import pytest
from fastapi.testclient import TestClient

from app.api import seatmap as api
from app.errors import FetchBlockedError
from app.safe_fetch import FetchResult
from main import app
from tests.synth import make_seatmap

client = TestClient(app, raise_server_exceptions=False)
PNG, _ = make_seatmap(rows=3, left=4, right=4)


def fake_fetch(pages=None, images=None):
    calls = []

    def _get(url, hosts, **kw):
        calls.append(url)
        if url.startswith("https://ticket.melon.com/"):
            return FetchResult(url, "text/html", (pages or "<html></html>").encode())
        if url in (images or {}):
            return FetchResult(url, "image/png", images[url])
        raise FetchBlockedError("blocked")
    _get.calls = calls
    return _get


def test_health():
    assert client.get("/health").status_code == 200


def test_recognize_upload_contract():
    r = client.post("/api/seatmap/recognize", files={"file": ("m.png", PNG, "image/png")})
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"image", "seats", "rows", "stats", "warnings"}
    assert set(body["seats"][0]) == {"uid", "row", "col", "x", "y", "w", "h"}
    assert body["stats"]["blockCount"] == 24 and len(body["seats"]) == 24
    assert set(body["rows"][0]) == {"row", "rowSource", "labelConfidence", "seatCount", "aisles"}


def test_recognize_upload_errors():
    r = client.post("/api/seatmap/recognize", files={"file": ("a.png", b"not an image", "image/png")})
    assert r.status_code == 415 and r.json()["code"] == "UNSUPPORTED_IMAGE"
    r = client.post("/api/seatmap/recognize")
    assert r.status_code == 400 and r.json()["code"] == "BAD_REQUEST"
    r = client.post("/api/seatmap/recognize?aisleMode=bogus", files={"file": ("m.png", PNG, "image/png")})
    assert r.status_code == 400


def test_upload_too_large(monkeypatch):
    monkeypatch.setattr(api, "MAX_IMAGE_BYTES", 100)
    r = client.post("/api/seatmap/recognize", files={"file": ("m.png", PNG, "image/png")})
    assert r.status_code == 413 and r.json()["code"] == "IMAGE_TOO_LARGE"


def test_discover_not_available_when_page_has_no_seatmap(monkeypatch):
    monkeypatch.setattr(api, "safe_get", fake_fetch())
    r = client.post("/api/seatmap/discover", json={"site": "melon", "productId": "213480"})
    assert r.status_code == 422 and r.json()["code"] == "SEATMAP_NOT_AVAILABLE"


def test_discover_and_analyze_success(monkeypatch):
    img = "https://cdnticket.melon.co.kr/x/seat.png"
    fetch = fake_fetch('<img class="placeImg" data-src="%s">' % img, {img: PNG})
    monkeypatch.setattr(api, "safe_get", fetch)
    r = client.post("/api/seatmap/discover", json={"site": "melon", "productId": "213480"})
    assert r.status_code == 200
    assert r.json() == {"site": "melon", "productId": "213480",
                        "pageUrl": "https://ticket.melon.com/performance/index.htm?prodId=213480",
                        "images": [{"url": img, "source": "venue_tab"}]}
    r = client.post("/api/seatmap/analyze", json={"site": "melon", "productId": "213480", "aisleMode": "skip"})
    assert r.status_code == 200 and r.json()["sourceImageUrl"] == img and len(r.json()["seats"]) == 24


def test_input_validation(monkeypatch):
    monkeypatch.setattr(api, "safe_get", fake_fetch())
    r = client.post("/api/seatmap/discover", json={"site": "yes24", "productId": "1"})
    assert r.status_code == 400 and r.json()["code"] == "UNSUPPORTED_SITE"
    for pid in ["abc", "1&x=2", "https://evil.com"]:
        r = client.post("/api/seatmap/discover", json={"site": "melon", "productId": pid})
        assert r.status_code == 400 and r.json()["code"] == "INVALID_PRODUCT_ID"
    assert client.post("/api/seatmap/discover", json={"site": "melon"}).status_code == 400


def test_recognize_url_only_allowed_image_hosts(monkeypatch):
    fetch = fake_fetch(images={"https://cdnticket.melon.co.kr/a.png": PNG})
    monkeypatch.setattr(api, "safe_get", fetch)
    ok = client.post("/api/seatmap/recognize-url",
                     json={"site": "melon", "imageUrl": "https://cdnticket.melon.co.kr/a.png"})
    assert ok.status_code == 200
    for bad in ["http://cdnticket.melon.co.kr/a.png", "https://evil.com/a.png",
                "https://169.254.169.254/latest/meta-data", "file:///etc/passwd"]:
        r = client.post("/api/seatmap/recognize-url", json={"site": "melon", "imageUrl": bad})
        assert r.status_code == 400 and r.json()["code"] == "IMAGE_URL_NOT_ALLOWED"
    assert fetch.calls == ["https://cdnticket.melon.co.kr/a.png"]  # 막힌 주소는 요청조차 안 함
