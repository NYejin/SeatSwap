import { createContext, useContext } from "react";
import type { AuthUser, LoginRequest } from "../types/auth";
import type { User } from "../types/user";

/** 내 정보(/api/users/me) 조회 상태. 로그아웃 상태면 idle */
export type ProfileStatus = "idle" | "loading" | "success" | "error";

export interface AuthContextValue {
  /** 토큰에서 복원한 식별 정보 (id, email) — 인증 판단 기준 */
  user: AuthUser | null;
  isAuthenticated: boolean;
  /** 저장된 토큰 확인(만료 시 refresh 시도)이 끝나기 전에는 true — 이 동안 리다이렉트 판단을 보류한다 */
  isInitializing: boolean;
  /** 표시용 프로필 (nickname, trustScore 등). 현재 세션 user와 id가 같은 것만 노출된다 */
  profile: User | null;
  profileStatus: ProfileStatus;
  /** 프로필 조회 실패 시 화면용 메시지 */
  profileError: string | null;
  /** 프로필 다시 불러오기 (오류 재시도용) */
  reloadProfile: () => void;
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

/** 화면 표시 이름: 프로필 닉네임 우선, 로딩 중·실패 시 email */
export function getDisplayName(user: AuthUser, profile: User | null): string {
  return profile?.nickname || user.email;
}
