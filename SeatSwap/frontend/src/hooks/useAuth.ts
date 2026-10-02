// TODO: 로그인 상태, 사용자 정보 전역 접근 훅 (Context 또는 상태관리 연동)
export function useAuth() {
  return { user: null, isAuthenticated: false };
}
