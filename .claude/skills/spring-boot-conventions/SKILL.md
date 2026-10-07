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
- 교환 후보 조회·교환 신청/매칭 검증은 **공연(Performance) 단위**다 (같은 공연의 다른 회차끼리도 교환 가능, 2026-10-07 결정).
  두 티켓의 `performanceSession.performance`가 같은지 확인하고 회차 동일 여부는 요구하지 않는다. 세부(차액, 같은 회차 우선 노출)는 미정.
- WebSocket(STOMP)은 채팅 전용 — `/topic/chat/{matchId}` 형태의 destination 규칙을 따른다.
- 엔티티 연관관계는 기본 `FetchType.LAZY`, N+1 우려되는 조회는 fetch join 또는 `@EntityGraph` 사용.
- 좌석 인식(OpenCV/OCR)은 이 서버의 책임이 아니다 — `SeatSwap/seatmap-service`(FastAPI)가
  반환하는 좌표 JSON을 받아 저장하는 클라이언트 역할만 한다.
- seatmap-service 연동 관례(`SeatMapRecognitionClient`): `RestClient`에 연결 5초·읽기 60초 타임아웃을 두고, 공유 키가 설정된 경우에만 `X-Internal-Key` 헤더를 보낸다(선택). 인식 호출은 **트랜잭션 밖에서** 하고 결과 저장만 트랜잭션 안에서 한다.
  동시 인식은 전역 3건·사용자당 1건으로 제한한다(초과 시 429 `RATE_LIMITED` / 503 `BUSY`).
  등록 전 제한 검사(인식 호출 전): 공연장당 구역 상한 20(422 `ZONE_LIMIT_REACHED`, ADMIN 포함), 사용자 24시간 10건(429 `DAILY_LIMIT_REACHED`, ADMIN 제외). 설정은 `seatmap-service.max-zones-per-venue`·`daily-limit-per-user`(환경변수 `SEATMAP_MAX_ZONES_PER_VENUE`·`SEATMAP_DAILY_LIMIT_PER_USER`). 삭제 권한은 작성자·ADMIN(403 `ForbiddenException`, 임시 플래그 true면 누구나), 역할은 JWT가 아니라 `principal.isAdmin()`(DB role) 사용, 응답 `canDelete`. 업스트림 오류는 `SeatMapException`의 오류 코드로 매핑하고 응답 문구는 고정 한국어로 내려 업스트림 내용을 노출하지 않는다. 인식 결과는 저장 전에 검증한다(좌석 6000 상한, 좌석 section 1~50 등).

## DB 스키마 변경 (Flyway)
- `spring.jpa.hibernate.ddl-auto: validate` — 스키마는 Hibernate가 아니라 Flyway가 관리한다.
- 스키마 변경은 `src/main/resources/db/migration/V{n}__{snake_description}.sql` **신규 파일로만** 한다.
  이미 적용된 파일은 수정 금지. 새 엔티티·컬럼 변경 시 마이그레이션 파일을 함께 작성한다 (상세: erd-conventions).
- 테스트 중 `@DataJpaTest`/슬라이스 테스트는 `ddl-auto=none`, Flyway 비활성 상태를 유지한다(DB 없이 도는 테스트).
- 이력 조회와 적용 절차는 `SeatSwap/backend/README.md` 참고.

## 의존성
Spring Web, Spring Security, Spring Data JPA, WebSocket, jjwt(JWT), MySQL Connector, Flyway(flyway-core, flyway-mysql)
(SeatSwap/backend/build.gradle 기준선 참고, 임의로 새 의존성 추가 시 사용자에게 먼저 확인)

## 실행 환경
로컬/Docker Compose 둘 다 지원 — `SeatSwap/backend/application.yml`은 환경변수
(`SPRING_DATASOURCE_URL` 등)로 오버라이드 가능하게 되어 있다. DB는 MySQL.
