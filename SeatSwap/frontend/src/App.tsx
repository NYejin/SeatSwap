import { BrowserRouter, Routes, Route } from "react-router-dom";
import ProtectedRoute from "./components/ProtectedRoute";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import HomePage from "./pages/HomePage";
import PerformanceDetailPage from "./pages/PerformanceDetailPage";
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
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/performances/:id" element={<PerformanceDetailPage />} />

        <Route element={<ProtectedRoute />}>
          <Route path="/tickets/new" element={<TicketRegisterPage />} />
          <Route path="/seatmaps/:id/select" element={<SeatMapSelectPage />} />
          <Route path="/exchange" element={<ExchangeListPage />} />
          <Route path="/exchange/:id" element={<ExchangeDetailPage />} />
          <Route path="/chat/:matchId" element={<ChatPage />} />
          <Route path="/me" element={<MyPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
