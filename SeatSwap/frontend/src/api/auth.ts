import axios from "axios";
import { apiClient, authClient } from "./client";
import { getUserFromAccessToken, isTokenExpired, tokenStorage } from "./tokenStorage";
import type { User } from "../types/user";
import type { LoginRequest, RefreshRequest, SignupRequest, TokenResponse } from "../types/auth";

// 백엔드 AuthController (@RequestMapping("/api/auth")). baseURL에 /api가 포함되어 있다.
// 내 정보 조회(/me) API는 백엔드에 아직 없음.
export const authApi = {
  async login(payload: LoginRequest): Promise<TokenResponse> {
    const { data } = await apiClient.post<TokenResponse>("/auth/login", payload);
    return data;
  },

  async signup(payload: SignupRequest): Promise<User> {
    const { data } = await apiClient.post<User>("/auth/signup", payload);
    return data;
  },

  /** 인터셉터 없는 authClient 사용 (client.ts 참고) */
  async refreshToken(refreshToken: string): Promise<TokenResponse> {
    const body: RefreshRequest = { refreshToken };
    const { data } = await authClient.post<TokenResponse>("/auth/refresh", body);
    return data;
  },
};

/**
 * - ok: 재발급 성공, 새 토큰 저장 완료
 * - invalid: refresh token 무효/만료(백엔드 400 또는 로컬 만료) — 토큰 삭제 완료
 * - unavailable: 네트워크 오류/5xx 등 일시 장애 — 토큰 보존
 * - superseded: 요청 중 다른 곳(로그아웃/재로그인/다른 탭)에서 토큰이 바뀜 — 결과 버리고 저장소 기준으로 동기화
 */
export type RefreshResult =
  | { status: "ok"; tokens: TokenResponse }
  | { status: "invalid" | "unavailable" | "superseded" };

let inflight: Promise<RefreshResult> | null = null;

/**
 * 저장된 refresh token으로 세션을 재발급한다. 모듈 단위 single-flight —
 * 동시에 여러 곳(초기화, 만료 타이머, 추후 401 인터셉터)에서 호출해도 요청은 1회만 나간다.
 */
export function refreshSession(): Promise<RefreshResult> {
  if (inflight) return inflight;

  inflight = (async (): Promise<RefreshResult> => {
    const refreshToken = tokenStorage.getRefreshToken();
    if (!refreshToken || isTokenExpired(refreshToken)) {
      tokenStorage.clear();
      return { status: "invalid" };
    }

    try {
      const tokens = await authApi.refreshToken(refreshToken);
      if (tokenStorage.getRefreshToken() !== refreshToken) return { status: "superseded" };
      if (!getUserFromAccessToken(tokens.accessToken)) return { status: "unavailable" };
      tokenStorage.save(tokens);
      return { status: "ok", tokens };
    } catch (err) {
      if (tokenStorage.getRefreshToken() !== refreshToken) return { status: "superseded" };
      // 백엔드는 무효/만료 refresh token을 SeatSwapException(400)으로 응답한다
      if (axios.isAxiosError(err) && err.response?.status === 400) {
        tokenStorage.clear();
        return { status: "invalid" };
      }
      return { status: "unavailable" };
    }
  })().finally(() => {
    inflight = null;
  });

  return inflight;
}
