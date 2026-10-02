import type { SeatCoordinate } from "../types/seatmap";

interface Props {
  seat: SeatCoordinate;
  onSubmit: (correctedLabel: string) => void;
}

/** 좌석 선택 시 항상 함께 노출되는 오류 신고 버튼 (FR-07) */
export default function SeatMapErrorReportButton({ seat, onSubmit }: Props) {
  // TODO: 모달/인라인 폼으로 정정 라벨 입력받아 onSubmit 호출
  return null;
}
