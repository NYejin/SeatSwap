"""safe_fetch 보강 테스트: IP 범위 차단 경로, 시간 제한(slow-loris), 연결 계층, 빈도 제한 단위."""
import socket
import ssl
import time

import pytest

from app import safe_fetch
from app.errors import FetchBlockedError, FetchFailedError
from app.safe_fetch import RateLimiter, safe_get
from tests.test_safe_fetch import HOSTS, FakeConn, FakeResp, ok, run


def get(factory, **kw):
    return safe_get("https://ticket.melon.com/a", HOSTS, resolver=kw.pop("resolver", lambda h: ["8.8.8.8"]),
                    connection_factory=factory, rate_limiter=RateLimiter(100), **kw)


# ---- IP 범위 차단 경로 (실제 safe_get 흐름, IPv6 포함) ----

@pytest.mark.parametrize("ips", [
    ["::1"], ["fe80::1"], ["fc00::1"], ["::ffff:10.0.0.1"], ["64:ff9b::a00:1"], ["2002:a00:1::"],
    ["8.8.8.8", "::1"], ["100.64.0.1"], ["0.0.0.0"], ["192.168.0.10"], ["169.254.169.254"],
])
def test_safe_get_blocks_internal_resolution_without_connecting(ips):
    with pytest.raises(FetchBlockedError):
        run({}, resolver=lambda h: ips)
    assert FakeConn.log == []


def test_safe_get_allows_public_ipv6():
    r = run({("ticket.melon.com", "/a"): ok(b"v6")}, resolver=lambda h: ["2606:4700:4700::1111"])
    assert r.body == b"v6" and FakeConn.log[0][1] == "2606:4700:4700::1111"


def test_unparsable_resolved_address_is_blocked():
    with pytest.raises(FetchBlockedError):
        run({}, resolver=lambda h: ["not-an-ip"])


def test_empty_resolution_fails():
    with pytest.raises(FetchFailedError):
        run({}, resolver=lambda h: [])


# ---- 시간 제한 ----

def test_dns_resolution_timeout(monkeypatch):
    monkeypatch.setattr(safe_fetch, "DNS_TIMEOUT", 0.2)

    def slow(host):
        time.sleep(1.0)
        return ["8.8.8.8"]
    t0 = time.monotonic()
    with pytest.raises(FetchFailedError, match="시간 초과"):
        run({}, resolver=slow)
    assert time.monotonic() - t0 < 0.9


class TrickleResp(FakeResp):
    """한 바이트씩 천천히 보내는 응답 (slow-loris 본문)."""

    def __init__(self, delay):
        super().__init__(200, {"Content-Type": "image/png"}, b"")
        self.delay = delay

    def read1(self, n):
        time.sleep(self.delay)
        return b"x"


def test_slow_body_hits_total_deadline(monkeypatch):
    monkeypatch.setattr(safe_fetch, "TOTAL_DEADLINE", 0.5)
    t0 = time.monotonic()
    with pytest.raises(FetchFailedError, match="오래 걸"):
        run({("ticket.melon.com", "/a"): TrickleResp(0.1)}, max_bytes=10_000_000)
    assert time.monotonic() - t0 < 1.5


def test_slow_headers_hit_total_deadline(monkeypatch):
    monkeypatch.setattr(safe_fetch, "TOTAL_DEADLINE", 0.3)

    class SlowHeaders(FakeConn):
        def getresponse(self):
            time.sleep(0.5)
            return ok(b"x")
    with pytest.raises(FetchFailedError, match="오래 걸"):
        get(lambda host, ip, t: SlowHeaders({}, host, ip))


def test_watchdog_closes_blocked_socket_at_deadline(monkeypatch):
    """헤더를 한 바이트도 안 보내는 서버: 소켓 타임아웃(30초)이 아니라 워치독이 데드라인에 소켓을 닫는다."""
    monkeypatch.setattr(safe_fetch, "TOTAL_DEADLINE", 0.4)
    monkeypatch.setattr(safe_fetch, "READ_TIMEOUT", 30.0)
    a, b = socket.socketpair()

    class Blocking(FakeConn):
        def __init__(self, *args):
            super().__init__(*args)
            self.sock = a

        def getresponse(self):
            if not self.sock.recv(1):
                raise OSError("closed")
    t0 = time.monotonic()
    try:
        with pytest.raises(FetchFailedError, match="오래 걸"):
            get(lambda host, ip, t: Blocking({}, host, ip))
        assert time.monotonic() - t0 < 2.0
    finally:
        a.close()
        b.close()


def test_connection_gets_connect_timeout_not_read_timeout():
    seen = []

    def factory(host, ip, timeout):
        seen.append(timeout)
        return FakeConn({("ticket.melon.com", "/a"): ok()}, host, ip)
    get(factory)
    assert seen == [safe_fetch.CONNECT_TIMEOUT] and safe_fetch.CONNECT_TIMEOUT == 5.0


def test_read_timeout_is_set_after_connect_and_shrinks_with_deadline(monkeypatch):
    monkeypatch.setattr(safe_fetch, "TOTAL_DEADLINE", 3.0)
    monkeypatch.setattr(safe_fetch, "READ_TIMEOUT", 10.0)
    timeouts = []

    class Sock:
        def settimeout(self, t):
            timeouts.append(t)

        def shutdown(self, *_):
            pass

        def close(self):
            pass

    class WithSock(FakeConn):
        def __init__(self, *a):
            super().__init__(*a)
            self.sock = Sock()
    get(lambda host, ip, t: WithSock({("ticket.melon.com", "/a"): ok(b"abc")}, host, ip))
    assert timeouts and all(0 < t <= 3.0 for t in timeouts)  # READ_TIMEOUT(10)가 아니라 남은 시간으로 줄어듦


def test_rate_limit_counts_requests_not_hops():
    limiter = RateLimiter(1)
    routes = {("ticket.melon.com", "/a"): FakeResp(302, {"Location": "/b"}),
              ("ticket.melon.com", "/b"): FakeResp(302, {"Location": "/c"}),
              ("ticket.melon.com", "/c"): ok(b"done")}
    assert run(routes, rate_limiter=limiter).body == b"done"  # 한도 1건이어도 hop 3번이 통과


# ---- 연결 계층: _PinnedHTTPSConnection ----

def test_pinned_connection_connects_to_validated_ip_with_sni_of_hostname(monkeypatch):
    calls = {}
    raw = object()

    def fake_create_connection(addr, timeout=None, *a, **k):
        calls["addr"], calls["timeout"] = addr, timeout
        return raw

    class Ctx:
        verify_mode = ssl.CERT_REQUIRED
        check_hostname = True
        post_handshake_auth = None

        def wrap_socket(self, sock, server_hostname=None):
            calls["wrapped"], calls["sni"] = sock, server_hostname
            return "tls-socket"
    monkeypatch.setattr(safe_fetch.socket, "create_connection", fake_create_connection)
    conn = safe_fetch._PinnedHTTPSConnection("ticket.melon.com", "203.0.113.7", 4.5, Ctx())
    conn.connect()
    assert calls["addr"] == ("203.0.113.7", 443)       # DNS 재조회 없이 검증한 IP로 접속
    assert calls["timeout"] == 4.5                      # 접속 타임아웃
    assert calls["wrapped"] is raw and calls["sni"] == "ticket.melon.com"  # SNI/검증은 호스트명 기준
    assert conn.sock == "tls-socket"


def test_pinned_connection_closes_raw_socket_when_tls_fails(monkeypatch):
    closed = []

    class Raw:
        def close(self):
            closed.append(True)

    class Ctx:
        verify_mode = ssl.CERT_REQUIRED
        check_hostname = True
        post_handshake_auth = None

        def wrap_socket(self, sock, server_hostname=None):
            raise ssl.SSLError("bad cert")
    monkeypatch.setattr(safe_fetch.socket, "create_connection", lambda *a, **k: Raw())
    conn = safe_fetch._PinnedHTTPSConnection("ticket.melon.com", "203.0.113.7", 5, Ctx())
    with pytest.raises(ssl.SSLError):
        conn.connect()
    assert closed == [True]


def test_default_factory_verifies_certificates():
    conn = safe_fetch._default_connection_factory("ticket.melon.com", "203.0.113.7", 5.0)
    ctx = conn._context
    assert ctx.verify_mode == ssl.CERT_REQUIRED and ctx.check_hostname
    assert ctx.minimum_version >= ssl.TLSVersion.TLSv1_2
    assert conn.host == "ticket.melon.com" and conn.timeout == 5.0


def test_connect_failure_is_reported_as_fetch_failed():
    class Refuse(FakeConn):
        def connect(self):
            raise ConnectionRefusedError()
    with pytest.raises(FetchFailedError):
        get(lambda host, ip, t: Refuse({}, host, ip))
