import { BrowserRouter, Routes, Route } from "react-router-dom";
import AppLayout from "./components/AppLayout";
import ProtectedRoute from "./components/ProtectedRoute";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import HomePage from "./pages/HomePage";
import PerformanceDetailPage from "./pages/PerformanceDetailPage";
import PerformanceRegisterPage from "./pages/PerformanceRegisterPage";
import TicketRegisterPage from "./pages/TicketRegisterPage";
import SeatMapSelectPage from "./pages/SeatMapSelectPage";
import ExchangeListPage from "./pages/ExchangeListPage";
import ExchangeDetailPage from "./pages/ExchangeDetailPage";
import ChatPage from "./pages/ChatPage";
import MyPage from "./pages/MyPage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 공통 레이아웃: 모든 화면 상단에 Header. 홈(/)은 공개 — 비로그인이면 안내, 로그인이면 공연 목록 */}
        <Route element={<AppLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />

          <Route element={<ProtectedRoute />}>
            {/* 공연 API는 모두 로그인 필요 → 상세도 보호 라우트 (비로그인 진입 시 로그인 후 복귀) */}
            <Route path="/performances/new" element={<PerformanceRegisterPage />} />
            <Route path="/performances/:id" element={<PerformanceDetailPage />} />
            <Route path="/tickets/new" element={<TicketRegisterPage />} />
            <Route path="/seatmaps/:id/select" element={<SeatMapSelectPage />} />
            <Route path="/exchange" element={<ExchangeListPage />} />
            <Route path="/exchange/:id" element={<ExchangeDetailPage />} />
            <Route path="/chat/:matchId" element={<ChatPage />} />
            <Route path="/me" element={<MyPage />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
