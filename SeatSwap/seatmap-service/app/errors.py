"""서비스 공통 오류. 모든 오류 응답은 {"code": "...", "message": "..."} 형식으로 나간다."""


class ServiceError(Exception):
    status_code = 500
    code = "INTERNAL_ERROR"

    def __init__(self, message: str, *, code: str | None = None, status_code: int | None = None):
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code
        if status_code is not None:
            self.status_code = status_code


class BadRequestError(ServiceError):
    status_code = 400
    code = "BAD_REQUEST"


class FetchBlockedError(ServiceError):
    """SSRF 방어 규칙에 걸려 요청 자체를 하지 않았다 (호스트/스킴/포트/IP/리다이렉트)."""
    status_code = 400
    code = "FETCH_BLOCKED"


class FetchFailedError(ServiceError):
    """외부 사이트 요청 실패 (타임아웃, 4xx/5xx, 크기 초과, Content-Type 불일치 등)."""
    status_code = 502
    code = "UPSTREAM_FETCH_FAILED"


class RateLimitedError(ServiceError):
    status_code = 429
    code = "RATE_LIMITED"


class SeatMapNotAvailableError(ServiceError):
    """사이트가 좌석맵 이미지를 로그인/캡차 없이 공개하지 않거나 페이지에서 찾지 못했다."""
    status_code = 422
    code = "SEATMAP_NOT_AVAILABLE"


class UnsupportedImageError(ServiceError):
    status_code = 415
    code = "UNSUPPORTED_IMAGE"


class ImageTooLargeError(ServiceError):
    status_code = 413
    code = "IMAGE_TOO_LARGE"


class RecognitionFailedError(ServiceError):
    """이미지는 정상이나 좌석 블록을 하나도 찾지 못했다."""
    status_code = 422
    code = "NO_SEATS_DETECTED"


class ImageTooComplexError(ServiceError):
    """블록 수/행 수가 상한을 넘었다 (좌석맵이 아니거나 노이즈가 많은 이미지)."""
    status_code = 422
    code = "IMAGE_TOO_COMPLEX"


class ServiceBusyError(ServiceError):
    status_code = 503
    code = "BUSY"


class UnauthorizedError(ServiceError):
    status_code = 401
    code = "UNAUTHORIZED"
