import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Link, NavLink, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { useAuthRedirect, type AuthRouteState } from "../hooks/useAuthRedirect";
import type { AuthUser } from "../types/auth";
import styles from "./Header.module.css";

// 공통 상단 헤더 (FR-01 로그인 상태 표시 + 주요 화면 진입점)
// TODO: 알림 아이콘 (교환 요청/채팅 알림) — 별도 작업

/** 데스크톱(가로 메뉴) 전환 기준. Header.module.css의 @media (min-width: 768px)와 반드시 같은 값 */
const DESKTOP_MEDIA_QUERY = "(min-width: 768px)";

const AUTH_PATHS = ["/login", "/signup"];

const MENU = [
  { to: "/exchange", label: "교환 목록" },
  { to: "/tickets/new", label: "티켓 등록" },
] as const;

/** 헤더에 표시할 사용자 이름. /me API가 생기면 nickname으로 교체 */
function displayName(user: AuthUser): string {
  return user.email;
}

const navClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? `${styles.navLink} ${styles.navLinkActive}` : styles.navLink;

export default function Header() {
  const { user, isInitializing, logout } = useAuth();
  const { forwardState } = useAuthRedirect();
  const location = useLocation();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuId = useId();
  const headerRef = useRef<HTMLElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const logoRef = useRef<HTMLAnchorElement>(null);

  // 경로가 바뀌면 모바일 메뉴 닫기
  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  // 메뉴가 열려 있을 때만 Esc / 바깥 클릭 / 데스크톱 폭 전환 감지
  useEffect(() => {
    if (!menuOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setMenuOpen(false);
      // 포커스가 헤더 안에 있을 때만 토글로 돌려준다 (다른 영역의 포커스를 빼앗지 않음)
      if (headerRef.current?.contains(document.activeElement)) toggleRef.current?.focus();
    };
    const onPointerDown = (e: PointerEvent) => {
      if (headerRef.current && !headerRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    const desktop = window.matchMedia(DESKTOP_MEDIA_QUERY);
    const onMediaChange = () => setMenuOpen(false);

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    desktop.addEventListener("change", onMediaChange);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
      desktop.removeEventListener("change", onMediaChange);
    };
  }, [menuOpen]);

  // 같은 경로 링크를 눌러 pathname이 안 바뀌는 경우에도 닫히도록 링크 클릭 시 직접 닫는다
  const closeMenu = () => setMenuOpen(false);

  const handleLogout = () => {
    // 보호 화면을 먼저 벗어난 뒤 세션을 지운다. 순서가 반대면 ProtectedRoute가 현재 화면을 from으로
    // /login 리다이렉트를 걸 수 있어 "/" 이동과 경합한다
    navigate("/", { replace: true });
    logout();
    closeMenu();
    // 로그아웃 버튼이 사라지며 포커스가 body로 떨어지지 않게 로고로 옮긴다
    logoRef.current?.focus();
  };

  // 인증 화면에서는 기존 from을 그대로 넘기고, 그 밖에서는 현재 위치의 필요한 필드만 넘긴다
  const authLinkState: AuthRouteState | undefined = AUTH_PATHS.includes(location.pathname)
    ? forwardState
    : { from: { pathname: location.pathname, search: location.search, hash: location.hash } };

  let authArea: ReactNode;
  if (isInitializing) {
    // 토큰 확인 중: 로그인 상태 영역과 같은 구조를 보이지 않게 렌더해 자리를 유지 (깜빡임/레이아웃 이동 방지).
    // 초기화는 refresh token이 있을 때만 일어나므로 대개 로그인 상태로 끝난다
    authArea = (
      <div className={`${styles.auth} ${styles.authPending}`} aria-hidden="true">
        <span className={`${styles.userName} ${styles.userNamePending}`} />
        <span className={styles.navLink}>마이페이지</span>
        <span className={styles.logoutButton}>로그아웃</span>
      </div>
    );
  } else if (user) {
    const name = displayName(user);
    authArea = (
      <div className={styles.auth}>
        <span className={styles.userName} title={name}>
          {name}
        </span>
        <NavLink to="/me" className={navClass} onClick={closeMenu}>
          마이페이지
        </NavLink>
        <button type="button" className={styles.logoutButton} onClick={handleLogout}>
          로그아웃
        </button>
      </div>
    );
  } else {
    authArea = (
      <div className={styles.auth}>
        <Link to="/login" state={authLinkState} className={styles.loginLink} onClick={closeMenu}>
          로그인
        </Link>
        <Link to="/signup" state={authLinkState} className={styles.signupLink} onClick={closeMenu}>
          회원가입
        </Link>
      </div>
    );
  }

  return (
    <header ref={headerRef} className={styles.header}>
      <div className={styles.bar}>
        <Link ref={logoRef} to="/" className={styles.logo} onClick={closeMenu}>
          SeatSwap
        </Link>

        <button
          ref={toggleRef}
          type="button"
          className={styles.menuToggle}
          aria-expanded={menuOpen}
          aria-controls={menuId}
          aria-label={menuOpen ? "메뉴 닫기" : "메뉴 열기"}
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span className={styles.menuIcon} aria-hidden="true" />
        </button>

        <div id={menuId} className={menuOpen ? `${styles.panel} ${styles.panelOpen}` : styles.panel}>
          <nav className={styles.nav} aria-label="주요 메뉴">
            {MENU.map((item) => (
              <NavLink key={item.to} to={item.to} className={navClass} onClick={closeMenu}>
                {item.label}
              </NavLink>
            ))}
          </nav>
          {authArea}
        </div>
      </div>
    </header>
  );
}
