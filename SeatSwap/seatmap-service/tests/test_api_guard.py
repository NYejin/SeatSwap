"""API 가드: 업로드 상한(사전/스트리밍), 내부 키, 동시성 503, 오류 형식 통일, /docs 끄기, 로깅."""
import importlib
import logging
import threading

import pytest
from fastapi.testclient import TestClient

import main as main_module
from app import middleware
from app.api import seatmap as api
from app.errors import FetchBlockedError
from main import app
from tests.synth import make_seatmap

client = TestClient(app, raise_server_exceptions=False)
PNG, _ = make_seatmap(rows=3, left=4, right=4)
UPLOAD = "/api/seatmap/recognize"


def never_called(*a, **k):
    pytest.fail("본문 검사 전에 막혀야 하는데 핸들러까지 도달함")


# ---- 오류 형식 통일 ----

def test_404_and_405_use_service_error_format():
    r = client.get("/nope")
    assert r.status_code == 404 and r.json() == {"code": "NOT_FOUND", "message": "요청한 경로를 찾을 수 없습니다."}
    r = client.get("/api/seatmap/discover")
    assert r.status_code == 405 and r.json()["code"] == "METHOD_NOT_ALLOWED"


def test_corrections_endpoint_removed():
    r = client.post("/api/seatmap/corrections", json={"seatmap_id": 1})
    assert r.status_code == 404 and r.json()["code"] == "NOT_FOUND"


def test_unhandled_error_is_500_in_standard_format(monkeypatch):
    def boom(*a, **k):
        raise RuntimeError("secret internal detail")
    monkeypatch.setattr(api, "recognize_image", boom)
    r = client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")})
    assert r.status_code == 500 and r.json()["code"] == "INTERNAL_ERROR"
    assert "secret" not in r.text


def test_slot_is_released_after_errors(monkeypatch):
    def boom(*a, **k):
        raise RuntimeError("x")
    monkeypatch.setattr(api, "recognize_image", boom)
    for _ in range(4):  # 슬롯 2개 -> 새면 3번째부터 막힘
        assert client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")}).status_code == 500
    monkeypatch.undo()
    assert client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")}).status_code == 200


# ---- 업로드 상한 ----

def test_upload_rejected_by_content_length_before_handler(monkeypatch):
    monkeypatch.setattr(middleware, "UPLOAD_BODY_LIMIT", 1000)
    monkeypatch.setattr(api, "recognize_image", never_called)
    big, _ = make_seatmap()
    assert len(big) > 1000
    r = client.post(UPLOAD, files={"file": ("m.png", big, "image/png")})
    assert r.status_code == 413 and r.json()["code"] == "IMAGE_TOO_LARGE"


def test_upload_without_content_length_is_stopped_while_streaming(monkeypatch):
    monkeypatch.setattr(middleware, "UPLOAD_BODY_LIMIT", 1000)
    monkeypatch.setattr(api, "recognize_image", never_called)

    def chunks():
        yield b"--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n\r\n"
        for _ in range(10):
            yield b"x" * 400
        yield b"\r\n--b--\r\n"
    r = client.post(UPLOAD, content=chunks(), headers={"content-type": "multipart/form-data; boundary=b"})
    assert r.status_code == 413 and r.json()["code"] == "IMAGE_TOO_LARGE"


def test_json_body_limit():
    r = client.post("/api/seatmap/discover", json={"site": "melon", "productId": "1", "pad": "x" * 20000})
    assert r.status_code == 413 and r.json()["code"] == "REQUEST_TOO_LARGE"


def test_upload_within_limit_still_works():
    assert client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")}).status_code == 200


# ---- 내부 키 ----

def test_internal_key_required_when_configured(monkeypatch):
    monkeypatch.setenv("SEATMAP_INTERNAL_KEY", "s3cret")
    monkeypatch.setattr(api, "recognize_image", never_called)
    for headers in ({}, {"X-Internal-Key": "wrong"}, {"X-Internal-Key": ""}):
        r = client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")}, headers=headers)
        assert r.status_code == 401 and r.json()["code"] == "UNAUTHORIZED"
    assert client.get("/health").status_code == 200  # 헬스체크는 키 없이


def test_internal_key_accepted(monkeypatch):
    monkeypatch.setenv("SEATMAP_INTERNAL_KEY", "s3cret")
    r = client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")}, headers={"X-Internal-Key": "s3cret"})
    assert r.status_code == 200


def test_dev_mode_when_key_not_configured(monkeypatch):
    monkeypatch.delenv("SEATMAP_INTERNAL_KEY", raising=False)
    assert client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")}).status_code == 200


# ---- 동시성 ----

def test_busy_returns_503_after_slot_timeout(monkeypatch):
    sem = threading.BoundedSemaphore(1)
    sem.acquire()
    monkeypatch.setattr(api, "_recognize_slots", sem)
    monkeypatch.setattr(api, "_SLOT_TIMEOUT", 0.05)
    r = client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")})
    assert r.status_code == 503 and r.json()["code"] == "BUSY"


# ---- /docs 끄기 ----

def test_docs_can_be_disabled_by_env(monkeypatch):
    assert client.get("/docs").status_code == 200  # 기본은 켜짐
    monkeypatch.setenv("SEATMAP_ENABLE_DOCS", "false")
    try:
        importlib.reload(main_module)
        off = TestClient(main_module.app, raise_server_exceptions=False)
        for path in ("/docs", "/redoc", "/openapi.json"):
            r = off.get(path)
            assert r.status_code == 404 and r.json()["code"] == "NOT_FOUND"
    finally:
        monkeypatch.delenv("SEATMAP_ENABLE_DOCS")
        importlib.reload(main_module)


# ---- 로깅 ----

def test_blocked_fetch_is_logged(monkeypatch, caplog):
    def blocked(*a, **k):
        raise FetchBlockedError("허용되지 않은 호스트입니다.")
    monkeypatch.setattr(api, "safe_get", blocked)
    with caplog.at_level(logging.INFO, logger="seatmap"):
        r = client.post("/api/seatmap/discover", json={"site": "melon", "productId": "1"})
    assert r.status_code == 400 and r.json()["code"] == "FETCH_BLOCKED"
    assert any("FETCH_BLOCKED" in rec.getMessage() and rec.levelno >= logging.WARNING for rec in caplog.records)


def test_unhandled_error_is_logged_with_traceback(monkeypatch, caplog):
    def boom(*a, **k):
        raise RuntimeError("kaboom")
    monkeypatch.setattr(api, "recognize_image", boom)
    with caplog.at_level(logging.ERROR, logger="seatmap"):
        client.post(UPLOAD, files={"file": ("m.png", PNG, "image/png")})
    assert any(rec.exc_info for rec in caplog.records)
