export interface Review {
  id: number;
  matchId: number;
  reviewerId: number;
  targetId: number;
  rating: number;
  comment: string;
}
