import pytest

from app.adapters import get_adapter, supported_sites
from app.adapters.melon import MelonAdapter

A = MelonAdapter()


def test_registry():
    assert supported_sites() == ["melon"] and get_adapter("MELON") is not None and get_adapter("yes24") is None


def test_build_url_only_from_numeric_id():
    assert A.build_product_url("213480") == "https://ticket.melon.com/performance/index.htm?prodId=213480"
    for bad in ["", "abc", "1&x=2", "../x", "1 2", "1" * 21, "١٢٣"]:
        assert not A.is_valid_product_id(bad)
        with pytest.raises(ValueError):
            A.build_product_url(bad)


def test_hosts_are_exact():
    assert A.page_hosts == {"ticket.melon.com"} and A.image_hosts == {"cdnticket.melon.co.kr"}
    assert A.is_allowed_image_url("https://cdnticket.melon.co.kr/a.png")
    for bad in ["http://cdnticket.melon.co.kr/a.png", "https://cdnticket.melon.co.kr.evil.com/a.png",
                "https://evil.com/a.png", "https://ticket.melon.com/a.png"]:
        assert not A.is_allowed_image_url(bad)


def test_parse_finds_place_images_only_on_allowed_hosts():
    html = """
    <div><img class="placeImg" data-src="//cdnticket.melon.co.kr/x/seat.png"/>
    <img class="placeImg other" src="https://cdnticket.melon.co.kr/x/seat2.png">
    <img class="placeImg" data-src="https://evil.com/seat.png">
    <img class="placeImg" data-src="//cdnticket.melon.co.kr/x/seat.png">
    <img src="https://cdnticket.melon.co.kr/poster.jpg"></div>"""
    urls = [c.url for c in A.parse_seatmap_images(html)]
    assert urls == ["https://cdnticket.melon.co.kr/x/seat.png", "https://cdnticket.melon.co.kr/x/seat2.png"]


def test_parse_returns_empty_when_page_has_no_seatmap():
    html = '<img src="https://cdnticket.melon.co.kr/poster.jpg"><ul class="list_seat"><li>VIP</li></ul>'
    assert A.parse_seatmap_images(html) == []


def test_performance_info_is_placeholder():
    assert A.parse_performance_info("<html></html>") is None
