// backend SeatMapLayout.seatJson 구조와 일치 (seatmap-recognition-pattern 스킬 참고)
export interface SeatCoordinate {
  row: number;
  col: number;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface SeatMapLayout {
  id: number;
  venueId: number;
  zoneName: string;
  imageUrl: string;
  seats: SeatCoordinate[];
}
