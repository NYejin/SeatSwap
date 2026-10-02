import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { authApi, refreshSession, type RefreshResult } from "../api/auth";
import {
  ACCESS_TOKEN_KEY,
  REFRESH_TOKEN_KEY,
  getTokenExpiry,
  getUserFromAccessToken,
  isTokenExpired,
  tokenStorage,
} from "../api/tokenStorage";
import { AuthContext, type AuthContextValue } from "../hooks/useAuth";
import type { AuthUser, LoginRequest } from "../types/auth";

interface Session {
  user: AuthUser;
  accessToken: string;
}

/** access token 만료 이 시간 전에 선제 재발급 (백엔드 access 유효기간 1시간 기준) */
const REFRESH_LEAD_MS = 60_000;
/** setTimeout 최대 지연(약 24.8일) 초과 시 즉시 실행되는 브라우저 동작 방지 */
const MAX_TIMEOUT_MS = 2_147_483_647;

/** 저장된 access token이 유효하면 세션을, 아니면 null (토큰은 건드리지 않음) */
function readValidSession(): Session | null {
  const accessToken = tokenStorage.getAccessToken();
  if (!accessToken || isTokenExpired(accessToken)) return null;
  const user = getUserFromAccessToken(accessToken);
  return user ? { user, accessToken } : null;
}

function hasUsableRefreshToken(): boolean {
  const refresh = tokenStorage.getRefreshToken();
  return !!refresh && !isTokenExpired(refresh);
}

/**
 * refreshSession 결과를 세션 상태로 변환.
 * - ok: 새 토큰으로 세션 갱신
 * - invalid: 토큰은 refreshSession에서 이미 삭제됨 → 비로그인
 * - unavailable/superseded: 토큰 보존, 저장소의 access token이 아직 유효한지로 판단
 */
function sessionFromRefreshResult(result: RefreshResult): Session | null {
  if (result.status === "ok") {
    const user = getUserFromAccessToken(result.tokens.accessToken);
    return user ? { user, accessToken: result.tokens.accessToken } : null;
  }
  if (result.status === "invalid") return null;
  return readValidSession();
}

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(readValidSession);
  // access token은 만료됐지만 refresh token이 남아 있으면 재발급을 시도하는 동안 초기화 상태 유지
  const [isInitializing, setIsInitializing] = useState<boolean>(
    () => readValidSession() === null && hasUsableRefreshToken()
  );

  // 초기화: 만료된 access + 유효한 refresh → 재발급 시도.
  // refreshSession이 single-flight라 StrictMode 이중 실행에서도 요청은 1회이고,
  // 토큰 삭제 여부는 refreshSession이 응답 상태(400)로만 결정하므로 cancelled와 무관하다.
  useEffect(() => {
    if (!isInitializing) return;
    let cancelled = false;
    refreshSession().then((result) => {
      if (cancelled) return;
      setSession(sessionFromRefreshResult(result));
      setIsInitializing(false);
    });
    return () => {
      cancelled = true;
    };
    // 최초 마운트 시 1회만 수행
  }, []);

  // access token 만료 타이머: 만료 REFRESH_LEAD_MS 전에 선제 재발급.
  // 토큰이 바뀌거나(로그인/재발급/다른 탭) 로그아웃·언마운트되면 cleanup으로 타이머 정리 후 재설정.
  const accessToken = session?.accessToken ?? null;
  useEffect(() => {
    if (!accessToken) return;
    const expiry = getTokenExpiry(accessToken);
    if (expiry === null) return;

    let cancelled = false;
    let expiryTimer: number | undefined;

    const refreshTimer = window.setTimeout(async () => {
      const result = await refreshSession();
      if (cancelled) return;
      const next = sessionFromRefreshResult(result);
      setSession(next);

      // 일시 장애로 재발급 실패 + access가 아직 유효: 토큰은 보존하되 실제 만료 시점에 비로그인 처리
      if (result.status === "unavailable" && next?.accessToken === accessToken) {
        expiryTimer = window.setTimeout(() => {
          if (!cancelled) setSession(null);
        }, Math.min(Math.max(expiry - Date.now(), 0), MAX_TIMEOUT_MS));
      }
    }, Math.min(Math.max(expiry - REFRESH_LEAD_MS - Date.now(), 0), MAX_TIMEOUT_MS));

    return () => {
      cancelled = true;
      window.clearTimeout(refreshTimer);
      if (expiryTimer !== undefined) window.clearTimeout(expiryTimer);
    };
  }, [accessToken]);

  // 다른 탭에서 로그인/로그아웃/재발급하면 상태 동기화 (accessToken이 바뀌면 위 타이머도 재설정됨)
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key === null || e.key === ACCESS_TOKEN_KEY || e.key === REFRESH_TOKEN_KEY) {
        setSession(readValidSession());
      }
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const login = useCallback(async (payload: LoginRequest): Promise<AuthUser> => {
    const tokens = await authApi.login(payload);
    const user = getUserFromAccessToken(tokens.accessToken);
    if (!user) throw new Error("서버에서 받은 토큰 형식이 올바르지 않습니다.");
    tokenStorage.save(tokens);
    setSession({ user, accessToken: tokens.accessToken });
    return user;
  }, []);

  const logout = useCallback(() => {
    // 백엔드는 stateless refresh token이라 로그아웃 API 없음 — 클라이언트 토큰 삭제로 처리
    tokenStorage.clear();
    setSession(null);
  }, []);

  const user = session?.user ?? null;
  const value = useMemo<AuthContextValue>(
    () => ({ user, isAuthenticated: user !== null, isInitializing, login, logout }),
    [user, isInitializing, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
