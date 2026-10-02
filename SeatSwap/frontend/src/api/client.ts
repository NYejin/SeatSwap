import axios from "axios";
import { tokenStorage } from "./tokenStorage";

const baseURL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";

// JWT 인터셉터는 여기 한 곳에서만 처리한다 (react-conventions 스킬 참고)
export const apiClient = axios.create({ baseURL });

/**
 * 인터셉터를 거치지 않는 인증 전용 클라이언트 (토큰 재발급 등).
 * apiClient에 401 재시도 인터셉터가 붙어도 refresh 요청이 재귀/무한 재시도되지 않도록 분리한다.
 */
export const authClient = axios.create({ baseURL });

apiClient.interceptors.request.use((config) => {
  const token = tokenStorage.getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// TODO(후속): 401 응답 시 토큰 재발급 후 원 요청 1회 재시도하는 response 인터셉터 추가.
//   전제/조건:
//   - 백엔드가 인증 실패를 401로 통일한 뒤 진행 (현재 AuthenticationEntryPoint 미설정 → Spring 기본 403)
//   - 401만 대상으로 한다 (403 권한 부족, 400 등은 재시도하지 않음)
//   - 재발급은 api/auth.ts의 refreshSession() single-flight를 재사용 (동시 401 다발 시 refresh 1회)
