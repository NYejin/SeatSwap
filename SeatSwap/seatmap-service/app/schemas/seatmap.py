from pydantic import BaseModel
from typing import List, Optional


class SeatCoordinate(BaseModel):
    row: int
    col: int
    x: int
    y: int
    w: int
    h: int


class RecognizeRequest(BaseModel):
    image_url: str


class RecognizeResponse(BaseModel):
    seats: List[SeatCoordinate]


class CorrectionRequest(BaseModel):
    seatmap_id: int
    original_label: str
    corrected_label: str
    reporter_id: int
