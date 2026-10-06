import logging
import os

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api import seatmap
from app.core.ocr import tesseract_available
from app.errors import ServiceError
from app.middleware import GuardMiddleware

logging.basicConfig(level=os.environ.get("SEATMAP_LOG_LEVEL", "INFO"),
                    format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("seatmap")

# SEATMAP_ENABLE_DOCS=false 이면 /docs, /redoc, /openapi.json을 끈다 (운영 권장). 기본은 개발 편의상 켜짐.
_docs_on = os.environ.get("SEATMAP_ENABLE_DOCS", "true").strip().lower() != "false"
app = FastAPI(title="SeatSwap Seatmap Recognition Service",
              docs_url="/docs" if _docs_on else None,
              redoc_url="/redoc" if _docs_on else None,
              openapi_url="/openapi.json" if _docs_on else None)
app.add_middleware(GuardMiddleware)
app.include_router(seatmap.router, prefix="/api/seatmap", tags=["seatmap"])

# Spring Boot 메인 서버와 분리된 별도 프로세스로 실행 (결정사항, spring-boot-conventions 스킬 참고)

_HTTP_CODES = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}


@app.exception_handler(ServiceError)
async def service_error_handler(request: Request, exc: ServiceError):
    # 차단·외부 오류·서버 쪽 문제는 운영에서 보이도록 WARNING 이상으로 남긴다 (메시지는 repr로 개행 주입 방지)
    level = logging.WARNING if exc.status_code >= 429 or exc.code.startswith("FETCH") else logging.INFO
    logger.log(level, "service error code=%s status=%s path=%s msg=%r", exc.code, exc.status_code,
               request.url.path, exc.message)
    return JSONResponse(status_code=exc.status_code, content={"code": exc.code, "message": exc.message})


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(_: Request, exc: StarletteHTTPException):
    code = _HTTP_CODES.get(exc.status_code, f"HTTP_{exc.status_code}")
    message = {404: "요청한 경로를 찾을 수 없습니다.", 405: "허용되지 않는 메서드입니다."}.get(
        exc.status_code, "요청을 처리할 수 없습니다.")
    return JSONResponse(status_code=exc.status_code, content={"code": code, "message": message})


@app.exception_handler(RequestValidationError)
async def validation_error_handler(_: Request, exc: RequestValidationError):
    return JSONResponse(status_code=400, content={"code": "BAD_REQUEST", "message": "요청 형식이 올바르지 않습니다."})


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception):
    logger.error("unhandled error path=%s", request.url.path, exc_info=exc)
    return JSONResponse(status_code=500, content={"code": "INTERNAL_ERROR", "message": "서버 내부 오류가 발생했습니다."})


@app.get("/health")
def health():
    return {"status": "ok", "tesseract": tesseract_available()}
