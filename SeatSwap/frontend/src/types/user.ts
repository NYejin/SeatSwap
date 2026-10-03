export interface User {
  id: number;
  email: string;
  nickname: string;
  /** 신뢰도 점수. 백엔드 Double이라 null일 수 있음 (신규 회원은 기본값 0.0 — 기획 결정, 화면도 0.0 그대로 표시) */
  trustScore: number | null;
}
