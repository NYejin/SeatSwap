import axios from "axios";

// JWT 인터셉터는 여기 한 곳에서만 처리한다 (react-conventions 스킬 참고)
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api",
});

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem("accessToken");
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// TODO: 401 응답 시 refresh token으로 재발급 후 재시도하는 response 인터셉터 추가
