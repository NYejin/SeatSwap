import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Link, NavLink, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { useAuthRedirect, type AuthRouteState } from "../hooks/useAuthRedirect";
import type { AuthUser } from "../types/auth";
import { FOCUS_RING } from "./authFormClasses";

// 공통 상단 헤더 (FR-01 로그인 상태 표시 + 주요 화면 진입점)
// TODO: 알림 아이콘 (교환 요청/채팅 알림) — 별도 작업

/** 데스크톱(가로 메뉴) 전환 기준. index.css @theme --breakpoint-md(768px)와 같은 값 (클래스의 md: 접두사) */
const DESKTOP_MEDIA_QUERY = "(min-width: 768px)";

// ---- Tailwind 클래스 조합 (모바일 우선: 기본은 햄버거 + 펼침 패널, md 이상에서 가로 배치) ----
const cls = {
  /** 하단 경계선은 보조색(장식 용도 — 글자에는 쓰지 않음) */
  header: "sticky top-0 z-[100] border-b border-secondary-200 bg-white pt-[env(safe-area-inset-top)]",
  bar: "relative mx-auto flex min-h-14 max-w-[1080px] items-center gap-2 pr-2 pl-4 md:gap-4 md:px-4",
  logo:
    "mr-auto inline-flex min-h-11 shrink-0 items-center text-xl/[normal] font-extrabold tracking-[-0.02em] " +
    "whitespace-nowrap text-primary-600 no-underline active:opacity-70 " +
    FOCUS_RING,
  menuToggle:
    "inline-flex size-11 shrink-0 cursor-pointer touch-manipulation items-center justify-center rounded-[10px] " +
    "border-0 bg-transparent p-0 md:hidden " +
    FOCUS_RING,
  /** 햄버거 아이콘: 가운데 막대 + before/after 막대 */
  menuIcon:
    "relative block h-0.5 w-5 rounded-[1px] bg-gray-900 " +
    "before:absolute before:-top-1.5 before:left-0 before:block before:h-0.5 before:w-5 before:rounded-[1px] before:bg-gray-900 " +
    "after:absolute after:top-1.5 after:left-0 after:block after:h-0.5 after:w-5 after:rounded-[1px] after:bg-gray-900",
  /** 패널 공통: md 이상에서는 항상 가로 배치 */
  panel:
    "md:static md:flex md:min-w-0 md:flex-row md:items-center md:gap-4 md:border-0 md:bg-transparent md:p-0 md:shadow-none",
  /** 모바일 닫힘: display:none으로 숨겨 탭 순서에서도 제외 */
  panelClosed: "hidden",
  /** 모바일 열림: 바 아래로 펼침 */
  panelOpen:
    "absolute inset-x-0 top-full flex flex-col gap-1 border-b border-gray-200 bg-white px-4 pt-2 pb-4 " +
    "shadow-[0_8px_24px_rgba(0,0,0,0.08)]",
  nav: "flex flex-col md:shrink-0 md:flex-row md:gap-1",
  navLink:
    "flex min-h-11 items-center rounded-[10px] px-3 text-base/[normal] font-semibold whitespace-nowrap no-underline " +
    "active:bg-gray-100 md:text-[0.9375rem]/[normal] " +
    FOCUS_RING,
  navLinkIdle: "text-gray-700",
  navLinkActive: "bg-primary-50 text-primary-600",
  auth:
    "flex min-w-0 flex-col gap-1 border-t border-gray-200 pt-2 whitespace-nowrap " +
    "md:flex-row md:items-center md:gap-2 md:border-t-0 md:pt-0",
  /** 초기화 중: 로그인 상태 영역과 같은 크기로 자리만 차지 */
  authPending: "invisible",
  /** 768px 기준 로고+메뉴+이메일+마이페이지+로그아웃 합계 약 710px — 이메일은 최대 168px(lg 248px)에서 말줄임 */
  userName:
    "block min-w-0 truncate px-3 py-2 text-sm/[normal] text-gray-500 md:max-w-[168px] md:px-1 md:py-0 lg:max-w-[248px]",
  userNamePending: "min-h-[1.25em] md:w-[168px] lg:w-[248px]",
  button:
    "inline-flex min-h-11 shrink-0 cursor-pointer touch-manipulation items-center justify-center rounded-[10px] " +
    "px-4 text-base/[normal] font-semibold whitespace-nowrap no-underline md:px-3.5 md:text-[0.9375rem]/[normal] " +
    FOCUS_RING,
  buttonOutline: "border border-gray-300 bg-white text-gray-900 active:bg-gray-100",
  buttonSolid: "border border-primary-600 bg-primary-600 text-white active:bg-primary-700",
} as const;

const outlineButton = `${cls.button} ${cls.buttonOutline}`;
const solidButton = `${cls.button} ${cls.buttonSolid}`;

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
  `${cls.navLink} ${isActive ? cls.navLinkActive : cls.navLinkIdle}`;

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
      <div className={`${cls.auth} ${cls.authPending}`} aria-hidden="true">
        <span className={`${cls.userName} ${cls.userNamePending}`} />
        <span className={navClass({ isActive: false })}>마이페이지</span>
        <span className={outlineButton}>로그아웃</span>
      </div>
    );
  } else if (user) {
    const name = displayName(user);
    authArea = (
      <div className={cls.auth}>
        <span className={cls.userName} title={name}>
          {name}
        </span>
        <NavLink to="/me" className={navClass} onClick={closeMenu}>
          마이페이지
        </NavLink>
        <button type="button" className={outlineButton} onClick={handleLogout}>
          로그아웃
        </button>
      </div>
    );
  } else {
    authArea = (
      <div className={cls.auth}>
        <Link to="/login" state={authLinkState} className={outlineButton} onClick={closeMenu}>
          로그인
        </Link>
        <Link to="/signup" state={authLinkState} className={solidButton} onClick={closeMenu}>
          회원가입
        </Link>
      </div>
    );
  }

  return (
    <header ref={headerRef} className={cls.header}>
      <div className={cls.bar}>
        <Link ref={logoRef} to="/" className={cls.logo} onClick={closeMenu}>
          SeatSwap
        </Link>

        <button
          ref={toggleRef}
          type="button"
          className={cls.menuToggle}
          aria-expanded={menuOpen}
          aria-controls={menuId}
          aria-label={menuOpen ? "메뉴 닫기" : "메뉴 열기"}
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span className={cls.menuIcon} aria-hidden="true" />
        </button>

        <div id={menuId} className={`${cls.panel} ${menuOpen ? cls.panelOpen : cls.panelClosed}`}>
          <nav className={cls.nav} aria-label="주요 메뉴">
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
