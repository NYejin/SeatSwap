---
name: spring-boot-conventions
description: SeatSwap/backend(Spring Boot) 코드(엔티티, 컨트롤러, 서비스, 시큐리티) 작성 시 반드시 참고할 패키지 구조와 코딩 컨벤션.
---

# Spring Boot 컨벤션

## 위치
`SeatSwap/backend/src/main/java/com/seatswap/`

## 패키지 구조
```
com.seatswap
├── domain       # @Entity 클래스
├── controller   # @RestController — DTO만 주고받는다
├── service      # @Service — 비즈니스 로직, 트랜잭션 경계
├── repository   # JpaRepository 인터페이스
├── config       # SecurityConfig, WebSocketConfig
├── security     # JwtTokenProvider, JwtAuthenticationFilter, AuthUserPrincipal, EntryPoint/AccessDeniedHandler
├── exception    # 커스텀 예외 + GlobalExceptionHandler
└── dto          # Request/Response 객체
```

## 규칙
- 컨트롤러는 엔티티를 절대 직접 반환하지 않는다 (DTO 변환 필수).
- 인증은 JWT — `SecurityFilterChain`에서 화이트리스트(로그인/회원가입 등)만 permitAll.
- 비밀번호는 `BCryptPasswordEncoder`로 암호화.
- 서비스 계층에서 예외는 `SeatSwapException`으로 던지고, `@RestControllerAdvice`로 일괄 처리한다.
  - **예외 조항 — 인증 주체 소실**: 토큰은 유효하나 해당 사용자가 없는 경우(삭제 등)는
    `SeatSwapException`(400) 대신 `AuthenticationException` 계열(예: `InsufficientAuthenticationException`)을
    던진다. `GlobalExceptionHandler`가 Security 예외를 rethrow → `ExceptionTranslationFilter` →
    `JwtAuthenticationEntryPoint`가 401 `{"message":"로그인이 필요합니다."}`로 응답한다.
- 인증 사용자 식별은 **principal의 userId 기준**(email 아님 — email은 변경될 수 있는 값).
  컨트롤러는 `@AuthenticationPrincipal AuthUserPrincipal principal`로 받고 `principal.userId()`를
  서비스에 넘겨 `findById`로 조회한다. JWT 필터도 토큰 sub(userId)로 사용자를 로딩한다.
- WebSocket(STOMP)은 채팅 전용 — `/topic/chat/{matchId}` 형태의 destination 규칙을 따른다.
- 엔티티 연관관계는 기본 `FetchType.LAZY`, N+1 우려되는 조회는 fetch join 또는 `@EntityGraph` 사용.
- 좌석 인식(OpenCV/OCR)은 이 서버의 책임이 아니다 — `SeatSwap/seatmap-service`(FastAPI)가
  반환하는 좌표 JSON을 받아 저장하는 클라이언트 역할만 한다.

## 의존성
Spring Web, Spring Security, Spring Data JPA, WebSocket, jjwt(JWT), MySQL Connector
(SeatSwap/backend/build.gradle 기준선 참고, 임의로 새 의존성 추가 시 사용자에게 먼저 확인)

## 실행 환경
로컬/Docker Compose 둘 다 지원 — `SeatSwap/backend/application.yml`은 환경변수
(`SPRING_DATASOURCE_URL` 등)로 오버라이드 가능하게 되어 있다. DB는 MySQL.
