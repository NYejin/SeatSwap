import http.client

import pytest

from app.errors import FetchBlockedError, FetchFailedError, RateLimitedError
from app.safe_fetch import RateLimiter, is_blocked_ip, safe_get, validate_url

HOSTS = ["cdnticket.melon.co.kr", "ticket.melon.com"]


@pytest.mark.parametrize("ip", [
    "10.0.0.1", "172.16.5.4", "192.168.1.1", "127.0.0.1", "169.254.169.254", "0.0.0.0", "100.64.0.1",
    "224.0.0.1", "240.0.0.1", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
    "::", "64:ff9b::7f00:1", "2002:7f00:1::",
])
def test_blocked_ips(ip):
    assert is_blocked_ip(ip)


@pytest.mark.parametrize("ip", ["8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"])
def test_public_ips_allowed(ip):
    assert not is_blocked_ip(ip)


@pytest.mark.parametrize("url", [
    "http://ticket.melon.com/x",
    "https://ticket.melon.com:8443/x",
    "https://evil.com/x",
    "https://ticket.melon.com.evil.com/x",
    "https://evilticket.melon.com/x",
    "https://user:pw@ticket.melon.com/x",
    "https://ticket.melon.com@evil.com/x",
    "https://169.254.169.254/latest/meta-data",
    "ftp://ticket.melon.com/x",
    "file:///etc/passwd",
    "https://[::1]/x",
])
def test_validate_url_rejects(url):
    with pytest.raises(FetchBlockedError):
        validate_url(url, HOSTS)


def test_validate_url_ok_and_case_insensitive():
    assert validate_url("https://TICKET.melon.com:443/performance/index.htm?prodId=1", HOSTS) == \
        ("ticket.melon.com", "/performance/index.htm?prodId=1")


class FakeResp:
    def __init__(self, status=200, headers=None, body=b""):
        self.status, self._h, self._body, self._pos = status, headers or {}, body, 0

    def getheader(self, k, d=None):
        return {kk.lower(): v for kk, v in self._h.items()}.get(k.lower(), d)

    def read1(self, n):
        chunk = self._body[self._pos:self._pos + n]
        self._pos += n
        return chunk


class FakeConn:
    log = []

    def __init__(self, routes, host, ip):
        self.routes, self.host, self.ip, self.timeout = routes, host, ip, None
        self.sock = None

    def connect(self):
        pass

    def request(self, method, path, headers=None):
        self.path, self.headers = path, headers or {}
        FakeConn.log.append((self.host, self.ip, path, self.headers))

    def getresponse(self):
        r = self.routes[(self.host, self.path)]
        if isinstance(r, Exception):
            raise r
        return r

    def close(self):
        pass


def run(routes, url="https://ticket.melon.com/a", resolver=None, **kw):
    FakeConn.log = []
    return safe_get(
        url, HOSTS,
        resolver=resolver or (lambda h: ["8.8.8.8"]),
        connection_factory=lambda host, ip, t: FakeConn(routes, host, ip),
        rate_limiter=kw.pop("rate_limiter", RateLimiter(100)), **kw)


def ok(body=b"x", ctype="image/png", **h):
    return FakeResp(200, {"Content-Type": ctype, **h}, body)


def test_success_pins_ip_and_identifies_itself():
    r = run({("ticket.melon.com", "/a"): ok(b"abc", "image/png; x=y")})
    assert r.body == b"abc" and r.content_type == "image/png"
    _host, ip, _path, headers = FakeConn.log[0]
    assert ip == "8.8.8.8"
    assert "SeatSwapBot" in headers["User-Agent"] and headers["Accept-Encoding"] == "identity"


def test_dns_with_any_private_ip_is_blocked_and_no_request_made():
    with pytest.raises(FetchBlockedError):
        run({}, resolver=lambda h: ["8.8.8.8", "10.0.0.5"])
    assert FakeConn.log == []


def test_metadata_ip_blocked():
    with pytest.raises(FetchBlockedError):
        run({}, resolver=lambda h: ["169.254.169.254"])


def test_redirect_followed_and_revalidated():
    routes = {("ticket.melon.com", "/a"): FakeResp(302, {"Location": "https://cdnticket.melon.co.kr/i.png"}),
              ("cdnticket.melon.co.kr", "/i.png"): ok(b"ok")}
    r = run(routes)
    assert r.body == b"ok" and r.redirects == ["https://ticket.melon.com/a"]


def test_redirect_to_disallowed_host_blocked():
    with pytest.raises(FetchBlockedError):
        run({("ticket.melon.com", "/a"): FakeResp(302, {"Location": "https://evil.com/x"})})


def test_redirect_to_http_or_internal_blocked():
    for loc in ("http://ticket.melon.com/x", "https://169.254.169.254/x"):
        with pytest.raises(FetchBlockedError):
            run({("ticket.melon.com", "/a"): FakeResp(302, {"Location": loc})})


def test_redirect_hop_dns_rebinding_blocked():
    calls = {"n": 0}

    def resolver(host):
        calls["n"] += 1
        return ["8.8.8.8"] if calls["n"] == 1 else ["127.0.0.1"]
    with pytest.raises(FetchBlockedError):
        run({("ticket.melon.com", "/a"): FakeResp(301, {"Location": "/b"})}, resolver=resolver)


def test_redirect_limit():
    with pytest.raises(FetchBlockedError):
        run({("ticket.melon.com", "/a"): FakeResp(302, {"Location": "/a"})})


def test_content_type_enforced():
    with pytest.raises(FetchFailedError):
        run({("ticket.melon.com", "/a"): ok(b"x", "text/html")})


def test_size_limit_declared_and_streamed():
    with pytest.raises(FetchFailedError):
        run({("ticket.melon.com", "/a"): ok(b"x", **{"Content-Length": "999"})}, max_bytes=10)
    with pytest.raises(FetchFailedError):
        run({("ticket.melon.com", "/a"): ok(b"x" * 100)}, max_bytes=10)


def test_non_200_and_network_errors():
    with pytest.raises(FetchFailedError):
        run({("ticket.melon.com", "/a"): FakeResp(404, {})})
    with pytest.raises(FetchFailedError):
        run({("ticket.melon.com", "/a"): http.client.HTTPException("boom")})
    with pytest.raises(FetchFailedError):
        run({("ticket.melon.com", "/a"): TimeoutError()})


def test_rate_limit():
    limiter = RateLimiter(2)
    for _ in range(2):
        run({("ticket.melon.com", "/a"): ok()}, rate_limiter=limiter)
    with pytest.raises(RateLimitedError):
        run({("ticket.melon.com", "/a"): ok()}, rate_limiter=limiter)


def test_rate_limit_window_expires():
    t = [0.0]
    limiter = RateLimiter(1, clock=lambda: t[0])
    limiter.check("h")
    with pytest.raises(RateLimitedError):
        limiter.check("h")
    t[0] = 61
    limiter.check("h")
