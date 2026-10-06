"""
좌석맵 API. 백엔드(Spring Boot)가 호출하는 계약은 docs/API.md 참고.
요청/응답 JSON은 camelCase, 오류는 {"code","message"}.
오류 신고(SeatCorrection) 접수·판정·저장은 이 서비스가 아니라 Spring Boot가 담당한다.
"""
import threading

from fastapi import APIRouter, File, Query, UploadFile

from app.adapters import get_adapter, supported_sites
from app.adapters.base import SiteAdapter
from app.core.pipeline import recognize_image
from app.errors import BadRequestError, ImageTooLargeError, SeatMapNotAvailableError, ServiceBusyError
from app.safe_fetch import IMAGE_TYPES, MAX_HTML_BYTES, MAX_IMAGE_BYTES, safe_get
from app.schemas.seatmap import (AisleMode, AnalyzeRequest, AnalyzeResponse, DiscoverRequest,
                                 DiscoverResponse, ErrorResponse, ImageCandidate, RecognizeResponse,
                                 RecognizeUrlRequest)

router = APIRouter()

# CPU/메모리 보호: 동시에 인식하는 이미지 수 제한. 자리를 못 얻으면 _SLOT_TIMEOUT 뒤 503.
_recognize_slots = threading.BoundedSemaphore(2)
_SLOT_TIMEOUT = 5.0

_ERRORS = {
    400: {"model": ErrorResponse}, 401: {"model": ErrorResponse}, 413: {"model": ErrorResponse},
    415: {"model": ErrorResponse}, 422: {"model": ErrorResponse}, 429: {"model": ErrorResponse},
    502: {"model": ErrorResponse}, 503: {"model": ErrorResponse},
}


def _adapter(site: str) -> SiteAdapter:
    adapter = get_adapter(site)
    if adapter is None:
        raise BadRequestError(f"지원하지 않는 사이트입니다. 지원: {', '.join(supported_sites())}",
                              code="UNSUPPORTED_SITE")
    return adapter


def _discover(adapter: SiteAdapter, product_id: str) -> tuple[str, list[ImageCandidate]]:
    if not adapter.is_valid_product_id(product_id):
        raise BadRequestError("productId 형식이 올바르지 않습니다.", code="INVALID_PRODUCT_ID")
    page_url = adapter.build_product_url(product_id)
    page = safe_get(page_url, adapter.page_hosts, accept_prefixes=("text/html",), max_bytes=MAX_HTML_BYTES)
    html = page.body.decode("utf-8", errors="replace")
    images = [ImageCandidate(url=c.url, source=c.source) for c in adapter.parse_seatmap_images(html)]
    return page_url, images


def _recognize(data: bytes, aisle_mode: str) -> dict:
    if not _recognize_slots.acquire(timeout=_SLOT_TIMEOUT):
        raise ServiceBusyError("인식 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도하세요.")
    try:
        return recognize_image(data, aisle_mode)
    finally:
        _recognize_slots.release()


def _fetch_image(adapter: SiteAdapter, url: str) -> bytes:
    if not adapter.is_allowed_image_url(url):
        raise BadRequestError("허용되지 않은 이미지 주소입니다.", code="IMAGE_URL_NOT_ALLOWED")
    return safe_get(url, adapter.image_hosts, accept_prefixes=IMAGE_TYPES, max_bytes=MAX_IMAGE_BYTES).body


@router.post("/discover", response_model=DiscoverResponse, responses=_ERRORS)
def discover(req: DiscoverRequest):
    """상품 페이지에서 좌석맵 이미지 후보를 찾는다. 후보가 없으면 422 SEATMAP_NOT_AVAILABLE."""
    adapter = _adapter(req.site)
    page_url, images = _discover(adapter, req.product_id)
    if not images:
        raise SeatMapNotAvailableError(
            "이 상품 페이지에는 공개된 좌석맵 이미지가 없습니다. 이미지를 직접 업로드하거나 이미지 주소를 입력해 주세요.")
    return DiscoverResponse(site=adapter.site, product_id=req.product_id, page_url=page_url, images=images)


@router.post("/recognize", response_model=RecognizeResponse, responses=_ERRORS)
def recognize(file: UploadFile = File(...), aisle_mode: AisleMode = Query("continue", alias="aisleMode")):
    """
    업로드한 좌석맵 이미지(PNG/JPEG/WebP, 10MB 이하)를 인식한다. multipart/form-data, 필드명 file.
    본문 크기는 GuardMiddleware가 읽기 전/도중에 제한한다(여기 검사는 마지막 방어선).
    """
    data = file.file.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise ImageTooLargeError("이미지 크기가 10MB를 초과합니다.")
    return _recognize(data, aisle_mode)


@router.post("/recognize-url", response_model=RecognizeResponse, responses=_ERRORS)
def recognize_url(req: RecognizeUrlRequest):
    """사이트 어댑터가 허용한 이미지 호스트의 이미지 주소를 받아 인식한다."""
    adapter = _adapter(req.site)
    return _recognize(_fetch_image(adapter, req.image_url), req.aisle_mode)


@router.post("/analyze", response_model=AnalyzeResponse, responses=_ERRORS)
def analyze(req: AnalyzeRequest):
    """{site, productId} -> 이미지 확보 -> 인식을 한 번에. 후보가 여럿이면 첫 후보를 쓴다."""
    adapter = _adapter(req.site)
    _page_url, images = _discover(adapter, req.product_id)
    if not images:
        raise SeatMapNotAvailableError(
            "이 상품 페이지에는 공개된 좌석맵 이미지가 없습니다. 이미지를 직접 업로드하거나 이미지 주소를 입력해 주세요.")
    url = images[0].url
    result = _recognize(_fetch_image(adapter, url), req.aisle_mode)
    result["sourceImageUrl"] = url
    return result
