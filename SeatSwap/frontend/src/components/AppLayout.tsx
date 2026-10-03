import { Outlet } from "react-router-dom";
import Header from "./Header";
import styles from "./AppLayout.module.css";

/**
 * 모든 라우트 공통 레이아웃: 상단 Header + 페이지 영역(<main>, 페이지 쪽에서는 <main>을 다시 쓰지 않는다).
 * 페이지 영역은 헤더를 뺀 남은 높이를 채우는 flex 컨테이너다. 페이지는 100dvh 대신 flex: 1로
 * 높이를 채워야 헤더 높이만큼 넘치지 않는다 (AuthForm.module.css .page 참고).
 */
export default function AppLayout() {
  return (
    <div className={styles.shell}>
      <Header />
      <main className={styles.content}>
        <Outlet />
      </main>
    </div>
  );
}
