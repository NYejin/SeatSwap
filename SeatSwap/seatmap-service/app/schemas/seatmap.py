from typing import List, Literal

from pydantic import BaseModel, ConfigDict, Field

AisleMode = Literal["continue", "skip"]


class _Camel(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class SeatCoordinate(BaseModel):
    """seatJson 한 칸. 좌표는 원본 이미지 픽셀 기준."""
    uid: str = Field(max_length=32)  # 안정 식별자(정정으로 row/col이 바뀌어도 불변)
    row: int
    col: int
    x: int
    y: int
    w: int
    h: int
    section: int = 1  # 구역(층) 번호, 위에서부터 1..


class ImageInfo(BaseModel):
    width: int
    height: int


class AisleInfo(_Camel):
    after_col: int = Field(alias="afterCol")
    gap_px: int = Field(alias="gapPx")
    missing_slots: int = Field(alias="missingSlots")


class RowInfo(_Camel):
    row: int
    row_source: Literal["ocr", "inferred", "sequence"] = Field(alias="rowSource")
    label_confidence: float = Field(alias="labelConfidence")
    seat_count: int = Field(alias="seatCount")
    aisles: List[AisleInfo]
    section: int = 1


class BBox(BaseModel):
    x: int
    y: int
    w: int
    h: int


class SectionInfo(_Camel):
    section: int
    row_count: int = Field(alias="rowCount")
    seat_count: int = Field(alias="seatCount")
    ocr_rows_read: int = Field(default=0, alias="ocrRowsRead")
    bbox: BBox


class RecognizeStats(_Camel):
    block_count: int = Field(alias="blockCount")
    row_count: int = Field(alias="rowCount")
    ocr_rows_read: int = Field(alias="ocrRowsRead")
    discarded_components: int = Field(alias="discardedComponents")
    split_seats: int = Field(default=0, alias="splitSeats")        # 붙은 덩어리에서 분리해 복원한 좌석 수
    section_count: int = Field(default=1, alias="sectionCount")


class WarningInfo(BaseModel):  # 내장 Warning을 가리지 않도록 이름 변경 (응답 JSON 필드는 그대로)
    code: str
    message: str


class RecognizeResponse(BaseModel):
    image: ImageInfo
    seats: List[SeatCoordinate]
    rows: List[RowInfo]
    sections: List[SectionInfo] = []
    stats: RecognizeStats
    warnings: List[WarningInfo]


class DiscoverRequest(_Camel):
    site: str
    product_id: str = Field(alias="productId")


class ImageCandidate(BaseModel):
    url: str
    source: str


class DiscoverResponse(_Camel):
    site: str
    product_id: str = Field(alias="productId")
    page_url: str = Field(alias="pageUrl")
    images: List[ImageCandidate]


class RecognizeUrlRequest(_Camel):
    site: str
    image_url: str = Field(alias="imageUrl")
    aisle_mode: AisleMode = Field(default="continue", alias="aisleMode")


class AnalyzeRequest(_Camel):
    site: str
    product_id: str = Field(alias="productId")
    aisle_mode: AisleMode = Field(default="continue", alias="aisleMode")


class AnalyzeResponse(RecognizeResponse):
    source_image_url: str = Field(alias="sourceImageUrl")
    model_config = ConfigDict(populate_by_name=True)


class ErrorResponse(BaseModel):
    code: str
    message: str
