from app.adapters.base import PerformanceInfo, SeatMapImageCandidate, SiteAdapter
from app.adapters.melon import MelonAdapter

# 지원 사이트 레지스트리. 다른 사이트(interpark/yes24/ticketlink)는 어댑터를 추가하면 등록된다.
_ADAPTERS: dict[str, SiteAdapter] = {a.site: a for a in (MelonAdapter(),)}


def get_adapter(site: str) -> SiteAdapter | None:
    return _ADAPTERS.get((site or "").lower())


def supported_sites() -> list[str]:
    return sorted(_ADAPTERS)


__all__ = ["get_adapter", "supported_sites", "SiteAdapter", "SeatMapImageCandidate", "PerformanceInfo"]
