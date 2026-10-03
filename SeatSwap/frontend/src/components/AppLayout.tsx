import { Outlet } from "react-router-dom";
import Header from "./Header";

/**
 * 모든 라우트 공통 레이아웃: 상단 Header + 페이지 영역(<main>, 페이지 쪽에서는 <main>을 다시 쓰지 않는다).
 * 페이지 영역은 헤더를 뺀 남은 높이를 채우는 flex 컨테이너다. 페이지는 100dvh 대신 flex: 1로
 * 높이를 채워야 헤더 높이만큼 넘치지 않는다 (authFormClasses.ts page 참고).
 */
export default function AppLayout() {
  return (
    <div className="flex min-h-[100vh] flex-col supports-[min-height:100dvh]:min-h-dvh">
      <Header />
      <main className="flex flex-[1_0_auto] flex-col">
        <Outlet />
      </main>
    </div>
  );
}
