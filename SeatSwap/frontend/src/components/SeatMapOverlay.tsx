import type { SeatCoordinate } from "../types/seatmap";

interface Props {
  imageUrl: string;
  seats: SeatCoordinate[];
  selectedSeatId?: string;
  onSelectSeat: (seat: SeatCoordinate) => void;
}

/**
 * 원본 좌석맵 이미지 위에 SVG <rect> 오버레이로 좌표를 겹쳐 그린다.
 * 판매 상태에 따른 색상 구분은 하지 않는다 (react-conventions 스킬 참고) —
 * 선택됨/선택안됨만 구분한다. 좌표는 원본 이미지 픽셀 기준이므로
 * 실제 렌더링 크기에 맞춰 스케일링이 필요하다 (TODO).
 */
export default function SeatMapOverlay({ imageUrl, seats, selectedSeatId, onSelectSeat }: Props) {
  // TODO: viewBox 스케일링, <image> + <rect> 오버레이 렌더링
  return null;
}
