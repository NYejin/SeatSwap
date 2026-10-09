# 교환 도메인 스키마 설계안 (V3~V7, 구현 완료)

작성 2026-10-08 / 브랜치 `docs/exchange-schema-design` / 상태: **설계 확정(2026-10-08 사용자 답변 반영, 남은 확인 필요는 6절 끝), 구현은 별도 지시 후**

> **구현 상태 (2026-10-08, 브랜치 `feature/ticket-register`, 미커밋)**: 1.1절 ticket 변경은 **V3로 구현 완료**(`V3__ticket_seat_columns.sql`, ticket 행이 있으면 SIGNAL 가드로 실패, 생성 컬럼 `active_flag`, `uk_ticket_active_seat`, `idx_ticket_user_status`; 정규화 키는 서비스 `SeatKeyNormalizer`가 NFKC·공백 제거·대문자·앞 0 제거·끝의 '열'/'번' 제거로 만든다). 티켓 등록 API(`POST /api/tickets`, `GET /api/tickets/me`, `DELETE /api/tickets/{id}`)도 구현됨. V4 희망 쪽은 아래 메모대로 구현 완료다.
>
> **구현 상태 갱신 (2026-10-08, 브랜치 `feature/exchange-candidates`, 미커밋)**: **2절 후보 조회 구현 완료** — `GET /api/exchange/requests/{id}/candidates?page&size`(기본 20, 최대 100), `ExchangeCandidateRepository`(네이티브 SQL)·`ExchangeCandidateService`. 스키마 변경 없음(V4 인덱스로 충분). 2절 SQL과 다른 점은 2절 끝의 '구현 반영' 참고(STRAIGHT_JOIN, 정렬, **차단·같은 쌍 채팅·예약 잠금 제외는 V5 테이블이 없어 미적용** — 확장 지점 `additionalExclusions()`; **V5(위 갱신)에서 같은 쌍 채팅·예약 잠금은 적용, 차단만 미적용**). X–X·NEG–NEG 성립은 사용자 미확정 기본값이었으나 2026-10-09 7차 답변으로 성립 확정.
>
> **구현 상태 갱신 (2026-10-08, 브랜치 `feature/exchange-want`, 미커밋)**: **V4 희망 쪽 구현 완료** — `V4__exchange_want_tables.sql`이 `exchange_request`·`exchange_want_range`·`exchange_want_seat`·`exchange_want_session` 4개 테이블을 만든다(API: `POST /api/exchange/requests`, `GET /api/exchange/requests/me`, `PATCH/DELETE /api/exchange/requests/{id}`). **매칭 쪽(차단·`exchange_match`·`exchange_ticket_lock`·`chat_message`·`exchange_history`)은 미구현이며 V5 이후**로 번호를 옮긴다(이 문서 본문의 'V4' 표기 중 매칭 쪽 테이블은 V5 이후로 읽는다). 티켓을 내리면 해당 요청이 CLOSED로 바뀌고(이후 수정 422 `TICKET_NOT_ACTIVE`), 예약 잠금 409는 매칭 구현 때 `ensureCanDeactivate`/`ensureNoActiveProposal` 훅에서 추가한다. 서버 안전 상한 5,000석·50범위(`exchange.want.*`, 초과 422 `WANT_SEAT_LIMIT_EXCEEDED`/`WANT_RANGE_LIMIT_EXCEEDED`). 리뷰 반영(테스트 254건): 자기 좌석 포함 422 `WANT_INCLUDES_OWN_SEAT`는 희망 회차가 내 티켓 회차 하나뿐일 때만이며 다른 회차가 있으면 같은 위치도 허용한다(사용자가 별도 결정 없이 추천안 채택, 이의 시 변경 가능). 상한 판정은 합집합 기준. 요청에도 지난 회차 마감 적용(422 `SESSION_CLOSED`, 희망 회차 마감은 400 `wantSessions[i].sessionId`). 열·번 부호 정수형은 400. 잠금 순서는 항상 티켓→요청. 남은 한계는 요청 응답의 열·번 범위가 정규화 값이라 원문 표기를 복원할 수 없다는 것뿐이다. V4 실제 파일에는 초안에 없던 `ck_exchange_want_range_sort`, `ck_exchange_want_session_priority`, `idx_exchange_want_range_request`, `idx_exchange_want_session_session`이 추가됐다(4.1 참고).
> **구현 상태 갱신 (2026-10-08, 브랜치 `feature/exchange-propose-accept`, 미커밋)**: **V5 구현 완료 — `V5__exchange_match_tables.sql`이 `exchange_match`·`exchange_ticket_lock` 2개 테이블만 만든다.** `user_block`·`chat_message`·`exchange_history`는 V6 이후다(이 문서의 'V5 이후' 표기 중 이 3개는 V6 이후로 읽는다). 사용자 결정(명령 6)에 따라 요청당 '제안 1개→수락' 모델이 아니라 확정 흐름(후보 선택→채팅 여러 개 동시→양쪽 예약→각자 완료)에 매핑했다. 구현 범위는 매칭 생성·예약(accept)·거절(reject)·취소(cancel)이며 COMPLETED·채팅·교환 이력·차단은 범위 밖이다(매칭 조회 API `GET /api/exchange/matches/me`·`/{id}`는 이후 `feature/exchange-ui`에서 추가됐고 새 마이그레이션은 없다). 1.7·1.8·3절·4절의 V5 변경점은 각 절 끝의 '구현 반영 (V5)'를 본다.
>
> **구현 완료 (2026-10-09, `feature/range-extra`, 미커밋, 7차 답변)**: 9절의 추가금 범위 단위 이동은 **V6(`V6__exchange_extra_per_range.sql`)**, 요청 소프트 삭제는 **V7(`V7__exchange_request_soft_delete.sql`)**으로 구현됐고 현재 DB는 V1~V7(10개 테이블)이다. 사용자 확정: 겹침 충돌은 (a) 거부(422 `WANT_EXTRA_CONFLICT`, 응답 최상위 `conflicts:[[i,j]]`), 매칭 추가금 스냅샷 4컬럼 동의, 삭제된 요청은 `/requests/me`에서 숨기고 내 매칭 목록에서만 회색 '(삭제)' 표시, X–X·NEG–NEG 성립 확정. **아래 1.2~1.4·2절·4절·6절의 '요청 단위 추가금'과 `uk_exchange_request_ticket`(및 `exchange_request.extra_*`) 서술은 V4/V5 시점 기록이며 9절로 대체됐다.** 'V6 이후'로 적힌 user_block·chat_message·exchange_history는 **V8 이후**다. 구현 후 실측은 후보 4,000건 규모 응답 중앙값 약 98~107ms, EXPLAIN `type=ALL` 없음, 이관은 `MigrationV6V7MysqlTest`로 자동화(테스트 기본 439건·환경변수 포함 456건).
>
> **8차 답변 반영 (2026-10-09, 브랜치 `docs/exchange-complete-ticket-model`, 문서만 — 코드·마이그레이션은 별도 브랜치에서 구현 예정)**: 이 문서의 두 확정이 **대체**됐다. ①**(g) 완료 시 교체**: '두 티켓의 좌석·회차 교체 + 임시 INACTIVE 순서'는 대체됨 -> 완료 시 **기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓을 INSERT**한다(`TicketStatus.EXCHANGED` 신규, 1.10 `exchange_history`는 `ticket_id` 대신 `old_ticket_id`·`new_ticket_id`). 매칭 행(`ticket_a_id`/`ticket_b_id`)의 좌석은 교환 전 자리이고 교환 후 자리는 새 티켓·이력에서 본다. ②**예약 방식**: 3차 답변의 '양쪽 예약 동의'(`a_reserved_at`/`b_reserved_at`)는 대체됨 -> **둘 중 한 명이 예약하면 RESERVED(두 티켓 잠금), 한 명이 예약을 취소하면 CHATTING 복귀**(`reserved_by_id`·`reserved_at` 가안, 상세는 `산출물/04_요구사항정의서/후속요구사항_제안알림_교환됨_공연정보입력.md` §3, §3A). 아래 본문의 해당 서술에는 '(8차 답변으로 대체됨)'을 표기했고, 현재 V5 스키마·코드는 아직 양쪽 동의 방식이다.

기준선: V1+V2 (users, performance, performance_session, ticket 4개 테이블). 이 문서의 SQL은 초안이며 마이그레이션 파일이 아니다.
좌석표(`SeatMapLayout`·`uid`·`section`)에 의존하지 않는다. 후기·신뢰도·신고 테이블은 만들지 않는다(신고는 8절에서 확장 여지만 언급).

---

## 0. 지시문과 확정 결정의 차이

| # | 사용자 지시문(작성 시점) | CLAUDE.md 최신 확정 | 이 설계의 처리 |
|---|---|---|---|
| 1 | 공연장 단위 구역 테이블(`venue_zone` 등) 가능성 | venue 테이블 삭제(V2), 공연장 단위 구역 테이블 없음, 구역은 사용자가 텍스트로 직접 입력하는 필수값. 자동완성은 좌석표 때 후속 | 구역 테이블 없음. `ticket.zone_label`(표시) + `zone_key`(정규화 키) 텍스트. 정규화 방식은 확정(a, 1.12 규칙) |
| 2 | 좌석 키 = 공연장·좌석표 기준 | 좌석 키는 공연(회차) 단위의 (구역, 열, 번) 텍스트 | 티켓의 키 = (회차, 구역, 열, 번). 희망 좌석은 회차와 분리: (구역, 열, 번)만 펼치고 회차는 별도 희망 회차 테이블(교차곱 폭발 방지) |
| 3 | 추가금: 금액 합 ≥ 0 / 합 ≤ 0 성립 | 금액 합 계산 폐기. [추가금 X]/[상관없음] 유무만 판정. 금액 표시용 유지 여부는 확인 필요 | **확정(d)**: 값 유형 4가지(X/ANY/POS/NEG) + 호환표로 후보 SQL 판정 교체. 금액(`extra_amount`)은 POS/NEG일 때만 저장, 계산에 쓰지 않고 후보 목록에 참고 표시 |
| 4 | 범위 입력 펼침 상한 300 | 상한은 지금 두지 않음(좌석표 때 후속) | 사용자 대상 상한 없음. DoS 방어용 **내부 안전 상한만** 둠(확정 b, 수치는 설정값) |
| 5 | `exchange_request` 변경 | 그런 테이블이 현재 없음 | 신규 설계로 취급 |
| 6 | 교환 완료 시 '내 Ticket을 새 자리로 갱신' (각자 완료) | 3차 답변 확정 (**8차 답변(2026-10-09)으로 대체됨**: 기존 두 티켓 EXCHANGED + 각자 새 자리 티켓 INSERT) | **충돌 발견**: 한쪽만 먼저 갱신하면 같은 활성 좌석이 두 티켓에 생겨 유일 제약 위반. **확정(g)**: 두 사람이 모두 '교환 완료'를 누르는 순간 한 트랜잭션에서 교체 (당시 확정 — 8차 답변으로 '교체'는 'EXCHANGED + 새 티켓 INSERT'로 대체) |
| 7 | 취소 후 같은 쌍 재매칭 불가 | 범위는 확인 필요(기본: 자동으로 서로 불가) | **철회(j, 확정)**: 취소는 상태만 원상태로 돌리고 재매칭 불가가 아니다. 재매칭 불가는 상대를 **차단하거나 신고했을 때만**(신고는 후속, 지금은 `user_block`만). 이에 따라 `exchange_match`의 CANCELED/CLOSED 구분을 CANCELED 하나로 단순화하고 사용자 쌍 판정 인덱스와 `user_low/high` 컬럼을 제거 |
| 8 | 로컬 DB의 ticket 행 수를 컨테이너로 조회 | - | **조회 실패**: Docker 데몬이 꺼져 있음(`dockerDesktopLinuxEngine` 파이프 없음). 가드로 대응(4절) |
| 9 | graphviz로 ERD 확인 | - | 이 환경에 `dot` 없음. 문법만 눈으로 점검(5절) |
| 10 | (j) 취소 후 재매칭 불가(CLAUDE.md 기본값 '자동으로 서로 불가') | 사용자 2026-10-08 확정 답변으로 **철회**: 차단·신고 때만 불가 | 후보 SQL의 재매칭 제외 조건은 `user_block`만 보고, 상태 5종을 4종(CHATTING/RESERVED/COMPLETED/CANCELED)으로 단순화. CLAUDE.md '확인 필요'의 해당 항목과 '제안(매칭) 상태 흐름 가안'의 '취소(재매칭 불가)' 표현은 doc-writer가 갱신 |

---

## 1. 테이블 설계

공통: InnoDB, `utf8mb4_0900_ai_ci`, 시각은 KST, 상태·키 컬럼은 `utf8mb4_bin` + CHECK, 제약 이름은 `uk_`/`idx_`/`fk_`/`ck_` 명시.

### 1.1 ticket 변경 (V3)
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| zone_label | VARCHAR(50) | NOT NULL (신규) | 구역, 사용자가 입력한 표시용(공백 정리만) |
| zone_key | VARCHAR(50) bin | NOT NULL (신규) | 구역 정규화 키(제안: NFKC, 모든 공백 제거, 대문자) |
| row_label | VARCHAR(20) | NOT NULL로 변경 (기존 VARCHAR(255) NULL) | 열 표시용 |
| row_key | VARCHAR(20) bin | NOT NULL (신규) | 열 정규화 키(숫자는 앞 0 제거, 끝의 '열' 제거, 영문 대문자) |
| col_label | VARCHAR(20) | NOT NULL로 변경 | 번 표시용 |
| col_key | VARCHAR(20) bin | NOT NULL (신규) | 번 정규화 키(끝의 '번' 제거 등) |
| status | VARCHAR(20) bin | NOT NULL DEFAULT 'ACTIVE', CHECK IN (ACTIVE, INACTIVE) | INACTIVE = 사용자가 내린 티켓(소프트 삭제; 매칭·이력이 참조하므로 물리 삭제 안 함) |
| active_flag | TINYINT | GENERATED STORED `IF(status='ACTIVE',1,NULL)` | 활성 유일 제약용 |
| created_at / updated_at | DATETIME(6) | NOT NULL (신규, JPA Auditing) | |

제약·인덱스
- `uk_ticket_active_seat` UNIQUE (performance_session_id, zone_key, row_key, col_key, active_flag): NULL은 유일 대상에서 제외되므로 **활성(ACTIVE) 티켓만** 같은 회차·구역·열·번 1개로 제한된다. 후보 조회의 ticket 접근 인덱스를 겸한다.
- `idx_ticket_user_status` (user_id, status): 내 활성 티켓 수(상한 20) 계산, 내 티켓 목록.
- 기존 `FKr9sms...(performance_session_id)` 인덱스는 위 유일 인덱스의 앞부분과 중복이지만 V1을 건드리지 않고 둔다(정리는 선택).
- 사용자당 활성 티켓 20개 상한은 서비스 검사 + 동시성은 3.3절.
- **등록·매칭 허용 기간(k, 확정)**: 회차 당일 끝(회차 `starts_at` 다음날 0시 KST)까지 티켓 등록·매칭을 허용하고, 그 시각이 지나면 티켓을 자동으로 INACTIVE로 바꾼다(스케줄러가 `status` 갱신, 후보 SQL에도 같은 시각 조건을 넣어 지연을 방어, 컬럼 추가 없음). 티켓 내리기(INACTIVE)는 **예약 잠금이 걸려 있지 않으면 언제든** 가능하다. 잠긴 티켓은 자동 비활성에서 제외하고 매칭이 끝난 뒤 비활성화한다(설계자 기본값, 6절 확인 필요).
- 생성 컬럼 `active_flag`는 JPA 엔티티에 매핑하지 않거나 `insertable=false, updatable=false`로 매핑한다(ddl-auto validate는 매핑된 컬럼만 검사).

### 1.2 exchange_request (신규, 가칭 유지 권장) — 티켓의 교환 희망 1건
이름: `exchange_request`가 무난. 'Ticket 1:1'이므로 `ticket_wish` 같은 이름도 가능하나 이력·매칭 용어와 맞춰 유지를 추천.

| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT PK AI | |
| ticket_id | BIGINT | NOT NULL, FK -> ticket, `uk_exchange_request_ticket` UNIQUE (티켓당 요청 1개) |
| extra_type | VARCHAR(10) bin | NOT NULL, CHECK IN ('X','ANY','POS','NEG'). X = 추가금 X(지불 안 함), ANY = 상관없음, POS = 금액 > 0(받아야만 교환), NEG = 금액 < 0(낼 의향 있음) |
| extra_amount | INT | NULL. POS/NEG일 때만 값(POS > 0, NEG < 0), X/ANY는 NULL. **매칭 계산에 쓰지 않고** 후보 목록에 참고 표시만. CHECK는 4.1 참고 |
| status | VARCHAR(20) bin | NOT NULL DEFAULT 'OPEN', CHECK IN ('OPEN','CLOSED') (CLOSED = 완료·티켓 내림·사용자 종료) |
| created_at / updated_at | DATETIME(6) | NOT NULL |

> **[대체됨: 2026-10-09 V6 — 추가금은 희망 범위 단위, 9절 참고]** 아래는 V4 시점의 요청 단위 서술이다.

추가금은 **요청 단위(티켓당 1개)** 값이다. 희망 좌석(행) 단위로 금액을 달리 받지 않는다. 사용자 입력이 '내 좌석 1개 + 희망 범위 + 추가금 1개'이고, 행 단위로 두면 want_seat(파생 데이터, 수정 시 전부 재생성)에 사용자 입력이 섞이며 후보 판정이 행마다 달라져 설명·UI가 복잡해지기 때문이다. 호환 판정은 후보 SQL의 요청 쌍(a, b)에서 한 번 한다.

연석·3자 순환 여지: 요청이 티켓을 직접 1:1로 가리키는 것은 지금 UNIQUE 하나뿐이다. 연석은 UNIQUE 해제 + `exchange_request_ticket` 자식 테이블로, 순환은 매칭 참여자 테이블 추가로 확장 가능하며 현재 컬럼을 깨지 않는다. 요청은 구역·좌석 값을 따로 갖지 않고 티켓을 참조하므로 좌석 중복 저장이 없다.

### 1.3 exchange_want_range — 사용자가 입력한 희망 범위(수정 화면 복원용)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT PK AI | |
| request_id | BIGINT | NOT NULL, FK -> exchange_request ON DELETE CASCADE, idx |
| zone_label / zone_key | VARCHAR(50) / bin | NOT NULL (범위 펼침 대상 아님) |
| row_from / row_to | VARCHAR(20) bin | NOT NULL. 숫자 범위면 from~to, 문자 열은 from=to |
| col_from / col_to | VARCHAR(20) bin | NOT NULL |
| sort_order | SMALLINT | NOT NULL |

펼친 결과는 `exchange_want_seat`가 갖는다. 범위 수정은 해당 요청의 want_seat를 **전부 지우고 다시 펼친다**(범위가 겹쳐도 중복·부분 삭제 문제 없음, 그래서 want_seat에 range_id를 두지 않는다).

### 1.4 exchange_want_seat — 펼친 개별 희망 좌석 (파생 데이터)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| request_id | BIGINT | FK -> exchange_request ON DELETE CASCADE |
| zone_key / row_key / col_key | VARCHAR bin | NOT NULL |
| | | **PRIMARY KEY (request_id, zone_key, row_key, col_key)** — 중복 방지 + 후보 조회 점조회 인덱스 |

별도 id를 두지 않는다(클러스터드 PK가 곧 조회 인덱스). 공연/회차는 두지 않는다: 같은 공연 안에서 좌석 위치 문자열은 회차 간 공통으로 보고, 회차 조건은 1.5가 맡는다.

### 1.5 exchange_want_session — 희망 회차와 사용자 우선순위
| 컬럼 | 타입 | 제약 |
|---|---|---|
| request_id | BIGINT | FK -> exchange_request ON DELETE CASCADE |
| performance_session_id | BIGINT | FK -> performance_session |
| priority | SMALLINT | NOT NULL (1이 가장 높음, 사용자 설정) |
| | | **PRIMARY KEY (request_id, performance_session_id)** |

- priority는 UNIQUE로 걸지 않는다(순서 변경 시 중간 충돌 회피, 재정렬은 한 트랜잭션에서 덮어씀).
- 희망 회차는 요청의 티켓과 **같은 공연**의 회차여야 한다(서비스 검사 + 후보 SQL에서 한 번 더 방어).
- 본인 티켓의 회차도 희망 회차에 넣을 수 있다(같은 회차 안 교환). 후보 노출 정렬은 이 priority다.

### 1.6 user_block — 사용자 차단
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT PK AI | |
| blocker_id / blocked_id | BIGINT | NOT NULL, FK -> users, `uk_user_block_pair` UNIQUE (blocker_id, blocked_id), CHECK (blocker_id <> blocked_id) |
| created_at | DATETIME(6) | NOT NULL |
| | | `idx_user_block_blocked` (blocked_id, blocker_id): 역방향 확인 |

차단은 단방향 저장, 후보·채팅 판정은 양방향으로 본다(어느 쪽이 차단해도 후보 제외·채팅 불가). **차단 시 두 사용자 사이의 진행 중 매칭(CHATTING/RESERVED)은 시스템이 CANCELED로 바꾸고(`canceled_by_id` = 차단한 사람) 예약 잠금을 해제한다**(확정, 유지). 재매칭 불가는 차단(과 후속 신고)에만 걸린다.

### 1.7 exchange_match — 후보 선택으로 열린 매칭(채팅방)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT PK AI | |
| request_a_id | BIGINT | NOT NULL FK. a = 후보 목록에서 상대를 고른 쪽(채팅 개시자) |
| request_b_id | BIGINT | NOT NULL FK. CHECK (request_a_id <> request_b_id) |
| user_a_id / user_b_id | BIGINT | NOT NULL FK -> users (요청에서 유도 가능하나 차단 조회와 내 매칭 목록용 비정규화) |
| status | VARCHAR(20) bin | NOT NULL, CHECK IN ('CHATTING','RESERVED','COMPLETED','CANCELED') (4종, CLOSED 삭제) |
| a_reserved_at / b_reserved_at | DATETIME(6) | NULL. '이 사람과 교환할게요' 누른 시각 (현재 V5 구현. **8차 답변(2026-10-09)으로 대체 예정**: 양쪽 동의 대신 `reserved_by_id`(FK users NULL)·`reserved_at`(NULL) 가안, RESERVED일 때만 NOT NULL로 CHECK) |
| a_completed_at / b_completed_at | DATETIME(6) | NULL. '교환 완료' 누른 시각 |
| canceled_by_id / canceled_at | BIGINT / DATETIME(6) | NULL. CANCELED일 때만. canceled_by_id가 NULL이면 시스템 취소(차단, 좌석 교체로 인한 종료, 티켓 내림 등) |
| created_at / updated_at | DATETIME(6) | NOT NULL |
| request_low_id, request_high_id | BIGINT GENERATED STORED | LEAST/GREATEST(request_a_id, request_b_id) |
| open_flag | TINYINT GENERATED STORED | `IF(status IN ('CHATTING','RESERVED'),1,NULL)` |

- `uk_exchange_match_open_pair` UNIQUE (request_low_id, request_high_id, open_flag): 같은 요청 쌍의 열린 매칭 1개(방향 무관, 중복 채팅방 방지).
- ~~`idx_exchange_match_user_a` (user_a_id, status), `idx_exchange_match_user_b` (user_b_id, status)~~ -> V5에서 단일 컬럼 `(user_a_id)`/`(user_b_id)`로 변경(3.4): 내 매칭 목록. 한 요청의 채팅 여러 개는 허용(요청당 열린 매칭 수 제한 없음). `idx_exchange_match_request_b` (request_b_id)는 FK용.
- 상태 의미: CHATTING(채팅, 예약 대기) -> RESERVED(양쪽 예약 완료, 두 티켓 잠김, 이후 티켓팅 사이트에서 양도 진행 — 현재 V5 구현. **8차 답변(2026-10-09)으로 대체 예정**: 둘 중 한 명이 예약하면 RESERVED, 한 명이 예약을 취소하면 CHATTING으로 복귀하고 잠금이 풀리며 같은 쌍도 재예약 가능) -> COMPLETED(양쪽 모두 완료). 'TRANSFERRING' 같은 별도 양도 상태는 두지 않는다: 양도는 서비스 밖에서 일어나며 RESERVED + 각자 완료 시각으로 충분하다. CANCELED = 취소(사용자 또는 시스템). **취소는 재매칭 불가가 아니다**: 상태만 원상태로 돌아가고(잠금 해제, 티켓은 다시 후보 대상) 같은 상대와 새 매칭을 다시 열 수 있다(`open_flag`가 NULL이 되어 유일 제약에 걸리지 않음). 양쪽 완료 전에는 누구든 취소할 수 있다. 한쪽만 완료한 매칭은 자동 완료·자동 취소가 없고 7일 경과 알림만 보낸다(`a/b_completed_at`과 `updated_at`으로 조회, 별도 컬럼 없음). 양쪽 완료로 두 티켓의 좌석이 바뀌면 그 티켓들의 다른 열린 매칭은 시스템이 CANCELED(canceled_by_id NULL)로 바꾼다 (**8차 답변(2026-10-09)로 정정**: 좌석이 바뀌는 것이 아니라 기존 티켓이 EXCHANGED가 되므로 그 티켓·요청의 다른 CHATTING 매칭을 시스템 취소한다, 확인 필요 Q-15).
- 점수·랭킹·신뢰도 컬럼 없음. 조건 일치는 저장하지 않는다.

**구현 반영 (V5, 2026-10-08, `V5__exchange_match_tables.sql`) — 위 초안과의 차이**
- 컬럼 추가: `ticket_a_id`·`ticket_b_id` BIGINT NOT NULL, FK -> ticket (예약 잠금·훅 검사가 요청을 거치지 않고 티켓을 바로 참조하도록).
- CHECK 추가: `ck_exchange_match_distinct_tickets`(ticket_a_id <> ticket_b_id), `ck_exchange_match_canceled`(status = 'CANCELED'이면 canceled_at NOT NULL, 아니면 canceled_at·canceled_by_id 모두 NULL). 기존 요청 서로 다름 CHECK는 유지.
- FK 추가: `canceled_by_id` -> users.
- 인덱스 변경: `idx_exchange_match_user_a (user_a_id, status)` / `idx_exchange_match_user_b (user_b_id, status)`는 **단일 컬럼 `(user_a_id)` / `(user_b_id)`로 변경**(아래 3.4 FK 인덱스 규칙). 추가: `idx_exchange_match_request_a (request_a_id)`, `idx_exchange_match_ticket_a (ticket_a_id)`, `idx_exchange_match_ticket_b (ticket_b_id)`. `idx_exchange_match_request_b (request_b_id)`는 유지.
- 상태 의미: `reject`(제안받은 b측만)와 `cancel`(참여자 누구나) **모두 CANCELED**이고 둘의 구분은 `canceled_by_id`로 한다(시스템 취소 = NULL). RESERVED였던 매칭이 취소되면 `exchange_ticket_lock`을 해제한다. 취소 후 같은 쌍은 다시 매칭할 수 있다(`open_flag`가 NULL이 되어 유일 제약에 걸리지 않음). 불허 전이는 409 `MATCH_STATE_CONFLICT`.
- 규칙: ~~티켓 좌석·회차 갱신은 COMPLETED 시점(양쪽 완료)에 한 트랜잭션에서 두 티켓을 교체한다(g).~~ (**8차 답변(2026-10-09)으로 대체됨**: COMPLETED 시점(양쪽 완료)에 한 트랜잭션에서 기존 두 티켓을 EXCHANGED로 바꾸고 각자 새 자리 티켓을 INSERT한다, 구현 예정.) 이미 시작한 채팅의 accept에는 회차 마감 검사를 하지 않는다(공연 시작 후에도 예약·취소 가능, 확정).

### 1.8 exchange_ticket_lock — 예약 잠금 (티켓당 1개)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| ticket_id | BIGINT | **PRIMARY KEY**, FK -> ticket |
| match_id | BIGINT | NOT NULL, FK -> exchange_match, idx |
| created_at | DATETIME(6) | NOT NULL |

예약 확정 시 매칭 한 건의 두 티켓 행을 한 트랜잭션에서 INSERT(티켓 id 오름차순). 취소·종료·완료 때 DELETE. PK 충돌 = '이미 다른 매칭에서 예약됨'(409 `TICKET_ALREADY_RESERVED`). 근거는 3절.

**구현 반영 (V5)**: 두 FK(`fk_exchange_ticket_lock_ticket`, `fk_exchange_ticket_lock_match`)를 **ON DELETE CASCADE로 확정**했다(부모 행 삭제 시 잠금도 함께 삭제). 요청 삭제 정책은 4절 참고.

### 1.9 chat_message — 채팅 메시지 (최소안)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT PK AI | |
| match_id | BIGINT | NOT NULL FK, `idx_chat_message_match` (match_id, id) |
| sender_id | BIGINT | NOT NULL FK -> users |
| content | VARCHAR(1000) | NOT NULL |
| created_at | DATETIME(6) | NOT NULL |

채팅 구현 단계에서 확정해도 되는 부분이며 이 설계의 핵심은 아니다. 차단 중이면 서비스에서 전송 거부.

### 1.10 exchange_history — 교환 이력 스냅샷 (사용자당 1행)
| 컬럼 | 타입 | 제약 |
|---|---|---|
| id | BIGINT PK AI | |
| match_id | BIGINT | NOT NULL FK, user_id와 함께 `uk_exchange_history_match_user` UNIQUE |
| user_id | BIGINT | NOT NULL FK (이력 주인) |
| counterpart_user_id | BIGINT | NOT NULL FK |
| old_ticket_id | BIGINT | NOT NULL FK (교환 전 내 티켓, 완료 후 EXCHANGED) — **8차 답변(2026-10-09)**: 기존 `ticket_id`('갱신된 내 티켓')를 대체 |
| new_ticket_id | BIGINT | NOT NULL FK (교환으로 새로 생긴 내 티켓) — **8차 답변(2026-10-09)** 신규 |
| performance_id | BIGINT | NOT NULL FK (공연은 등록 후 수정 불가라 참조 가능) |
| from_starts_at, to_starts_at | DATETIME(6) | NOT NULL 스냅샷 |
| from_zone, from_row, from_col | VARCHAR(50/20/20) | NOT NULL 스냅샷(표시용 label 값) |
| to_zone, to_row, to_col | VARCHAR(50/20/20) | NOT NULL 스냅샷 |
| completed_at | DATETIME(6) | NOT NULL |
| | | `idx_exchange_history_user` (user_id, completed_at) |

마이페이지는 `(from) -> (to)`를 이 테이블만 읽어 그린다(티켓이 이후 다시 바뀌어도 불변).

### 1.11 관계 요약
User 1:N Ticket / Performance; PerformanceSession 1:N Ticket; Ticket 1:1 ExchangeRequest; ExchangeRequest 1:N want_range / want_seat / want_session; ExchangeRequest 1:N ExchangeMatch(a측·b측); ExchangeMatch 1:N exchange_ticket_lock(최대 2) / chat_message / exchange_history(최대 2); User N:M User(user_block).

### 1.12 좌석 정규화 규칙 (확정 a)
- 서비스가 입력을 정규화해 `*_key`에 저장하고 원문은 `*_label`에 둔다. 규칙: 유니코드 NFKC(전각->반각), 양끝 공백 제거, 구역은 내부 공백까지 제거, 영문 대문자화, 열·번은 끝의 '열'/'번' 제거, 순수 숫자는 앞의 0 제거("03"→"3").
- 구역의 접미사('구역', '존', '층')는 지우지 않는다("1층"과 "1구역"은 다를 수 있음). 따라서 "A"와 "A구역"은 다른 구역으로 취급되어 매칭이 안 될 수 있다 → 입력 화면에서 같은 공연의 기존 구역 문자열을 **제안(DISTINCT 조회, 테이블 없음)** 하는 안을 후속으로 열어둔다.

---

## 2. 매칭 후보 조회 SQL 초안

파라미터 `:reqId` = 내 요청. 결과 한 행 = 후보 한 명(조인 키들이 모두 유일이라 중복 행이 생기지 않는다: want_session (request, session) PK, want_seat (request, 키) PK).

```sql
SELECT  b.id               AS cand_request_id,
        tb.id              AS cand_ticket_id,
        tb.user_id         AS cand_user_id,
        tb.performance_session_id,
        tb.zone_label, tb.row_label, tb.col_label,
        psb.starts_at,
        wsa.priority       AS my_session_priority,
        b.extra_type, b.extra_amount         -- 금액은 참고 표시용
FROM exchange_request a
JOIN ticket ta                   ON ta.id = a.ticket_id
JOIN performance_session psa     ON psa.id = ta.performance_session_id
-- (1) 내가 원하는 회차 x 내가 원하는 좌석
JOIN exchange_want_session wsa   ON wsa.request_id = a.id
JOIN performance_session psb     ON psb.id = wsa.performance_session_id
                                AND psb.performance_id = psa.performance_id        -- 같은 공연 방어
JOIN exchange_want_seat wa       ON wa.request_id = a.id
-- (2) 상대 티켓이 그 회차 + 그 좌석, 활성
JOIN ticket tb                   ON tb.performance_session_id = wsa.performance_session_id
                                AND tb.zone_key = wa.zone_key
                                AND tb.row_key  = wa.row_key
                                AND tb.col_key  = wa.col_key
                                AND tb.active_flag = 1                             -- 상대 좌석 ∈ 내 희망
JOIN exchange_request b          ON b.ticket_id = tb.id AND b.status = 'OPEN'
-- (3) 상대가 내 회차와 내 좌석을 원함 (내 좌석 ∈ 상대 희망)
JOIN exchange_want_session wsb   ON wsb.request_id = b.id
                                AND wsb.performance_session_id = ta.performance_session_id
JOIN exchange_want_seat wb       ON wb.request_id = b.id
                                AND wb.zone_key = ta.zone_key
                                AND wb.row_key  = ta.row_key
                                AND wb.col_key  = ta.col_key
WHERE a.id = :reqId AND a.status = 'OPEN'
  AND tb.user_id <> ta.user_id
  -- 추가금 호환(확정 d, V4 시점은 요청 단위 값 — V6 이후는 범위 단위, 9.8 참고): 유형만 보며 금액은 쓰지 않는다. 불성립은 POS-POS, POS-X 둘뿐.
  AND NOT (a.extra_type = 'POS' AND b.extra_type IN ('POS', 'X'))
  AND NOT (b.extra_type = 'POS' AND a.extra_type IN ('POS', 'X'))
  -- 회차 당일 끝(다음날 0시 KST)까지만 노출 (확정 k)
  AND NOW(6) < DATE_ADD(DATE(psb.starts_at), INTERVAL 1 DAY)
  -- 차단 (양방향)
  AND NOT EXISTS (SELECT 1 FROM user_block ub
                  WHERE (ub.blocker_id = ta.user_id AND ub.blocked_id = tb.user_id)
                     OR (ub.blocker_id = tb.user_id AND ub.blocked_id = ta.user_id))
  -- 재매칭 불가는 차단뿐이므로 위 user_block 조건이 전부다(취소 이력은 보지 않는다, 확정 j). 신고 연동은 신고 기능 착수 시.
  -- 이미 같은 요청 쌍으로 열린 채팅이 있으면 후보에서 빼고 '진행 중'에서 보여준다
  AND NOT EXISTS (SELECT 1 FROM exchange_match mo
                  WHERE mo.request_low_id  = LEAST(a.id, b.id)
                    AND mo.request_high_id = GREATEST(a.id, b.id)
                    AND mo.open_flag = 1)
  -- 상대 티켓이 이미 예약 잠금이면 제외 (확정 i: 잠긴 티켓은 후보 제외, 예약이 취소되면 잠금이 풀려 다시 후보로 복귀)
  AND NOT EXISTS (SELECT 1 FROM exchange_ticket_lock l WHERE l.ticket_id = tb.id)
ORDER BY wsa.priority, psb.starts_at, tb.id      -- 사용자 회차 우선순위 -> 일시 -> 안정 정렬
LIMIT :size OFFSET :offset;                      -- 후속: keyset(priority, starts_at, tb.id)
```
주의: 내 티켓이 잠겨 있거나 요청이 CLOSED면 서비스에서 후보 조회 자체를 막는다(쿼리에 넣지 않음). 점수·랭킹 없음: 정렬 키는 사용자가 정한 회차 우선순위와 일시뿐이다.

### 구현 반영 (2026-10-08, 위 초안 SQL과의 차이)
- **`SELECT STRAIGHT_JOIN`**: FROM 절 순서(내 요청 -> 내 희망 회차·좌석 -> 상대 티켓 -> 상대 요청 -> 상대 희망 회차·좌석)대로 조인하도록 고정했다. 힌트가 없으면 요청 수가 적을 때 옵티마이저가 상대 요청 테이블(`exchange_request b`)을 풀스캔으로 시작해 작업량이 전체 요청 수에 비례한다(EXPLAIN으로 확인). 고정하면 작업량이 내 희망 좌석 수 x 희망 회차 수에만 비례한다.
- **정렬**: 초안의 `wsa.priority, psb.starts_at, tb.id` 대신 구현은 `wsa.priority ASC, b.created_at DESC, b.id DESC`(내 희망 회차 priority -> 상대 요청 최신순). 점수·랭킹·신뢰도 없음, '같은 회차 우선'은 적용하지 않는다. 마감 조건은 `NOW(6) < DATE_ADD(DATE(starts_at), …)` 대신 `psb.starts_at >= 오늘 0시(KST)`로 동치 구현했다.
- **V5 적용 (2026-10-08, `feature/exchange-propose-accept`)**: 같은 쌍 열린 채팅(`exchange_match`의 `open_flag`) `NOT EXISTS`와 상대 티켓 예약 잠금(`exchange_ticket_lock`) `NOT EXISTS`를 `additionalExclusions()`에 추가했다. **차단(`user_block`)은 테이블이 없어(V6 이후) 여전히 미적용**이다(차단 사용자가 후보에 보일 수 있다). 또한 **내 티켓이 예약 잠금 상태이면 후보 조회 자체를 422 `TICKET_LOCKED`로 거절**한다(위 '주의'의 서비스 검사).
- **추가금 호환**: 초안 두 줄(POS–POS, POS–X, X–POS 불성립)을 그대로 구현했고 호환표와 일치한다. X–X·NEG–NEG 성립은 사용자 미확정 기본값이었으나 2026-10-09 7차 답변으로 성립 확정.
- **응답**: 상대 좌석(구역·열·번)·회차·닉네임·내/상대 추가금 유형·금액·`settlementHint`(POS–NEG이고 금액 범위가 겹치면 {min,max}, 참고값). 신뢰도 필드 없음.

### 필요한 인덱스와 실행 계획 (실측 반영: 요청 1,000건·희망 좌석 83만 행, 임시 MySQL 8.0)
| 단계 | 접근 | 사용 인덱스 |
|---|---|---|
| a | const | `exchange_request` PK |
| ta, psa | eq_ref | ticket PK, performance_session PK |
| wsa (희망 회차, 보통 1~10건) | ref | `exchange_want_session` PK (request_id, …) |
| wa (희망 좌석 n건) | ref | `exchange_want_seat` PK 앞부분 request_id |
| tb | ref, 상수 4컬럼 + active_flag | `uk_ticket_active_seat` (session, zone, row, col, active_flag): 점조회 |
| b | eq_ref | `uk_exchange_request_ticket` |
| wsb, wb | eq_ref | 각 PK 전체 |
| NOT EXISTS 서브쿼리 | ref / eq_ref | `uk_user_block_pair`, `idx_user_block_blocked`, `uk_exchange_match_open_pair`, `exchange_ticket_lock` PK |

- 작업량 ≈ (희망 좌석 수 n) × (희망 회차 수 s) 번의 tb 점조회. 예: 90석 × 3회차 = 270회, 각 점조회 뒤에 b/wsb/wb 점조회가 이어지므로 대부분 인덱스 룩업으로 끝난다. 사용자 대상 상한이 없으므로 n이 커지면 조회가 비례해 느려져 내부 안전 상한(확정 b, 6절)을 둔다.
- 조인 순서를 '내 희망 좌석 -> 상대 티켓'으로 잡은 이유: 내 희망이 보통 상대 전체 티켓보다 작고, tb 접근이 전부 상수 동등 비교로 끝난다. 역방향(상대 희망에서 내 좌석 찾기)은 wb PK 점조회로 이미 처리한다.
- **실측 EXPLAIN 요약**: 조인 11개 테이블 모두 const / ref / eq_ref이며 `type=ALL`이 없다(STRAIGHT_JOIN 적용 후). 응답시간(로컬 HTTP end-to-end, 가벼운 API 약 14ms 대비) 요청 100건 규모 중앙값 28~43ms, 1,000건 규모 42~69ms. DB 시간(EXPLAIN ANALYZE, 1,000건) 약 1~27ms. 한계: 균일 분포 합성 데이터, 동시 부하 미측정. 상세는 `SeatSwap/backend/README.md` '매칭 후보 조회 검증'.
- 마지막 ORDER BY는 결과 집합(후보 수십~수백)에서의 filesort뿐이다. 후보가 수천을 넘으면 keyset 페이징·총계 생략이 필요하다(후속).
- 추가 인덱스 후보(지금은 불필요): `exchange_want_seat (zone_key, row_key, col_key)` 역조회('내 좌석을 원하는 요청이 몇 건인지' 표시 기능을 넣을 때).

---

## 3. 동시성 보장 방법 비교

### 3.1 활성 티켓 좌석 유일
| 방법 | 장점 | 단점 |
|---|---|---|
| 생성 컬럼 UNIQUE (`active_flag` + 키) **추천** | DB가 보장, 락 없음, 동시 INSERT 중 하나만 성공(`uk_ticket_active_seat` 위반 -> 409). 소프트 삭제와 함께 쓰기 쉬움 | 생성 컬럼 한 개 추가, 엔티티 매핑 주의 |
| `SELECT … FOR UPDATE`로 존재 확인 후 INSERT | 추가 컬럼 없음 | 없는 행은 갭 락으로 막아야 해 데드락·락 범위 문제, 서비스 버그에 취약 |
| 상태 변경 직렬화 (애플리케이션 락/Redis) | 단순 | 다중 인스턴스에서 깨짐, DB 보장 없음 |

### 3.2 티켓당 예약 잠금 1개
교차 문제: 한 티켓은 어떤 매칭에서는 a측, 다른 매칭에서는 b측일 수 있어 `exchange_match`의 생성 컬럼 UNIQUE 하나로는 'a측 티켓 vs b측 티켓' 교차 충돌을 못 잡는다(컬럼 두 개에 각각 UNIQUE를 걸어도 서로 모르므로).
| 방법 | 장점 | 단점 |
|---|---|---|
| 잠금 테이블 `exchange_ticket_lock` PK(ticket_id) **추천** | 교차 충돌까지 DB가 보장, 두 티켓을 한 트랜잭션에서 INSERT하면 둘 다 성공/둘 다 실패, 해제는 DELETE | 테이블 1개 추가, 상태 변경 시 DELETE를 빼먹지 않도록 서비스 규칙 필요 |
| `ticket.reserved_match_id` 컬럼 + 조건부 UPDATE (`WHERE reserved_match_id IS NULL`) | 테이블 추가 없음, 원자적 | ticket <-> match 순환 FK, 티켓 행 갱신이 잦아 좌석 유일 인덱스와 같은 행에 경합 |
| 티켓 행 `SELECT … FOR UPDATE` (id 오름차순) 후 상태 검사 | 구현 쉬움 | DB 제약이 아니라 규약이므로 누락 시 이중 예약, 데드락 순서 규칙 필수 |

추천: 잠금 테이블을 **보장 수단**으로, 필요하면 INSERT 직전에 id 순 `FOR UPDATE`를 함께 써서 오류 메시지를 친절하게(409 '이미 다른 매칭에서 예약됨').

### 3.3 그 밖의 경합
- 사용자당 활성 티켓 20개 상한: 등록 트랜잭션에서 `SELECT … FROM users WHERE id=? FOR UPDATE`로 사용자 행을 잠근 뒤 COUNT(활성)+INSERT. (사용자 한 명 단위 직렬화라 비용 낮음. 생성 컬럼으로는 상한을 표현할 수 없다.)
- 열린 매칭 쌍 중복: `uk_exchange_match_open_pair`로 DB 보장.
- 쌍방 예약 동시 클릭: 예약 요청 시각 UPDATE 후 '상대 시각도 있나' 판정을 매칭 행 `FOR UPDATE` 아래에서 수행.
- 완료 교체(확정 g, 두 사람이 모두 완료를 누르는 순간): 두 티켓 + 매칭 행을 id 순으로 잠금. ~~티켓 교체는 좌석 유일 제약 때문에 A를 임시 INACTIVE -> B를 A의 좌석으로 -> A를 B의 좌석 + ACTIVE 순으로 한 트랜잭션에서 처리한다(한 UPDATE 문에서 서로 맞교환하면 행 단위 검사로 중간에 유일 위반).~~ (**8차 답변(2026-10-09)으로 대체됨 — 임시 INACTIVE 순서 폐기**: 두 기존 티켓을 먼저 `ACTIVE -> EXCHANGED`로 UPDATE해 `active_flag`를 NULL로 만들어 `uk_ticket_active_seat`에서 빼고, 그 뒤 각 사용자에게 새 `ACTIVE` 티켓을 INSERT한다. 잠금 순서(티켓 id↑ → 요청 id↑ → 매칭)는 그대로.)
- 동시 두 사람이 같은 좌석 등록: 3.1이 하나만 통과시킴.

### 3.4 FK 인덱스에 갱신 컬럼 금지 (V5에서 얻은 규칙, 실제 교착 재현)
- **외래키에 쓰이는 인덱스에 `status` 같은 자주 갱신되는 컬럼을 붙이지 않는다.** 초안의 `idx_exchange_match_user_a (user_a_id, status)`처럼 FK 인덱스에 갱신 컬럼이 들어 있으면, 그 컬럼을 UPDATE할 때 InnoDB가 인덱스 항목 갱신과 함께 부모 행(users 등)에 공유(S) 잠금을 걸어 다른 트랜잭션과 교착이 났다(동시성 테스트에서 실제 재현). FK 인덱스는 **단일 컬럼**(예: `(user_a_id)`)으로 두고, 상태 필터가 필요한 조회는 별도 인덱스로 분리하거나 소량 스캔으로 처리한다.
- 잠금 순서 규약(V5 확정): 티켓 id 오름차순 -> 요청 id 오름차순 -> 매칭.

---

## 4. Flyway 계획

- 다음 번호 **V3**. 파일은 둘로 나누는 것을 권장한다(MySQL DDL은 트랜잭션이 아니라 중간 실패 시 앞선 변경이 남으므로 실패 범위를 줄임).
  - `V3__ticket_seat_columns.sql`: 1.1의 ticket 변경.
  - `V4__exchange_domain.sql`: 1.2~1.10 신규 테이블(요청·희망·차단·매칭·잠금·채팅·이력). 신고 테이블 번호는 선점하지 않는다.
- **기존 ticket 행 이관·가드 필요 여부**
  - 조회 결과: **확인하지 못함**(Docker 데몬 미기동). 확인 쿼리 `SELECT COUNT(*) FROM ticket;` (컨테이너 `seatswap-mysql`, 사용자 `seatswap`, 비밀번호는 `SeatSwap/.env`의 `DB_PASSWORD`).
  - ticket은 엔티티만 있고 API가 없으므로 정상 환경에서는 0행일 가능성이 높다. 그러나 신규 `zone_label/zone_key` NOT NULL에 채울 값이 없다(기존 행은 구역 정보가 없음).
  - 추천: V3 맨 앞에 V2와 같은 `SIGNAL SQLSTATE '45000'` 가드로 **ticket 행이 있으면 실패**하고 '테스트 행을 삭제한 뒤 `flyway repair` 후 재시도'를 안내한다. 대안은 구역을 `'미입력'`으로 채우는 이관인데 같은 회차·번호 중복에서 활성 유일 제약 위반 위험이 있어 비추천(이 판단은 위 확인 결과가 0이면 무의미해지므로 구현 직전에 행 수를 다시 확인한다).
  - 가드와 별개로 `row_label/col_label`을 NOT NULL로 바꾸는 단계도 NULL 행이 있으면 실패하므로 같은 가드가 막아준다.
- **V5 구현 (2026-10-08)**: `V5__exchange_match_tables.sql` = `exchange_match` + `exchange_ticket_lock`만. 위 `V4__exchange_domain.sql`의 나머지(`user_block`·`chat_message`·`exchange_history`)는 **V8 이후**로 미룬다(V6·V7은 범위별 추가금·요청 소프트 삭제에 사용됨).
- **요청 삭제 정책 (V5 훅 채움; V7에서 소프트 삭제로 대체됨 — `MATCH_HISTORY_EXISTS` 제거, 9절 참고)**: 열린 매칭(CHATTING/RESERVED)이 있는 요청은 PATCH/DELETE 모두 409 `ACTIVE_MATCH_EXISTS`. 닫힌 요청을 삭제할 때는 CANCELED 매칭 행을 먼저 삭제하고, COMPLETED 매칭이 하나라도 있으면 409 `MATCH_HISTORY_EXISTS`(이력 보존). 잠긴 티켓(RESERVED 매칭)을 내리면 409 `TICKET_RESERVED`; 잠기지 않았다면 요청을 CLOSED로 바꾸고 그 티켓의 CHATTING 매칭을 시스템 취소(canceled_by_id NULL)한다.
- 엔티티 영향(backend-dev): `Ticket`에 필드 추가(zone/row/col label·key, status, 시각), 신규 엔티티 8개(ExchangeRequest, ExchangeWantRange, ExchangeWantSeat(복합키), ExchangeWantSession(복합키), UserBlock, ExchangeMatch, ExchangeTicketLock, ChatMessage, ExchangeHistory — 채팅은 후순위). 생성 컬럼은 매핑하지 않거나 읽기 전용.

### 4.1 V3/V4 초안 SQL (설계 문서 안에서만 사용, 파일 아님)
```sql
-- V3 (초안)
-- [가드] ticket 행이 있으면 SIGNAL 로 실패 (V2 의 프로시저 패턴)
ALTER TABLE ticket
  ADD COLUMN zone_label VARCHAR(50) NOT NULL AFTER performance_session_id,
  ADD COLUMN zone_key   VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL AFTER zone_label,
  MODIFY COLUMN row_label VARCHAR(20) NOT NULL,
  ADD COLUMN row_key    VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL AFTER row_label,
  MODIFY COLUMN col_label VARCHAR(20) NOT NULL,
  ADD COLUMN col_key    VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL AFTER col_label,
  ADD COLUMN status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN created_at DATETIME(6) NOT NULL,
  ADD COLUMN updated_at DATETIME(6) NOT NULL,
  ADD COLUMN active_flag TINYINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', 1, NULL)) STORED,
  ADD CONSTRAINT ck_ticket_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  ADD CONSTRAINT uk_ticket_active_seat UNIQUE (performance_session_id, zone_key, row_key, col_key, active_flag),
  ADD KEY idx_ticket_user_status (user_id, status);

-- V4 (초안, 발췌)
CREATE TABLE exchange_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,
  extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  extra_amount INT DEFAULT NULL,                                 -- POS(>0)/NEG(<0)일 때만, 표시용
  status VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'OPEN',
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_exchange_request_ticket UNIQUE (ticket_id),
  CONSTRAINT fk_exchange_request_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
  CONSTRAINT ck_exchange_request_extra_type CHECK (extra_type IN ('X', 'ANY', 'POS', 'NEG')),
  CONSTRAINT ck_exchange_request_status CHECK (status IN ('OPEN', 'CLOSED')),
  CONSTRAINT ck_exchange_request_amount CHECK (
    (extra_type IN ('X', 'ANY') AND extra_amount IS NULL)
    OR (extra_type = 'POS' AND extra_amount > 0)
    OR (extra_type = 'NEG' AND extra_amount < 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exchange_want_seat (
  request_id BIGINT NOT NULL,
  zone_key VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  row_key  VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  col_key  VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (request_id, zone_key, row_key, col_key),
  CONSTRAINT fk_exchange_want_seat_request FOREIGN KEY (request_id) REFERENCES exchange_request (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exchange_ticket_lock (
  ticket_id BIGINT NOT NULL,
  match_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (ticket_id),
  KEY idx_exchange_ticket_lock_match (match_id),
  CONSTRAINT fk_exchange_ticket_lock_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id),
  CONSTRAINT fk_exchange_ticket_lock_match  FOREIGN KEY (match_id)  REFERENCES exchange_match (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- [V4 실제 파일 추가분 — 구현 반영] 초안의 exchange_want_range / exchange_want_session 에 다음이 추가됐다.
--   exchange_want_range  : CONSTRAINT ck_exchange_want_range_sort CHECK (sort_order >= 0),
--                          KEY idx_exchange_want_range_request (request_id, sort_order)
--   exchange_want_session: CONSTRAINT ck_exchange_want_session_priority CHECK (priority >= 1),
--                          KEY idx_exchange_want_session_session (performance_session_id)
-- 나머지(exchange_want_range, exchange_want_session, user_block, exchange_match, chat_message, exchange_history)는
-- 1절의 컬럼·제약표 그대로 같은 패턴으로 작성한다. exchange_match 는 먼저 만들어야 lock 의 FK 가 걸린다.
```

---

## 5. ERD 반영
`산출물/08_ERD/erd.dot`에 신규·변경 테이블을 **'예정'** (노란 배경 `#FFF2CC` / 주황 헤더 `#F4B183`)으로 추가했다. 기존 4개 테이블은 현재 색 유지, ticket은 현재 컬럼 + 예정 컬럼 행(노란)으로 표시. 이 환경에 graphviz `dot`이 없어 PNG 생성·렌더 확인은 못 했고 문법(따옴표, HTML 라벨 닫힘, 엣지 대상 노드 존재)만 점검했다. 한계: 레이아웃 겹침 여부는 `dot -Tpng erd.dot -o ERD.png` 실행 후 눈으로 확인해야 한다. chat_message와 exchange_history, 잠금 테이블까지 포함해 노드가 늘어 rankdir=LR에서 가로가 길어질 수 있다.

---

## 6. 결정 사항 (2026-10-08 사용자 확정)

| 항목 | 확정 내용 |
|---|---|
| a. 구역 | 구역 필수 텍스트. 표시용 `zone_label`과 정규화 `zone_key`를 분리 저장(1.12 규칙). 자동완성·구역 테이블 없음 |
| b. 펼침 상한 | 사용자 대상 상한 없음. 내부 안전 상한만 설정값으로 둔다(기본 제안: 요청당 희망 좌석 5,000건, 범위 입력 50개, 초과 시 400) |
| c. 회차 | 희망 회차를 최소 1개 명시(저장은 항상 명시 행) + 사용자 우선순위. 화면 기본값은 전 회차 체크, 내 회차 1순위 |
| d. 추가금 | 값 유형 4가지(아래 호환표), 금액은 계산에 쓰지 않고 후보 목록에 참고 표시. 요청 단위 값(V6로 범위 단위로 대체) |
| e. 중복 좌석 | 활성 티켓 1개 유일(생성 컬럼 UNIQUE) + 안내 팝업의 '내 티켓 인증' 링크. 기존 보유자 정보는 노출하지 않는다 |
| f. 문자 열 | 숫자 열·번만 `3~5` 범위, 문자 열은 하나씩 추가 |
| g. 완료 시 교체 | 두 사람이 모두 '교환 완료'를 누르는 순간 한 트랜잭션에서 두 티켓 교체 + 이력 2행. 그 전에는 각자 완료 시각만 기록 (**8차 답변(2026-10-09)으로 대체됨**: '두 티켓 교체'는 '기존 두 티켓 EXCHANGED + 각자 새 자리 티켓 INSERT + 이력 2행(old/new 티켓 id)'으로, 임시 INACTIVE 순서는 폐기) |
| h. 차단·재매칭 | 재매칭 불가는 **상대를 차단하거나 신고했을 때만**. 차단 시 두 사용자 사이 진행 중 매칭은 CANCELED(시스템)로 바꾸고 잠금 해제 |
| i. 잠긴 티켓 | 예약으로 잠긴 티켓은 후보에서 제외, 예약이 취소되면 복귀 |
| j. 취소·한쪽 완료 | 취소는 상태만 원상태로(재매칭 가능, CANCELED/CLOSED 구분 삭제). 한쪽만 완료한 매칭은 자동 완료·취소 없이 7일 경과 알림만, 양쪽 완료 전에는 누구든 취소 가능 |
| k. 허용 기간 | 회차 당일 끝(다음날 0시 KST)까지 등록·매칭, 이후 자동 INACTIVE. 티켓 내리기는 예약 중이 아니면 언제든 |

### d 호환표 (요청 a, b 유형 조합)
| a \ b | X | ANY | POS | NEG |
|---|---|---|---|---|
| **X** | 성립(*) | 성립 | **불성립** (받아야 하는데 상대가 안 냄) | 성립 |
| **ANY** | 성립 | 성립 | 성립 | 성립 |
| **POS** | **불성립** | 성립 | **불성립** | 성립 |
| **NEG** | 성립 | 성립 | 성립 | 성립(*) |

(*) 사용자가 명시하지 않은 조합(X-X, NEG-NEG). 아무도 받아야 하지 않으므로 성립으로 둔 기본값이다. 불성립은 `POS`가 한쪽에 있고 다른 쪽이 `POS` 또는 `X`인 경우뿐이다(후보 SQL 두 줄).

### 남은 확인 필요
1. **X-X, NEG-NEG 조합 성립 여부**: 2026-10-09 7차 답변으로 **성립 확정**(해소).
2. **신고 시 재매칭 불가 연동**: 신고 기능 착수 시 확정한다.
3. **예약 중인 티켓의 자동 비활성**: 회차 당일 끝이 지났는데 예약 잠금이 남은 티켓은 매칭 종료 뒤 비활성화하는 것을 설계자 기본값으로 뒀다.
4. **중복 좌석 선점 대응**: 인증 전 임시로 관리자가 기존 티켓을 INACTIVE 처리하는 운영 절차가 필요한지(e의 위험).

---

## 7. 영향 범위 요약 (backend-dev / doc-writer 전달용)
- backend-dev(사용자 답변 후): V3, V4 작성, `Ticket` 필드 확장, 신규 엔티티, 정규화 유틸, 범위 펼침(서비스), 후보 조회(네이티브/QueryDSL), 예약 락 트랜잭션. `RepositoryQueryValidationTest` 확장.
- doc-writer: 요구사항정의서의 교환 흐름(상태 4종, 취소 후 재매칭 가능), 추가금 규칙(4유형 호환표), 사용자 차단, 교환 이력, 화면(티켓 등록의 구역 필수 입력, 희망 범위·회차 우선순위, 후보 목록)을 이 설계 확정 후 반영. erd-conventions 스킬의 '텍스트 좌석 입력 기반 매칭 스키마 방향' 절도 확정 이름으로 갱신.
- 영향 없는 것: users, performance, performance_session 스키마 불변.

## 8. 신고 확장 여지 (설계하지 않음)
신고는 교환 핵심 흐름 이후에 추가한다. 나중에 `exchange_match.id`·`users.id`를 참조하는 `user_report(reporter_id, target_user_id, match_id NULL, reason, status…)` 테이블을 새 V 파일로 붙이면 되고, 현재 스키마는 match_id·user_id 참조 키가 이미 있어 변경이 필요 없다. 차단과 신고는 별개 테이블로 둔다. 재매칭 불가는 차단과 신고에만 걸리므로 신고 착수 시 후보 SQL에 신고 대상 쌍 제외 조건을 추가한다(확인 필요).

**마이그레이션(V3, V4) 및 엔티티 구현은 별도 지시 전까지 금지(이 문서는 설계만).**

---

## 9. V6~V7 변경 설계: 추가금을 희망 범위 단위로 이동 + 요청 소프트 삭제 (2026-10-08, 브랜치 `feature/range-extra`)

상태: **구현 완료(2026-10-09, V6·V7, 7차 답변으로 사용자 확정)**. 아래 SQL은 설계 시점 초안이며 실제 마이그레이션은 `SeatSwap/backend/src/main/resources/db/migration/V6__*.sql`·`V7__*.sql`(재실행 가능 프로시저 패턴)이 기준이다. 1.2~1.4, 2절, 4절의 요청 단위 추가금 서술은 이 절로 **대체됐다**(V4/V5 시점 기록으로 남겨 둠).

### 9.1 사용자 확정 (2026-10-08)
- (A) 추가금은 요청 단위가 아니라 **희망 범위 단위**다. 범위마다 유형(X/ANY/POS/NEG)이 짝이다. 호환표는 그대로(불성립은 POS–POS, POS–X, X–POS뿐, X–X·NEG–NEG 성립 **확정**).
- (B) 요청은 하드 삭제하지 않고 **상태 DELETED**(소프트 삭제). 후보에서 제외, 연결된 취소 매칭 기록 보존, 삭제 후 같은 티켓에 새 요청 생성 가능.
- (C) 요청 수정·삭제 시 CHATTING 매칭은 **시스템 취소**(`canceled_by_id` NULL), RESERVED가 있으면 **409**. 스키마 변경 없음: V5의 `ck_exchange_match_canceled`는 CANCELED일 때 `canceled_at` NOT NULL만 요구하고 `canceled_by_id`는 NULL 허용이며 FK도 nullable이라 그대로 가능하다(티켓 내림 경로가 이미 같은 방식).

### 9.2 스키마 변경 요약
| 테이블 | 변경 |
|---|---|
| exchange_want_range | `extra_type VARCHAR(10) bin NOT NULL`, `extra_amount INT NULL` + CHECK 2개(요청 때와 같은 규칙) |
| exchange_want_seat | `extra_type VARCHAR(10) bin NOT NULL`, `extra_amount INT NULL` + CHECK. **PK·인덱스 불변**(유형은 키가 아니라 값) |
| exchange_request | `extra_type`·`extra_amount`와 관련 CHECK 2개 **제거**. status에 `DELETED` 추가, `deleted_at DATETIME(6) NULL`, 생성 컬럼 `live_flag`, `uk_exchange_request_ticket` UNIQUE(ticket_id) 제거 -> `uk_exchange_request_live_ticket` UNIQUE(live_flag, ticket_id) + FK용 `idx_exchange_request_ticket`(ticket_id) |
| exchange_match | **(발견 사항, 추가 제안)** 매칭 시점 스냅샷 컬럼 `a_extra_type`·`a_extra_amount`·`b_extra_type`·`b_extra_amount` 추가 |

exchange_match 스냅샷이 필요한 이유: 지금 내 매칭 조회(`ExchangeMatchQueryRepository`)는 `exchange_request`의 extra_*를 읽는데 요청에는 이제 추가금이 없고, 범위·좌석 행은 요청 수정(통째 교체)·소프트 삭제(하위 행 삭제)로 사라지며, 완료 후에는 티켓 좌석도 바뀐다. 취소 매칭 기록을 보존하려면 '그 매칭이 성립했을 때 적용된 추가금'이 매칭 행에 있어야 한다. a_extra_* = a측 희망 범위 중 b 티켓 좌석을 포함한 범위의 추가금, b_extra_* = b측이 a 좌석을 포함한 범위의 추가금. 매칭 생성 시 후보 SQL이 돌려준 wa/wb 값을 그대로 복사한다. 점수가 아니라 표시 스냅샷이다.

### 9.3 겹치는 범위의 추가금 충돌 정책 (확정: a 거부, 2026-10-09)
want_seat PK (request, zone, row, col)에 한 좌석은 한 행이다. 두 범위가 같은 좌석을 포함하고 추가금이 다를 때:

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| **(a) 거부 (추천)** | 겹치는 좌석에 서로 다른 추가금(유형 또는 금액)이 붙으면 422 `WANT_EXTRA_CONFLICT` | PK·후보 SQL·조인 행 수 불변, 의미가 하나, 사용자가 실수를 즉시 안다, 숨은 규칙 없음 | 사용자가 겹침을 고쳐야 함(드문 입력) |
| (b) 앞/뒤 범위 우선 | `sort_order` 작은(또는 큰) 범위의 값을 채택 | 입력 거부 없음 | 화면에서 안 보이는 숨은 규칙. 의도와 달라도 알 수 없음 |
| (c) PK에 유형 포함 | 좌석당 유형별 여러 행, 판정은 '호환되는 행이 하나라도 있으면 성립' | 입력 거부 없음, '둘 중 아무거나' 표현 가능 | 후보 SQL 조인 행이 곱으로 늘어 DISTINCT/GROUP BY·정렬 영향, 응답의 '적용 추가금'이 모호, PK 전체 재작성, 사용자 기대('범위마다 다른 추가금')와 의미가 다름 |

추천 (a). 사용자 모델이 '범위-추가금이 짝'이므로 한 좌석이 두 짝에 속하면 모순이고, 거부가 후보 SQL(점조회)과 응답 의미를 가장 단순하게 유지한다. **금액만 다른 겹침도 거부**한다(같은 유형이어도 참고 금액이 결정되지 않으므로). 유형·금액이 완전히 같은 겹침은 허용(중복 제거). 검사는 서비스(`WantSeatExpander`)에서 좌석 키 -> (유형, 금액) 맵으로 한다. DB 보장은 PK다: 서비스가 놓쳐도 INSERT가 PK 위반으로 실패하도록 **`INSERT IGNORE`/`ON DUPLICATE KEY`를 쓰지 않는다**. 안전 상한(5,000석·50범위)은 합집합 기준 그대로.

### 9.4 want_seat에 금액까지 두는 이유
후보 응답과 `settlementHint`는 내·상대 쪽 적용 추가금의 유형과 금액이 필요하다. 좌석이 속한 범위를 역으로 찾으려면 문자열 키(정규화 값)의 숫자 범위 비교를 SQL에서 해야 해 비현실적이고, `range_id`를 두면 후보 SQL에 조인 2개(wa->range, wb->range)가 늘어난다. 파생 데이터에 `extra_type`+`extra_amount`를 비정규화해 싣는 쪽이 조인을 늘리지 않는다. 비용은 행당 수 바이트(200만 행에서 약 +15MB 추정). 금액은 판정에 쓰지 않고 응답 표시에만 쓴다.

### 9.5 V6 초안 SQL (추가금 이동) — 설계 문서 안에서만
```sql
-- V6__exchange_extra_per_range.sql (초안). 순서: 컬럼 추가(NULL 허용) -> 백필 -> NOT NULL/CHECK -> 요청 컬럼 제거(유일한 파괴 단계)
ALTER TABLE exchange_want_range
  ADD COLUMN extra_type   VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER col_to,
  ADD COLUMN extra_amount INT NULL AFTER extra_type;
ALTER TABLE exchange_want_seat
  ADD COLUMN extra_type   VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
  ADD COLUMN extra_amount INT NULL;
ALTER TABLE exchange_match
  ADD COLUMN a_extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL, ADD COLUMN a_extra_amount INT NULL,
  ADD COLUMN b_extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL, ADD COLUMN b_extra_amount INT NULL;

-- 이관: 요청의 추가금을 그 요청의 모든 범위·좌석에 복사 (요청 단위 값이었으므로 동일 의미)
UPDATE exchange_want_range r JOIN exchange_request q ON q.id = r.request_id
   SET r.extra_type = q.extra_type, r.extra_amount = q.extra_amount;
UPDATE exchange_want_seat s JOIN exchange_request q ON q.id = s.request_id
   SET s.extra_type = q.extra_type, s.extra_amount = q.extra_amount;
UPDATE exchange_match m
  JOIN exchange_request qa ON qa.id = m.request_a_id
  JOIN exchange_request qb ON qb.id = m.request_b_id
   SET m.a_extra_type = qa.extra_type, m.a_extra_amount = qa.extra_amount,
       m.b_extra_type = qb.extra_type, m.b_extra_amount = qb.extra_amount;

ALTER TABLE exchange_want_range
  MODIFY extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  ADD CONSTRAINT ck_exchange_want_range_extra_type CHECK (extra_type IN ('X','ANY','POS','NEG')),
  ADD CONSTRAINT ck_exchange_want_range_amount CHECK (
    (extra_type IN ('X','ANY') AND extra_amount IS NULL) OR (extra_type = 'POS' AND extra_amount > 0) OR (extra_type = 'NEG' AND extra_amount < 0));
-- exchange_want_seat: 같은 방식 (ck_exchange_want_seat_extra_type, ck_exchange_want_seat_amount)
-- exchange_match: 4컬럼 NOT NULL 전환 + a측·b측 각각 같은 규칙의 CHECK

-- 요청 컬럼 제거: 컬럼을 참조하는 CHECK 를 먼저 삭제해야 한다(MySQL 은 CHECK 가 참조하는 컬럼 삭제를 거부)
ALTER TABLE exchange_request DROP CHECK ck_exchange_request_amount;
ALTER TABLE exchange_request DROP CHECK ck_exchange_request_extra_type;
ALTER TABLE exchange_request DROP COLUMN extra_amount, DROP COLUMN extra_type;
```
- **가드 필요 여부**: 이관이 무손실이라(모든 범위·좌석·매칭이 FK로 요청을 가진다) **SIGNAL 데이터 가드는 불필요**하다. 단 범위가 0개인 요청(서비스로는 만들 수 없음)은 추가금이 버려진다. NOT NULL 전환이 백필 누락을 막아준다.
- **재실행 가능성**: MySQL DDL은 롤백되지 않으므로 V2처럼 `INFORMATION_SCHEMA` 존재 확인 프로시저 가드(이미 있으면 건너뜀)로 **재실행 가능하게** 쓰기를 권장한다. 파괴 단계는 마지막 요청 컬럼 DROP 하나뿐이어서 그 전에 중단돼도 `flyway repair` 후 재실행하면 데이터가 남아 있다.
- **로컬 요청 행 유무**는 Docker 미기동으로 확인하지 못했다. 위 설계는 행이 있든 없든 안전하다. 구현 직전 `SELECT COUNT(*) FROM exchange_request`로 이관 건수만 기록한다.

### 9.6 V7 초안 SQL (요청 소프트 삭제) — 설계 문서 안에서만
```sql
-- V7__exchange_request_soft_delete.sql (초안)
ALTER TABLE exchange_request DROP CHECK ck_exchange_request_status;
ALTER TABLE exchange_request
  ADD COLUMN deleted_at DATETIME(6) NULL,
  ADD COLUMN live_flag TINYINT GENERATED ALWAYS AS (IF(status = 'DELETED', NULL, 1)) STORED,
  ADD KEY idx_exchange_request_ticket (ticket_id),                       -- FK 전용 단일 컬럼 인덱스
  ADD CONSTRAINT uk_exchange_request_live_ticket UNIQUE (live_flag, ticket_id),
  DROP INDEX uk_exchange_request_ticket,
  ADD CONSTRAINT ck_exchange_request_status CHECK (status IN ('OPEN','CLOSED','DELETED')),
  ADD CONSTRAINT ck_exchange_request_deleted CHECK (
    (status = 'DELETED' AND deleted_at IS NOT NULL) OR (status <> 'DELETED' AND deleted_at IS NULL));
```
- **FK 인덱스 규칙(3.4)**: 유일 키의 **맨 앞을 `live_flag`로** 둔다. `(ticket_id, live_flag)` 순서였다면 그 인덱스가 `fk_exchange_request_ticket`의 FK 인덱스 후보가 되어, DELETED로 바꿀 때 `live_flag`가 NULL로 바뀌며 FK 인덱스 항목이 갱신되고 부모(ticket) 행에 공유 잠금이 걸려 V5에서 재현한 교착이 날 수 있다. `(live_flag, ticket_id)`는 ticket_id가 선두가 아니므로 FK 인덱스가 될 수 없고, FK는 `idx_exchange_request_ticket` 하나에만 묶인다. OPEN->CLOSED일 때는 `live_flag`가 1 그대로라 유일 인덱스도 변하지 않는다.
- 유일 의미: NULL(DELETED)은 유일 대상에서 제외 -> 티켓당 **미삭제(OPEN·CLOSED) 요청 1개**. CLOSED(완료·티켓 내림) 요청이 남은 티켓에 새 요청을 만들려면 먼저 그 요청을 삭제해야 한다(현행 하드 삭제 흐름과 같은 사용 경험).
- 기존 행은 모두 `live_flag = 1`이라 새 유일 키로 이관해도 위반이 없다(옛 UNIQUE(ticket_id)와 동치). 가드 불필요.
- 구현 주의: 같은 이름의 CHECK를 한 문장에서 DROP/ADD하면 충돌할 수 있어 DROP CHECK를 별도 문장으로 분리했다. `DROP INDEX uk_exchange_request_ticket`는 FK의 대체 인덱스를 같은 ALTER에서 얻어야 허용되므로 한 문장에 두었다. **실제 MySQL 8.0.x에서 구문 검증 후 확정**한다.
- 제약 이름이 바뀐다: 서비스의 `DataIntegrityViolations.isViolationOf(e, ExchangeRequest.UNIQUE_TICKET)` 상수를 `uk_exchange_request_live_ticket`으로 교체해야 한다.

### 9.7 소프트 삭제된 요청의 하위 행 처리
**삭제 시 `exchange_want_seat`·`exchange_want_range`·`exchange_want_session`을 모두 지운다**(같은 트랜잭션, 요청 행만 DELETED로 남김).
- 근거: ①DELETED 요청을 읽는 쿼리가 없다(후보는 OPEN만, 매칭 표시는 매칭 스냅샷 컬럼 + 요청 status만 쓴다). ②want_seat는 요청당 최대 5,000행이라 남기면 의미 없는 행이 쌓여 후보 조회 대상 테이블만 비대해진다. ③사용자에게 '삭제'는 조건이 사라진다는 뜻이다. ④매칭 보존에 필요한 정보는 매칭 행 스냅샷과 티켓이 갖는다.
- 남기는 대안(range·session만 보존해 '복제해서 다시 만들기')은 요구에 없어 채택하지 않았다. 나중에 필요하면 스키마 변경 없이 정책만 바꿀 수 있다.
- CLOSED(티켓 내림·완료)는 현행대로 하위 행을 유지한다.

### 9.8 후보 조회 SQL 개정 (2절 대체 예정)
```sql
SELECT STRAIGHT_JOIN b.id AS request_id, tb.id AS ticket_id, tb.zone_label, tb.row_label, tb.col_label,
       psb.id AS session_id, psb.starts_at, ub.nickname, wsa.priority AS want_priority,
       wa.extra_type AS my_extra_type, wa.extra_amount AS my_extra_amount,   -- 내 쪽: 상대 좌석을 포함한 내 범위의 추가금
       wb.extra_type AS extra_type,    wb.extra_amount AS extra_amount,      -- 상대 쪽: 내 좌석을 포함한 상대 범위의 추가금
       b.created_at
FROM exchange_request a
JOIN ticket ta ON ta.id = a.ticket_id
JOIN performance_session psa ON psa.id = ta.performance_session_id
JOIN exchange_want_session wsa ON wsa.request_id = a.id
JOIN performance_session psb ON psb.id = wsa.performance_session_id AND psb.performance_id = psa.performance_id
JOIN exchange_want_seat wa ON wa.request_id = a.id
JOIN ticket tb ON tb.performance_session_id = wsa.performance_session_id
              AND tb.zone_key = wa.zone_key AND tb.row_key = wa.row_key AND tb.col_key = wa.col_key AND tb.active_flag = 1
JOIN exchange_request b ON b.live_flag = 1 AND b.ticket_id = tb.id AND b.status = 'OPEN'
JOIN exchange_want_session wsb ON wsb.request_id = b.id AND wsb.performance_session_id = ta.performance_session_id
JOIN exchange_want_seat wb ON wb.request_id = b.id
              AND wb.zone_key = ta.zone_key AND wb.row_key = ta.row_key AND wb.col_key = ta.col_key
JOIN users ub ON ub.id = tb.user_id
WHERE a.id = ? AND a.status = 'OPEN' AND tb.user_id <> ta.user_id
  AND NOT (wa.extra_type = 'POS' AND wb.extra_type IN ('POS','X'))
  AND NOT (wb.extra_type = 'POS' AND wa.extra_type IN ('POS','X'))
  AND psb.starts_at >= ?
  -- (V5 제외 조건: 열린 매칭 쌍, 예약 잠금 NOT EXISTS 그대로)
ORDER BY wsa.priority ASC, b.created_at DESC, b.id DESC LIMIT ? OFFSET ?
```
- 정책 (a) 덕에 좌석당 행이 하나라 wa·wb는 점조회 그대로이고 결과 중복이 생기지 않는다.
- 호환 판정은 요청 컬럼이 아니라 `wa.extra_type`(a측 좌석 행)·`wb.extra_type`(b측 좌석 행)을 쓴다. 호환표 의미는 동일하다.
- **예상 EXPLAIN(구현 시 EXPLAIN ANALYZE 재측정 필요, 아래는 예상)**: a·ta·psa const, wsa ref(PK), psb eq_ref, wa ref(PK 앞부분), tb eq_ref(`uk_ticket_active_seat`), **b eq_ref(`uk_exchange_request_live_ticket`)**, wsb·wb eq_ref(PK), ub eq_ref. `type=ALL` 없음이 목표. 달라지는 점: ①wa·wb가 `extra_type`을 읽으므로 PK 컬럼만 읽는 커버링 표기(`Using index`)가 사라질 수 있다. InnoDB는 PK 레코드에 컬럼이 함께 있어 추가 읽기 비용은 거의 없다. ②b가 새 유일 키를 쓴다. `live_flag = 1` 조건을 빼면 `idx_exchange_request_ticket`로 ref(삭제 행을 status로 거르는 추가 비용)가 되므로 **쿼리에 `live_flag = 1`을 반드시 넣는다**. ③STRAIGHT_JOIN 유지(조인 순서 불변).
- 인덱스 추가는 없다. 행 폭이 늘어 버퍼 풀 적재가 소폭 늘 뿐이다.
- `countSql`·`isCandidatePair`도 같은 FROM/WHERE 조각을 공유하므로 함께 바뀐다. 매칭 생성(propose)은 `my_extra_*`/`extra_*`를 받아 `exchange_match.a_extra_*`/`b_extra_*`로 복사한다(`isCandidatePair`가 두 값을 돌려주도록 확장 필요).

### 9.9 소프트 삭제가 미치는 쿼리·서비스 영향 목록
| # | 대상 | 변경 |
|---|---|---|
| 1 | 후보 SQL(목록·COUNT·isCandidatePair) | b 조인에 `live_flag = 1`, 추가금 판정 컬럼 교체(9.8). `a.status`/`b.status = 'OPEN'`이 DELETED를 이미 제외 |
| 2 | `existsByTicket_Id` (요청 생성 중복 검사) | 미삭제 요청만(`status <> DELETED`)으로 교체. DB 백스톱은 새 유일 키 |
| 3 | `ExchangeRequest.UNIQUE_TICKET` 상수·위반 판별 | 새 제약 이름으로 교체 |
| 4 | `findByOwner`/`findByOwnerAndTicket` (내 요청 목록) | 기본은 DELETED 제외(질문 2). 응답 status에 DELETED 허용 |
| 5 | 요청 수정 `update` | DELETED이면 거부(409 `REQUEST_DELETED` 제안). 현재의 `!isOpen()` -> `TICKET_NOT_ACTIVE`와 구분 |
| 6 | 요청 삭제 `delete` | 하드 삭제 -> 상태 DELETED + deleted_at, 하위 3개 테이블 삭제. `deleteCanceledByRequestId`·`existsCompletedByRequestId`(409 `MATCH_HISTORY_EXISTS`) **제거**(매칭 행을 지우지 않으므로 완료 이력이 있어도 삭제 가능). 이미 DELETED면 멱등(204) 제안 |
| 7 | `ensureNoActiveProposal` | 분리: RESERVED 존재 -> 409 `ACTIVE_MATCH_EXISTS`, CHATTING 존재 -> 같은 트랜잭션에서 시스템 취소(canceled_by NULL, canceled_at 설정, 예약 잠금은 없음). 수정·삭제 둘 다 |
| 8 | `closeByTicketId` (티켓 내림) | 이미 `status = 'OPEN'` 조건이라 DELETED를 CLOSED로 되살리지 않는다. **이 조건을 유지**해야 한다(없으면 삭제 부활·유일 키 위반) |
| 9 | 내 매칭 조회 `ExchangeMatchQueryRepository` | 추가금은 요청 조인이 아니라 `m.a_extra_*`/`m.b_extra_*` 스냅샷에서 읽는다. 삭제 표기를 위해 `ra.status`·`rb.status`를 SELECT에 추가하고 id 조인은 유지(DELETED 요청 행도 남아 있어 조인 성공). 응답에 요청 삭제 여부 필드 추가 -> 프론트 '(삭제)' 회색 표기 |
| 10 | 매칭 제안(propose) 검증 | 양쪽 요청 OPEN 확인(현행)이 DELETED를 막는다 |
| 11 | 열린 매칭 존재 검사(`existsOpenByRequestId`) | 변경 없음 |
| 12 | 티켓 기준 요청 조회(내 티켓 목록의 '요청 있음' 표시 등) | ticket_id로 요청을 찾는 곳은 미삭제 기준으로 |

### 9.10 Flyway 계획 · 락 · 동시성
- **번호/분리**: **V6 = 추가금 범위 이동**(9.5), **V7 = 요청 소프트 삭제**(9.6). 나누는 이유: MySQL DDL은 문 단위로만 원자적이라 중간 실패 시 앞 변경이 남는다 -> 실패 범위 축소, 리뷰·롤포워드 용이, 두 변경이 서로 독립. 같은 릴리스에 함께 나가도 된다. **영향**: 기존 문서의 'V6 이후'(user_block·chat_message·exchange_history)는 **V8 이후**로 밀린다.
- **롤백 곤란 요소**: ①V6의 요청 컬럼 DROP은 되돌릴 수 없다(복원하려면 새 마이그레이션으로 범위에서 역이관하며, 범위별 값이 달라졌으면 정보 손실 -> 백업 복원). ②V7을 되돌리면 UNIQUE(ticket_id) 복원 시 DELETED 행(같은 티켓 중복)이 있으면 실패하므로 먼저 처리해야 한다. Flyway Community에는 undo가 없으므로 실패 시 정책은 `flyway repair` + 새 V 파일(롤포워드), 운영은 마이그레이션 전 백업이다.
- V1~V5는 수정하지 않는다. `ddl-auto=validate`가 엔티티와 맞아야 하므로 엔티티 변경과 V6/V7이 같은 PR에 들어가야 한다.
- **락 순서는 티켓 -> 요청 -> 매칭 유지**. 요청 수정·삭제는 지금도 요청 행만 잠그므로(`findByIdForUpdate`) 여기에 매칭 행 갱신(CHATTING 시스템 취소)이 붙어도 요청 -> 매칭으로 전체 순서의 부분 순서라 엇갈리지 않는다. 매칭 행은 **id 오름차순**으로 잠근다(두 요청의 편집이 같은 매칭을 건드려도 같은 순서). 예약 경로(티켓 id 오름차순 -> 요청 -> 매칭)와도 순환이 없다. 이 경로에서 **티켓 행을 새로 잠그지 않는다**(기존 주석 규칙 유지).
- **수정·삭제와 예약의 경합**: 예약(RESERVED 전환)은 요청 행 잠금 아래에서 일어나 수정/삭제와 직렬화된다. 수정이 먼저면 CHATTING이 이미 CANCELED라 예약이 409 `MATCH_STATE_CONFLICT`, 예약이 먼저면 수정이 409 `ACTIVE_MATCH_EXISTS`.
- **삭제 후 재생성 경합**: 생성은 티켓 행 FOR UPDATE 후 미삭제 요청 검사 + INSERT. 삭제 커밋 전의 INSERT는 새 유일 키가 미커밋 갱신을 기다린 뒤 판정하며, 위반 시 409로 변환(현행과 같은 패턴).
- **FK 인덱스 규칙 점검**: exchange_request의 FK 인덱스는 `idx_exchange_request_ticket(ticket_id)` 단일 컬럼이다. 갱신 컬럼(`status`, `live_flag`)이 들어간 인덱스는 FK 인덱스가 아니다. want_range/want_seat에 추가한 컬럼은 어떤 FK 인덱스에도 들어가지 않는다.

### 9.11 결정 기록
| 항목 | 결정 | 근거 |
|---|---|---|
| 추가금 위치 | 요청 -> 범위 (사용자 확정) | 범위마다 협상 조건이 다름 |
| 파생 좌석에 유형·금액 | 비정규화로 둠, PK 불변 | 후보 SQL 조인 증가 방지 |
| 겹침 충돌 | (a) 422 거부, 유형·금액 모두 같아야 겹침 허용 (사용자 확정) | 9.3 |
| 호환표 | 불성립은 POS–POS, POS–X, X–POS뿐 (X–X·NEG–NEG 성립 확정) | 사용자 확정 |
| 소프트 삭제 | `DELETED` + `deleted_at` + `live_flag` 생성 컬럼 | ticket의 활성 유일 패턴과 일치 |
| 유일 키 순서 | `(live_flag, ticket_id)` + 별도 FK 인덱스 | 3.4 FK 인덱스 규칙 |
| DELETED 하위 행 | 3개 테이블 모두 삭제 | 9.7 |
| 매칭 추가금 | 매칭 행에 a/b 스냅샷 컬럼 4개 (사용자 확정) | 요청에서 추가금이 사라지고 하위 행이 삭제되므로 기록 보존에 필요 |
| 시스템 취소 | `canceled_by_id` NULL, 스키마 변경 없음 (취소 사유 컬럼은 채팅/시스템 메시지 도입 때 판단) | V5 CHECK가 허용 |
| 완료 이력 있는 요청 삭제 | 허용(409 `MATCH_HISTORY_EXISTS` 제거) | 소프트 삭제라 기록이 보존됨 |
| V 번호 | V6(추가금), V7(소프트 삭제), user_block 등은 V8+ | 9.10 |

### 9.12 역할별 영향 요약 (구현 완료, 기록용)
- **backend-dev**: 엔티티 `ExchangeRequest`(extra 제거, `DELETED`/`deletedAt`, `UNIQUE_TICKET` 상수 교체), `ExchangeWantRange`·`ExchangeWantSeat`(extra 추가), `ExchangeMatch`(스냅샷 4컬럼), 생성 컬럼 `live_flag`는 읽기 전용/비매핑. `WantSeatExpander`의 범위별 추가금·겹침 충돌 검사(422 `WANT_EXTRA_CONFLICT`), 요청 DTO(요청 단위 `extraType`/`extraAmount` 제거 -> `ranges[i].extraType/extraAmount`, 오류 키 `ranges[i].extraType`), `ExchangeRequestService` create/update/delete(9.9 #2~#8), 후보 SQL(9.8)과 `Row` 레코드, `ExchangeMatchQueryRepository`(9.9 #9), 매칭 생성 시 스냅샷 복사, 테스트(`ExchangeCandidateQueryTest`·`ExchangeRequestControllerSliceTest`·`ExchangeMatchQueryMysqlTest` 등 extra를 쓰는 backend 테스트), 후보 EXPLAIN 재측정과 README 갱신.
- **frontend**: 희망 조건 폼(`ExchangeRequestFormPage`, `ExtraFields`)의 추가금 입력을 요청 하나에서 **범위 행마다**로 이동(유형 + POS/NEG일 때 금액), 겹침 422 오류 표시, 후보 목록(`CandidatesPage`)은 내/상대 적용 추가금 표시(필드 의미는 유지하되 범위 기준), 내 매칭 목록의 '(삭제)' 회색 표기, 요청 삭제 확인 문구(진행 중 채팅 자동 취소, 예약 중이면 불가), `types/exchange.ts` 변경.
- **doc-writer**: 이 절 반영(요구사항 추가금 규칙을 범위 단위로, 요청 삭제 = 소프트 삭제, X–X·NEG–NEG 성립 확정, CLAUDE.md '확인 필요'에서 해당 항목 제거), 'V6 이후' 표기를 'V8 이후'로 정정.

### 9.13 사용자 확인 질문 (추천안 포함)
1. **겹치는 범위의 추가금 충돌**: 추천 (a) 거부(유형·금액이 다르면 422, 완전히 같은 겹침만 허용). 대안 b(순서 우선, 숨은 규칙), c(여러 행 허용, 후보 SQL·PK 복잡).
2. **삭제된 요청을 '내 희망 조건 목록'에도 회색 '(삭제)'로 남길까요?** 추천: 희망 조건 목록(`/requests/me`)에서는 숨기고 **내 매칭 목록**에서만 '(삭제)' 회색 표기. '목록·화면'이 어느 화면인지 모호하다.
3. **매칭 행에 추가금 스냅샷 4컬럼 추가에 동의하시나요?** (요청에서 추가금이 사라져 취소 매칭 기록을 보존하려면 필요. 추천: 동의)

(그 외 — 하위 행은 DELETED 시 전부 삭제, 완료 이력이 있어도 삭제 허용, 취소 사유 컬럼은 지금 안 둠, V6/V7 분리 — 는 추천안으로 확정해 두었고 이의가 있으면 알려 주세요.)

**(2026-10-09) 사용자 답변을 받아 V6/V7을 구현했다. 위 질문 1~3은 모두 추천안으로 확정(겹침 거부, `/requests/me` 숨김, 스냅샷 4컬럼 동의).**
