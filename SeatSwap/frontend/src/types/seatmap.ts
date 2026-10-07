// 좌석표 (UC-03/04). 백엔드 SeatMapResponse 계약(2026-10-07)과 1:1 대응.
// 좌표는 원본 이미지 픽셀 기준이다. 원본 이미지는 서버에 저장하지 않으므로 이미지 URL 필드는 없다.

export interface SeatCoordinate {
  /** 좌석 안정 식별자 (React key·선택 상태에 사용) */
  uid: string;
  /** 열 (앞에서부터 1열, 뒤로 갈수록 커짐) */
  row: number;
  /** 번 (왼쪽에서 오른쪽으로 1번, 2번, ...) */
  col: number;
  /** 구역(층) 번호, 위에서부터 1.. 서버가 안 주면 1로 간주 */
  section?: number;
  x: number;
  y: number;
  w: number;
  h: number;
}

/** DRAFT: 정식 등록 전 임시 좌석표(확인용). OFFICIAL: 관리자가 정식 등록한 좌석표 */
export type SeatMapStatus = "DRAFT" | "OFFICIAL";

/** 열 번호 통로 처리: continue = 통로를 건너도 번호를 이어서, skip = 통로 자리를 결번으로 */
export type AisleMode = "continue" | "skip";

/** GET /api/seatmaps/{id}, POST /api/venues/{venueId}/seatmaps(201) */
export interface SeatMap {
  id: number;
  venueId: number;
  venueName: string;
  zoneName: string | null;
  status: SeatMapStatus;
  version: number;
  imageWidth: number;
  imageHeight: number;
  seats: SeatCoordinate[];
  /** 삭제 가능 여부(DRAFT이고 작성자/관리자, 또는 서버의 임시 플래그 on). 없으면 false로 간주 */
  canDelete?: boolean;
  createdAt: string;
  updatedAt: string;
}

/** GET /api/venues/{venueId}/seatmaps 항목 */
export interface SeatMapSummary {
  id: number;
  zoneName: string | null;
  status: SeatMapStatus;
  version: number;
  /** 서버가 내려주지 않을 수 있다 */
  seatCount?: number;
  createdAt: string;
}

export interface SeatMapUploadParams {
  file: File;
  zoneName?: string;
  aisleMode?: AisleMode;
}

// ---- 번호 수정(PATCH /api/seatmaps/{id}/seats)·정정 신고(POST /api/seatmaps/{id}/corrections) ----

/** 수정 대상 필드: ROW_LABEL = 열 번호(row), COL_LABEL = 번 번호(col) */
export type SeatChangeField = "ROW_LABEL" | "COL_LABEL";

export interface SeatChange {
  uid: string;
  field: SeatChangeField;
  /** 1~9999 */
  value: number;
}

export interface UpdateSeatsRequest {
  /** 화면이 본 version — 다른 사람이 먼저 수정했으면 409 VERSION_CONFLICT */
  expectedVersion: number;
  /** 수정 사유(필수, 500자 이하) */
  reason: string;
  /** 최대 2000건 */
  changes: SeatChange[];
}

export interface SeatCorrectionRequest {
  uid: string;
  field: SeatChangeField;
  value: number;
  note?: string;
}

/** PENDING: 접수됨(같은 정정이 2건 이상 모이면 반영), APPLIED: 바로 반영됨 */
export interface SeatCorrectionResponse {
  status: "PENDING" | "APPLIED";
  correctionId: number;
}
