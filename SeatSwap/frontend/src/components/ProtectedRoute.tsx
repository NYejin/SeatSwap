import { Navigate, Outlet } from "react-router-dom";

export default function ProtectedRoute() {
  const isAuthenticated = !!localStorage.getItem("accessToken");
  // TODO: 토큰 만료 체크까지 포함
  return isAuthenticated ? <Outlet /> : <Navigate to="/login" replace />;
}
