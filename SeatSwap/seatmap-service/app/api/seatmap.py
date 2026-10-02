from fastapi import APIRouter
from app.schemas.seatmap import RecognizeRequest, RecognizeResponse, CorrectionRequest

router = APIRouter()


@router.post("/recognize", response_model=RecognizeResponse)
def recognize(req: RecognizeRequest):
    """
    1) req.image_url 이미지 다운로드
    2) core.detection.detect_seat_blocks()로 좌표 검출
    3) core.ocr로 행/열 번호 매핑
    4) Spring Boot가 저장할 수 있게 좌표 JSON 반환
    """
    # TODO: 구현
    return RecognizeResponse(seats=[])


@router.post("/corrections")
def report_correction(req: CorrectionRequest):
    """
    오류 신고 접수. 동일 (seatmap_id, corrected_label) 조합이 2건 이상이면
    자동 반영 — 실제 반영 로직은 Spring Boot SeatCorrection 테이블에서 처리하거나
    이 서비스가 Spring Boot API를 콜백 호출하는 방식 중 택1 (추후 결정).
    """
    # TODO: 구현
    return {"status": "received"}
