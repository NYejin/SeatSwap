"""리뷰 낮음 항목(L1~L4, L6, L9) 테스트."""
import random
import socket
import ssl
import statistics
import time
from urllib.parse import quote

import pytest
from fastapi.testclient import TestClient

from app import safe_fetch
from app.adapters.base import SiteAdapter
from app.adapters.melon import MelonAdapter
from app.api import seatmap as api
from app.core import ocr
from app.errors import FetchFailedError
from app.safe_fetch import IMAGE_TYPES, RateLimiter, safe_get
from main import app
from tests.test_safe_fetch import HOSTS, FakeConn, FakeResp, ok, run

SEPARATORS = "/%?&=:@!$'()*+,;-._~"


def get(factory, ips=("8.8.8.8",), **kw):
    return safe_get("https://ticket.melon.com/a", HOSTS, resolver=lambda h: list(ips),
                    connection_factory=factory, rate_limiter=RateLimiter(100), **kw)


# ---- L1: 비ASCII / ValueError ----

def test_korean_redirect_location_is_percent_encoded_not_500():
    target = "https://cdnticket.melon.co.kr/이미지.png?q=값"
    routes = {("ticket.melon.com", "/a"): FakeResp(302, {"Location": target}),
              ("cdnticket.melon.co.kr", quote("/이미지.png?q=값", safe=SEPARATORS)): ok(b"img")}
    r = run(routes)
    assert r.body == b"img"
    sent = [p for _h, _ip, p, _hd in FakeConn.log]
    assert all(p.isascii() for p in sent) and sent[1] == "/%EC%9D%B4%EB%AF%B8%EC%A7%80.png?q=%EA%B0%92"


def test_already_encoded_path_is_not_double_encoded():
    routes = {("ticket.melon.com", "/a"): FakeResp(302, {"Location": "/p%20q?x=%EA%B0%92"}),
              ("ticket.melon.com", "/p%20q?x=%EA%B0%92"): ok(b"ok")}
    assert run(routes).body == b"ok"


def test_unicode_encode_error_from_http_client_becomes_fetch_failed():
    class Raises(FakeConn):
        def request(self, *a, **k):
            raise UnicodeEncodeError("ascii", "한", 0, 1, "ordinal not in range")
    with pytest.raises(FetchFailedError):
        get(lambda host, ip, t: Raises({}, host, ip))


def test_value_error_from_request_becomes_fetch_failed():
    class Raises(FakeConn):
        def request(self, *a, **k):
            raise ValueError("Invalid header value")
    with pytest.raises(FetchFailedError):
        get(lambda host, ip, t: Raises({}, host, ip))


def test_unparsable_redirect_location_becomes_fetch_failed():
    routes = {("ticket.melon.com", "/a"): FakeResp(302, {"Location": "https://[::1/x"})}
    with pytest.raises(FetchFailedError):
        run(routes)


def test_real_http_client_would_fail_on_raw_korean_but_quoted_path_is_ascii():
    import http.client
    conn = http.client.HTTPConnection("example.invalid")
    with pytest.raises(UnicodeEncodeError):
        conn._send_request("GET", "/이미지", None, {}, False)  # 이 입력을 그대로 보내면 500의 원인
    assert quote("/이미지", safe=SEPARATORS).isascii()


# ---- L2: 검증된 IP 순차 시도 ----

def test_falls_back_to_next_validated_ip_when_first_fails():
    tried, closed = [], []

    class Flaky(FakeConn):
        def connect(self):
            tried.append(self.ip)
            if self.ip.startswith("2606"):
                raise OSError("network unreachable")  # IPv6 라우팅 불가

        def close(self):
            closed.append(self.ip)
    routes = {("ticket.melon.com", "/a"): ok(b"v4")}
    r = get(lambda host, ip, t: Flaky(routes, host, ip), ips=("2606:4700:4700::1111", "8.8.8.8"))
    assert r.body == b"v4" and tried == ["2606:4700:4700::1111", "8.8.8.8"]
    assert closed[0] == "2606:4700:4700::1111"  # 실패한 시도는 닫음


def test_all_ips_failing_raises_and_attempts_are_capped():
    tried = []

    class Down(FakeConn):
        def connect(self):
            tried.append(self.ip)
            raise ConnectionRefusedError()
    ips = [f"8.8.8.{i}" for i in range(1, 8)]
    with pytest.raises(FetchFailedError):
        get(lambda host, ip, t: Down({}, host, ip), ips=ips)
    assert tried == ips[:safe_fetch.MAX_CONNECT_ATTEMPTS]


def test_ip_fallback_stays_inside_total_deadline(monkeypatch):
    monkeypatch.setattr(safe_fetch, "TOTAL_DEADLINE", 0.3)

    class Slow(FakeConn):
        def connect(self):
            time.sleep(0.2)
            raise OSError("timeout")
    t0 = time.monotonic()
    with pytest.raises(FetchFailedError, match="오래 걸"):
        get(lambda host, ip, t: Slow({}, host, ip), ips=["8.8.8.1", "8.8.8.2", "8.8.8.3", "8.8.8.4"])
    assert time.monotonic() - t0 < 1.0


def test_fallback_never_tries_unvalidated_ips():
    tried = []

    class Spy(FakeConn):
        def connect(self):
            tried.append(self.ip)
    with pytest.raises(safe_fetch.FetchBlockedError):
        get(lambda host, ip, t: Spy({}, host, ip), ips=("8.8.8.8", "10.0.0.5"))
    assert tried == []


# ---- L3: 소켓 누수 ----

def test_failed_connect_attempts_close_their_connection():
    closed = []

    class Down(FakeConn):
        def connect(self):
            raise OSError("x")

        def close(self):
            closed.append(self.ip)
    with pytest.raises(FetchFailedError):
        get(lambda host, ip, t: Down({}, host, ip), ips=["8.8.8.1", "8.8.8.2"])
    assert closed == ["8.8.8.1", "8.8.8.2"]


def test_tls_handshake_failure_really_closes_the_raw_socket(monkeypatch):
    a, b = socket.socketpair()
    b.sendall(b"not tls at all\r\n")
    b.close()
    monkeypatch.setattr(safe_fetch.socket, "create_connection", lambda *args, **kw: a)
    conn = safe_fetch._PinnedHTTPSConnection("ticket.melon.com", "203.0.113.7", 2.0, ssl.create_default_context())
    with pytest.raises(OSError):  # ssl.SSLError 포함
        conn.connect()
    assert a.fileno() == -1  # 닫힘


# ---- L4: Content-Type 좁히기 ----

@pytest.mark.parametrize("ctype", ["image/png", "image/jpeg", "image/webp", "IMAGE/PNG; charset=binary"])
def test_allowed_image_types(ctype):
    assert run({("ticket.melon.com", "/a"): ok(b"x", ctype)}).body == b"x"


@pytest.mark.parametrize("ctype", ["image/svg+xml", "image/gif", "image/png-evil", "image/", "image/x-icon",
                                   "text/html", "application/octet-stream", ""])
def test_other_content_types_rejected(ctype):
    with pytest.raises(FetchFailedError):
        run({("ticket.melon.com", "/a"): ok(b"x", ctype)})


def test_html_fetch_still_accepts_text_prefix():
    r = run({("ticket.melon.com", "/a"): ok(b"<html>", "text/html; charset=utf-8")}, accept_prefixes=("text/html",))
    assert r.body == b"<html>"


def test_accept_header_lists_exact_types_and_api_uses_them(monkeypatch):
    run({("ticket.melon.com", "/a"): ok()})
    assert FakeConn.log[0][3]["Accept"] == "image/png, image/jpeg, image/webp"
    seen = {}

    def fake_get(url, hosts, **kw):
        seen.update(kw)
        return safe_fetch.FetchResult(url, "image/png", b"x")
    monkeypatch.setattr(api, "safe_get", fake_get)
    api._fetch_image(MelonAdapter(), "https://cdnticket.melon.co.kr/a.png")
    assert tuple(seen["accept_prefixes"]) == IMAGE_TYPES == ("image/png", "image/jpeg", "image/webp")


# ---- L6: cluster_rows ----

def reference_cluster(seats, y_ratio=0.5):
    """이전 구현(평균을 매번 sum으로 재계산)과 같은 결과여야 한다."""
    tol = max(3.0, statistics.median(s["h"] for s in seats) * y_ratio)
    rows, mean = [], 0.0
    for s in sorted(seats, key=lambda s: s["cy"]):
        if rows and abs(s["cy"] - mean) <= tol:
            rows[-1].append(s)
            mean = sum(t["cy"] for t in rows[-1]) / len(rows[-1])
        else:
            rows.append([s])
            mean = s["cy"]
    for r in rows:
        r.sort(key=lambda s: s["x"])
    return rows


def test_cluster_rows_matches_reference_on_random_input():
    rnd = random.Random(7)
    seats = [{"x": rnd.randint(0, 900), "cy": rnd.uniform(0, 400), "h": rnd.choice([16, 18, 20])} for _ in range(600)]
    assert [[id(s) for s in r] for r in ocr.cluster_rows(seats)] == \
        [[id(s) for s in r] for r in reference_cluster(seats)]


def test_cluster_rows_is_not_quadratic():
    seats = [{"x": i, "cy": 100.0 + (i % 3) * 0.1, "h": 18} for i in range(60000)]  # 한 행에 6만 블록
    t0 = time.monotonic()
    rows = ocr.cluster_rows(seats)
    assert len(rows) == 1 and len(rows[0]) == 60000
    assert time.monotonic() - t0 < 2.0  # 이전 구현은 수십억 번 연산


# ---- L9: 미사용 속성 / 이름 가림 ----

def test_unused_allowed_hosts_property_removed():
    assert not hasattr(SiteAdapter, "allowed_hosts") and not hasattr(MelonAdapter(), "allowed_hosts")


def test_schema_does_not_shadow_builtin_warning():
    from app.schemas import seatmap as schemas
    assert not hasattr(schemas, "Warning") and hasattr(schemas, "WarningInfo")
    assert issubclass(Warning, Exception)  # 내장은 그대로


def test_response_warning_json_contract_unchanged():
    client = TestClient(app)
    spec = client.get("/openapi.json").json()["components"]["schemas"]
    assert "WarningInfo" in spec and set(spec["WarningInfo"]["properties"]) == {"code", "message"}
    assert "Warning" not in spec
    assert spec["RecognizeResponse"]["properties"]["warnings"]["items"]["$ref"].endswith("/WarningInfo")
