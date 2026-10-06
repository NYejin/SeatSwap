"""
안전한 외부 요청 기반 (SSRF 방어). 사이트 어댑터와 분리되어 있고, 외부로 나가는 모든 HTTP 요청은
이 모듈의 safe_get()만 거친다.

규칙
- https + 443 포트만. userinfo(user:pass@) 금지.
- 호스트는 호출자(어댑터)가 선언한 허용 목록과 정확히 일치해야 한다 (접미사 일치 금지).
- DNS 해석된 모든 IP가 공인(global) 주소여야 한다. 사설/루프백/링크로컬(169.254.169.254 포함)/
  0.0.0.0/예약/멀티캐스트/IPv4-mapped IPv6 내부 주소는 차단.
- 검증한 IP로 직접 접속하고(TLS SNI/인증서 검증은 원래 호스트명 기준) 재해석하지 않는다 -> DNS rebinding 방지.
- 리다이렉트는 자동 추종하지 않고 매 hop마다 위 검증을 다시 한다 (최대 MAX_REDIRECTS).
- 시간: DNS 해석 + 접속 + 헤더 + 본문을 합친 "총 데드라인"(TOTAL_DEADLINE)을 모든 단계가 공유한다.
  소켓 타임아웃은 매번 남은 시간으로 줄이고, 별도 워치독 타이머가 데드라인에 소켓을 강제로 닫는다
  (한 바이트씩 흘려보내는 slow-loris 응답 방어).
- 응답 크기 상한, Content-Type 제한, 식별 가능한 User-Agent, 호스트별 요청 빈도 제한(RateLimiter 한계 참고).
"""
from __future__ import annotations

import concurrent.futures
import http.client
import ipaddress
import logging
import socket
import ssl
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Callable, Iterable, Sequence
from urllib.parse import quote, urljoin, urlsplit

from app.errors import FetchBlockedError, FetchFailedError, RateLimitedError

logger = logging.getLogger("seatmap.fetch")

USER_AGENT = "SeatSwapBot/0.1 (+portfolio project; seatmap-service; contact: kidjin1016@gmail.com)"
MAX_REDIRECTS = 3
MAX_CONNECT_ATTEMPTS = 4  # 검증된 IP를 순차 시도하는 최대 횟수
# 좌석맵 이미지로 받을 Content-Type (svg+xml 등 스크립트가 들어갈 수 있는 형식은 제외). 정확히 일치해야 한다.
IMAGE_TYPES = ("image/png", "image/jpeg", "image/webp")
DNS_TIMEOUT = 5.0
CONNECT_TIMEOUT = 5.0
READ_TIMEOUT = 10.0       # 소켓 한 번의 recv 상한 (남은 총 시간이 더 작으면 그 값)
TOTAL_DEADLINE = 20.0     # DNS + 접속 + 헤더 + 본문 전체
READ_CHUNK = 16 * 1024
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_HTML_BYTES = 2 * 1024 * 1024
RATE_LIMIT_PER_MINUTE = 20  # 호스트당

_EXTRA_BLOCKED = [
    ipaddress.ip_network("169.254.0.0/16"),   # 링크로컬 + 클라우드 메타데이터
    ipaddress.ip_network("100.64.0.0/10"),    # CGNAT
    ipaddress.ip_network("0.0.0.0/8"),
    ipaddress.ip_network("192.0.0.0/24"),
    ipaddress.ip_network("198.18.0.0/15"),
    ipaddress.ip_network("224.0.0.0/4"),
    ipaddress.ip_network("240.0.0.0/4"),
    ipaddress.ip_network("fe80::/10"),
    ipaddress.ip_network("fc00::/7"),
    ipaddress.ip_network("64:ff9b::/96"),     # NAT64 (내부 IPv4 우회 방지)
]


def is_blocked_ip(ip: str | ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    addr = ipaddress.ip_address(ip) if isinstance(ip, str) else ip
    if isinstance(addr, ipaddress.IPv6Address):
        if addr.ipv4_mapped is not None:
            return is_blocked_ip(addr.ipv4_mapped)
        if addr.sixtofour is not None and is_blocked_ip(addr.sixtofour):
            return True
    if not addr.is_global:
        return True
    if addr.is_private or addr.is_loopback or addr.is_link_local or addr.is_multicast \
            or addr.is_reserved or addr.is_unspecified:
        return True
    return any(addr in net for net in _EXTRA_BLOCKED if net.version == addr.version)


def validate_url(url: str, allowed_hosts: Iterable[str]) -> tuple[str, str]:
    """(host, path_with_query)를 반환. 위반 시 FetchBlockedError."""
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise FetchBlockedError("URL 형식이 올바르지 않습니다.") from None
    if parts.scheme != "https":
        raise FetchBlockedError("https 주소만 허용됩니다.")
    if parts.username is not None or parts.password is not None:
        raise FetchBlockedError("사용자 정보가 포함된 주소는 허용되지 않습니다.")
    if port not in (None, 443):
        raise FetchBlockedError("443 포트만 허용됩니다.")
    host = (parts.hostname or "").lower()
    if not host or host not in {h.lower() for h in allowed_hosts}:
        raise FetchBlockedError("허용되지 않은 호스트입니다.")
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query
    return host, path


Resolver = Callable[[str], Sequence[str]]


def default_resolver(host: str) -> list[str]:
    try:
        infos = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise FetchFailedError("호스트 이름을 확인하지 못했습니다.") from e
    return list(dict.fromkeys(info[4][0] for info in infos))


# getaddrinfo는 시간 제한을 걸 수 없어 별도 스레드에서 돌리고 결과를 타임아웃으로 기다린다.
# 한계: 타임아웃된 스레드는 getaddrinfo가 끝날 때까지 남는다(풀 크기 4 -> 동시에 멈추면 이후 DNS 요청은 대기 후 시간 초과).
_dns_pool = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="dns")


def _resolve_with_timeout(resolver: Resolver, host: str, timeout: float) -> list[str]:
    fut = _dns_pool.submit(resolver, host)
    try:
        return list(fut.result(timeout=max(timeout, 0.01)))
    except concurrent.futures.TimeoutError:
        fut.cancel()
        raise FetchFailedError("호스트 이름 확인이 시간 초과되었습니다.") from None


def _type_ok(ctype: str, accepted: Sequence[str]) -> bool:
    """'/'로 끝나는 항목은 접두사(예: text/), 그 외는 정확히 일치."""
    return any(ctype == a or (a.endswith("/") and ctype.startswith(a)) for a in accepted)


_PATH_SAFE = "/%?&=:@!$'()*+,;-._~"


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """검증한 IP로 접속하되 SNI·인증서 검증은 원래 호스트명으로 한다."""

    def __init__(self, host: str, ip: str, timeout: float, context: ssl.SSLContext):
        super().__init__(host, 443, timeout=timeout, context=context)
        self._pinned_ip = ip

    def connect(self):
        sock = socket.create_connection((self._pinned_ip, 443), timeout=self.timeout)
        try:
            self.sock = self._context.wrap_socket(sock, server_hostname=self.host)
        except BaseException:
            sock.close()
            raise


ConnectionFactory = Callable[[str, str, float], http.client.HTTPSConnection]


def _default_connection_factory(host: str, ip: str, timeout: float) -> http.client.HTTPSConnection:
    ctx = ssl.create_default_context()  # 인증서·호스트명 검증 켜짐
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    return _PinnedHTTPSConnection(host, ip, timeout, ctx)


class RateLimiter:
    """
    호스트별 슬라이딩 윈도(60초) 빈도 제한. safe_get 호출 1회가 호스트당 1건이다(리다이렉트 hop마다 세지 않음).
    한계: 프로세스 메모리 안에서만 센다 -> uvicorn worker가 N개면 한도도 N배, 재시작하면 초기화,
    호출자(사용자)별 공정성 없음(백엔드가 사용자별 제한을 따로 걸어야 함).
    """

    def __init__(self, per_minute: int = RATE_LIMIT_PER_MINUTE, clock: Callable[[], float] = time.monotonic):
        self.per_minute = per_minute
        self._clock = clock
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, host: str) -> None:
        now = self._clock()
        with self._lock:
            q = self._hits[host]
            while q and now - q[0] > 60:
                q.popleft()
            if len(q) >= self.per_minute:
                raise RateLimitedError("외부 사이트 요청 빈도 제한에 걸렸습니다. 잠시 후 다시 시도하세요.")
            q.append(now)


default_rate_limiter = RateLimiter()


@dataclass
class FetchResult:
    url: str  # 최종 URL
    content_type: str
    body: bytes
    redirects: list[str] = field(default_factory=list)


class _Deadline:
    def __init__(self, seconds: float):
        self._end = time.monotonic() + seconds

    def remaining(self) -> float:
        return self._end - time.monotonic()

    def require(self, what: str = "외부 사이트 응답") -> float:
        rem = self.remaining()
        if rem <= 0:
            raise FetchFailedError(f"{what}이(가) 너무 오래 걸립니다.")
        return rem


def _abort_socket(sock, flag: threading.Event) -> None:
    flag.set()
    for op in (lambda: sock.shutdown(socket.SHUT_RDWR), sock.close):
        try:
            op()
        except OSError:
            pass


def _connect_any(ips: Sequence[str], host: str, factory: ConnectionFactory, deadline: "_Deadline"):
    """검증된 IP를 순서대로(총 데드라인 안에서, 최대 MAX_CONNECT_ATTEMPTS번) 시도해 처음 접속되는 연결을 돌려준다."""
    last: Exception | None = None
    for ip in list(ips)[:MAX_CONNECT_ATTEMPTS]:
        conn = factory(host, ip, min(CONNECT_TIMEOUT, deadline.require("접속")))
        try:
            conn.connect()  # 접속 타임아웃(CONNECT_TIMEOUT)은 연결 객체가 쓴다. 이후 읽기 타임아웃으로 바꾼다.
            return conn
        except (OSError, http.client.HTTPException, ValueError) as e:
            last = e
            conn.close()  # 실패한 시도의 소켓을 남기지 않는다
    raise FetchFailedError("외부 사이트에 접속하지 못했습니다.") from last


def safe_get(
    url: str,
    allowed_hosts: Iterable[str],
    *,
    accept_prefixes: Sequence[str] = IMAGE_TYPES,
    max_bytes: int = MAX_IMAGE_BYTES,
    resolver: Resolver = default_resolver,
    connection_factory: ConnectionFactory = _default_connection_factory,
    rate_limiter: RateLimiter | None = None,
    max_redirects: int = MAX_REDIRECTS,
) -> FetchResult:
    allowed = [h.lower() for h in allowed_hosts]
    limiter = rate_limiter or default_rate_limiter
    deadline = _Deadline(TOTAL_DEADLINE)
    current = url
    visited: list[str] = []
    charged: set[str] = set()

    for _hop in range(max_redirects + 1):
        deadline.require("외부 사이트 요청")
        try:
            host, path = validate_url(current, allowed)
        except FetchBlockedError as e:
            logger.warning("fetch blocked (url rule): %s", e.message)
            raise
        if host not in charged:  # 호스트당 1건으로 센다
            limiter.check(host)
            charged.add(host)

        ips = _resolve_with_timeout(resolver, host, min(DNS_TIMEOUT, deadline.require("호스트 이름 확인")))
        if not ips:
            raise FetchFailedError("호스트 이름을 확인하지 못했습니다.")
        for ip in ips:  # 하나라도 내부 주소면 거부 (혼합 응답 우회 방지)
            try:
                blocked = is_blocked_ip(ip)
            except ValueError:
                blocked = True
            if blocked:
                logger.warning("fetch blocked (ip rule): host=%s", host)
                raise FetchBlockedError("허용되지 않은 네트워크 주소로 해석되는 호스트입니다.")

        conn = _connect_any(ips, host, connection_factory, deadline)
        timed_out = threading.Event()
        watchdog: threading.Timer | None = None
        try:
            sock = getattr(conn, "sock", None)
            if sock is not None:  # 총 데드라인에 소켓을 강제로 닫는 워치독
                watchdog = threading.Timer(deadline.require(), _abort_socket, args=(sock, timed_out))
                watchdog.daemon = True
                watchdog.start()

            def set_read_timeout() -> None:
                rem = deadline.require()
                if sock is not None:
                    sock.settimeout(min(READ_TIMEOUT, rem))

            try:
                set_read_timeout()
                conn.request("GET", quote(path, safe=_PATH_SAFE), headers={
                    "Host": host,
                    "User-Agent": USER_AGENT,
                    "Accept": ", ".join(p + "*" if p.endswith("/") else p for p in accept_prefixes),
                    "Accept-Encoding": "identity",
                    "Connection": "close",
                })
                set_read_timeout()
                resp = conn.getresponse()
                deadline.require()

                if resp.status in (301, 302, 303, 307, 308):
                    location = resp.getheader("Location")
                    if not location:
                        raise FetchFailedError("리다이렉트 응답에 Location이 없습니다.")
                    visited.append(current)
                    try:
                        current = urljoin(f"https://{host}{path}", location)
                    except ValueError:
                        raise FetchFailedError("리다이렉트 주소가 올바르지 않습니다.") from None
                    continue  # 다음 hop에서 전체 재검증
                if resp.status != 200:
                    raise FetchFailedError(f"외부 사이트가 HTTP {resp.status}를 반환했습니다.")

                ctype = (resp.getheader("Content-Type") or "").split(";")[0].strip().lower()
                if not _type_ok(ctype, accept_prefixes):
                    raise FetchFailedError(f"허용되지 않는 Content-Type 입니다: {ctype or '(없음)'}")
                declared = resp.getheader("Content-Length")
                if declared and declared.isdigit() and int(declared) > max_bytes:
                    raise FetchFailedError("응답이 크기 상한을 초과합니다.")

                chunks: list[bytes] = []
                total = 0
                while True:
                    set_read_timeout()  # 매 반복 데드라인 검사 + 소켓 타임아웃을 남은 시간으로
                    chunk = resp.read1(READ_CHUNK)  # 한 번의 recv만 기다림(read(n)은 n바이트가 찰 때까지 막힘)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > max_bytes:
                        raise FetchFailedError("응답이 크기 상한을 초과합니다.")
                    chunks.append(chunk)
                return FetchResult(url=current, content_type=ctype, body=b"".join(chunks), redirects=visited)
            except (OSError, http.client.HTTPException, ValueError) as e:
                # ValueError: http.client가 ASCII로 못 바꾸는 요청 라인/제어문자(UnicodeEncodeError 포함)
                if timed_out.is_set() or isinstance(e, TimeoutError) or deadline.remaining() <= 0.05:
                    raise FetchFailedError("외부 사이트 응답이 너무 오래 걸립니다.") from e
                raise FetchFailedError("외부 사이트 요청에 실패했습니다.") from e
        finally:
            if watchdog is not None:
                watchdog.cancel()
            conn.close()

    logger.warning("fetch blocked (redirect limit)")
    raise FetchBlockedError("리다이렉트가 너무 많습니다.")
