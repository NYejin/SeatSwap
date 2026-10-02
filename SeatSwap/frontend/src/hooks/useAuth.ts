import { createContext, useContext } from "react";
import type { AuthUser, LoginRequest } from "../types/auth";

export interface AuthContextValue {
  user: AuthUser | null;
  isAuthenticated: boolean;
  /** 저장된 토큰 확인(만료 시 refresh 시도)이 끝나기 전에는 true — 이 동안 리다이렉트 판단을 보류한다 */
  isInitializing: boolean;
  /** 실패 시 axios 에러를 그대로 throw 한다 (화면에서 parseApiError로 메시지 변환) */
  login: (payload: LoginRequest) => Promise<AuthUser>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

/** 로그인 상태·사용자 정보 전역 접근 훅. <AuthProvider> 하위에서만 사용 가능. */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 <AuthProvider> 안에서만 사용할 수 있습니다.");
  return ctx;
}
