export interface User {
  id: number;
  email: string;
  nickname: string;
  /** 신뢰도 점수. 백엔드 Double이라 null일 수 있음 (신규 회원은 기본값 0.0 — 기획 결정, 화면도 0.0 그대로 표시) */
  trustScore: number | null;
  /** 권한. UI 분기용일 뿐 최종 권한 판정은 서버가 한다. 서버가 안 주면 일반 사용자로 간주 */
  role?: "USER" | "ADMIN";
}
