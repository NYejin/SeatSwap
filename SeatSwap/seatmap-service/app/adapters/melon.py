"""
멜론티켓 어댑터.

Phase 0 실측(2026-10-06, prodId=213480): 공개 상품 페이지에는 개별 좌석맵 이미지가 없다.
- 좌석 배치(개별좌석도)는 회차 선택 -> 예매 팝업 -> 보안문자 흐름 뒤에서만 나온다 (로그인/캡차/내부 API 필요
  -> 이 서비스는 우회하지 않는다).
- 페이지의 이미지는 포스터/할인표/공지/앱 홍보뿐. 공연장 탭(.placeImg)은 이 상품에서는 비어 있었다.
따라서 이 어댑터는 "페이지에 공개된 이미지 중 공연장 탭(.placeImg) 후보"만 찾고, 없으면 빈 리스트를 반환한다.
(.placeImg 마크업은 다른 상품에서 확인하지 못해 추정이다 -> 확인되면 fixture와 함께 보강.)
"""
from __future__ import annotations

import re
from html.parser import HTMLParser
from urllib.parse import urljoin

from app.adapters.base import SeatMapImageCandidate, SiteAdapter


class _PlaceImgParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag != "img":
            return
        a = dict(attrs)
        classes = (a.get("class") or "").split()
        if "placeImg" not in classes:
            return
        src = a.get("data-src") or a.get("src")
        if src:
            self.urls.append(src.strip())


class MelonAdapter(SiteAdapter):
    site = "melon"
    page_hosts = frozenset({"ticket.melon.com"})
    image_hosts = frozenset({"cdnticket.melon.co.kr"})
    _BASE = "https://ticket.melon.com/performance/index.htm"
    _ID = re.compile(r"^[0-9]{1,20}$")

    def is_valid_product_id(self, product_id: str) -> bool:
        return bool(self._ID.fullmatch(product_id or ""))

    def build_product_url(self, product_id: str) -> str:
        if not self.is_valid_product_id(product_id):
            raise ValueError("invalid productId")
        return f"{self._BASE}?prodId={product_id}"

    def parse_seatmap_images(self, html: str) -> list[SeatMapImageCandidate]:
        parser = _PlaceImgParser()
        parser.feed(html)
        out: list[SeatMapImageCandidate] = []
        seen: set[str] = set()
        for raw in parser.urls:
            url = urljoin(self._BASE, raw)  # '//cdn...' 형태 보정
            if self.is_allowed_image_url(url) and url not in seen:
                seen.add(url)
                out.append(SeatMapImageCandidate(url=url, source="venue_tab"))
        return out
