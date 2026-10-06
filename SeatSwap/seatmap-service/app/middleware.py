"""
요청 가드 (순수 ASGI 미들웨어). 본문을 읽기 전에 동작하므로 큰 업로드나 인증 없는 요청이 메모리/디스크를 쓰기 전에 막힌다.

1. 내부 키: 환경변수 SEATMAP_INTERNAL_KEY가 설정돼 있으면 /api/* 요청은 헤더 X-Internal-Key가 같아야 한다.
   미설정이면 개발 모드로 보고 허용한다 (운영에서는 반드시 설정하고, 백엔드가 같은 값을 헤더로 보낸다).
2. 본문 크기: Content-Length가 상한을 넘으면 즉시 413, 없거나 거짓이면(청크 전송 등) 읽는 도중 상한을 넘는 순간 중단.
   업로드(/api/seatmap/recognize)는 이미지 상한 + 멀티파트 여유, 나머지 JSON 요청은 16KB.
"""
from __future__ import annotations

import hmac
import json
import os

from app.errors import ServiceError
from app.safe_fetch import MAX_IMAGE_BYTES

UPLOAD_PATH = "/api/seatmap/recognize"
UPLOAD_BODY_LIMIT = MAX_IMAGE_BYTES + 64 * 1024
JSON_BODY_LIMIT = 16 * 1024
KEY_HEADER = b"x-internal-key"


def _limit_for(path: str) -> int:
    return UPLOAD_BODY_LIMIT if path == UPLOAD_PATH else JSON_BODY_LIMIT


def _too_large(path: str) -> ServiceError:
    if path == UPLOAD_PATH:
        return ServiceError("이미지 크기가 10MB를 초과합니다.", code="IMAGE_TOO_LARGE", status_code=413)
    return ServiceError("요청 본문이 너무 큽니다.", code="REQUEST_TOO_LARGE", status_code=413)


async def _reply(send, status: int, code: str, message: str) -> None:
    body = json.dumps({"code": code, "message": message}, ensure_ascii=False).encode()
    await send({"type": "http.response.start", "status": status,
                "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]})
    await send({"type": "http.response.body", "body": body})


class GuardMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not scope["path"].startswith("/api/"):
            return await self.app(scope, receive, send)

        path = scope["path"]
        headers = dict(scope["headers"])
        key = os.environ.get("SEATMAP_INTERNAL_KEY", "")
        if key and not hmac.compare_digest(headers.get(KEY_HEADER, b""), key.encode()):
            return await _reply(send, 401, "UNAUTHORIZED", "인증이 필요합니다.")

        limit = _limit_for(path)
        declared = headers.get(b"content-length", b"")
        if declared.isdigit() and int(declared) > limit:
            err = _too_large(path)
            return await _reply(send, err.status_code, err.code, err.message)

        total = 0
        exceeded = False

        async def limited_receive():
            nonlocal total, exceeded
            message = await receive()
            if message["type"] == "http.request":
                total += len(message.get("body", b""))
                if total > _limit_for(path):
                    exceeded = True
                    raise _too_large(path)
            return message

        async def guarded_send(message):
            # FastAPI는 본문 읽기 중 예외를 400으로 바꿔버리므로, 상한을 넘긴 요청의 응답은 여기서 413으로 교체한다.
            if exceeded:
                if message["type"] == "http.response.start":
                    err = _too_large(path)
                    await _reply(send, err.status_code, err.code, err.message)
                return
            await send(message)

        return await self.app(scope, limited_receive, guarded_send)
