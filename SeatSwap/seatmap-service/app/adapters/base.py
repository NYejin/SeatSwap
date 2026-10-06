"""
사이트 어댑터 인터페이스. 백엔드는 링크 원문이 아니라 {site, productId}만 넘기고,
어댑터가 요청 URL을 직접 조립한다 (서버가 임의 주소를 요청하지 않게).

site 코드값은 백엔드 TicketingSite.key()와 동일: interpark / melon / yes24 / ticketlink.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class SeatMapImageCandidate:
    url: str
    source: str  # 어디서 찾았는지 (예: "venue_tab")


@dataclass(frozen=True)
class PerformanceInfo:
    """3단계(공연정보 자동 입력)용 자리표시. 지금은 어떤 어댑터도 채우지 않는다."""
    title: str | None = None
    venue_name: str | None = None
    sessions: tuple[str, ...] = ()  # ISO-8601 KST


class SiteAdapter(ABC):
    site: str
    page_hosts: frozenset[str]   # 상품 페이지 요청이 허용되는 호스트 (정확 일치)
    image_hosts: frozenset[str]  # 좌석맵 이미지 요청이 허용되는 호스트 (정확 일치)

    @abstractmethod
    def is_valid_product_id(self, product_id: str) -> bool: ...

    @abstractmethod
    def build_product_url(self, product_id: str) -> str:
        """상품 페이지 URL을 새로 조립한다. product_id는 is_valid_product_id 통과 후에만 사용."""

    @abstractmethod
    def parse_seatmap_images(self, html: str) -> list[SeatMapImageCandidate]:
        """상품 페이지 HTML에서 좌석맵 이미지 후보를 찾는다. 없으면 빈 리스트."""

    # --- 확장 자리 (3단계: 공연정보 읽기) ---
    def parse_performance_info(self, html: str) -> PerformanceInfo | None:
        return None

    def is_allowed_image_url(self, url: str) -> bool:
        from urllib.parse import urlsplit
        try:
            p = urlsplit(url)
        except ValueError:
            return False
        return p.scheme == "https" and (p.hostname or "").lower() in self.image_hosts
