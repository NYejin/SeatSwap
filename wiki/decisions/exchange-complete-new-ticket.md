---
title: 교환 완료 시 티켓 처리: 안 2의 근거
type: decision
tags: [교환, 티켓, 유니크키, 8차답변]
sources: [산출물/04_요구사항정의서/후속요구사항_제안알림_교환됨_공연정보입력.md, 산출물/08_ERD/exchange-schema-design.md, SeatSwap/backend/src/main/resources/db/migration/V3__ticket_seat_columns.sql, SeatSwap/backend/README.md, CLAUDE.md]
updated: 2026-10-11
confidence: high
status: stable
---

# 교환 완료 시 티켓 처리: 안 2의 근거

결정 본문(교환 완료 시 기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓을 만든다)은 [CLAUDE.md](../../CLAUDE.md)의 "8차 답변" 항목이 기준이다. 여기에는 근거, 대안, 함정만 둔다. 용어는 [용어집](../glossary.md) 참고.

## 선택지 3가지

출처는 요구사항 문서 §3.3 (`산출물/04_요구사항정의서/후속요구사항_제안알림_교환됨_공연정보입력.md`).

| 안 | 방식 | 장점 | 단점 |
|---|---|---|---|
| 1 (기존 설계, 대체됨) | 기존 티켓 행의 좌석·회차를 UPDATE | 티켓 id가 유지된다 | `Ticket`의 좌석 컬럼이 JPA `updatable=false`라 해제하거나 네이티브 UPDATE가 필요하다. 유니크 충돌을 피하려면 임시 INACTIVE 순서(A를 임시 INACTIVE, B를 A 자리로, A를 B 자리 + ACTIVE)가 필요하다 |
| **2 (채택)** | 기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓을 INSERT | 아래 "안 2를 고른 이유" 참고 | 티켓 id가 바뀐다 |
| 3 (기각) | 두 티켓의 `user_id`를 서로 바꿈 | 행 수정이 적다 | 티켓에 붙은 요청·매칭이 다른 사람 것이 되어 기각 |

## 안 2를 고른 이유

- 티켓 행이 불변이다. 좌석 컬럼을 건드리지 않는다.
- '교환됨'과 '취소 불가'를 상태값(`EXCHANGED`)으로 표현할 수 있다.
- 교환 전 자리가 매칭 행(`ticket_a_id`/`ticket_b_id`)과 교환 이력(`old_ticket_id`/`new_ticket_id`)에 그대로 남는다. 그래서 매칭 응답의 좌석은 항상 교환 전 자리다.
- 대가는 티켓 id가 바뀐다는 점이다.

요구사항 문서 §3.3은 "유니크 충돌 자체는 기존 설계의 임시 INACTIVE 순서로도 풀 수 있었다"고 적는다. 즉 안 2의 이점은 충돌 회피가 아니라 불변성과 상태 표현이다. 구현 전이라 안 1로 되돌리는 비용은 문서 수정뿐이라고도 적혀 있다.

## 함정: 유니크 충돌 회피 순서

`uk_ticket_active_seat`는 (회차, 구역키, 열키, 번키, `active_flag`) 유니크다 (`V3__ticket_seat_columns.sql`). `active_flag`는 생성 컬럼으로 `status = 'ACTIVE'`일 때만 1이고 아니면 NULL이다. MySQL은 NULL을 유니크 비교에서 제외한다. 패턴 일반론은 [잠금 순서와 인덱스·유니크 키 함정](../gotchas/lock-order-and-index-pitfalls.md) 참고.

따라서 한 트랜잭션 안의 순서가 중요하다.

1. 두 기존 티켓을 먼저 `ACTIVE`에서 `EXCHANGED`로 UPDATE한다. `active_flag`가 NULL이 되어 좌석 슬롯이 비워진다.
2. 그 뒤 각 사용자에게 새 `ACTIVE` 티켓을 INSERT한다 (소유자 그대로, 회차·구역·열·번은 상대의 기존 티켓 값).

INSERT를 먼저 하면 상대의 기존 티켓이 아직 같은 좌석을 점유하므로 `uk_ticket_active_seat` 위반이 난다. 사용자당 활성 20개 상한은 활성 수가 늘지 않으므로 이 경로에서 검사하지 않는다. 잠금 순서(티켓 id 오름차순, 요청 id 오름차순, 매칭)는 그대로다.

추가 함정: V3의 `ck_ticket_status`는 `status IN ('ACTIVE', 'INACTIVE')`만 허용한다. `EXCHANGED`를 쓰려면 새 마이그레이션(V9 이후)에서 이 CHECK를 확장해야 한다. 요구사항 문서 §3.4도 "CHECK 등이 있으면 마이그레이션에서 확장"이라고 적는다.

## 구현 상태

확정이지만 미구현이다. `feature/exchange-complete`에서 구현할 예정이다. 현재 `TicketStatus`에는 `EXCHANGED`가 없고(`ACTIVE`, `INACTIVE`) 서비스·엔티티 Javadoc에 "구현 예정"으로만 적혀 있다. 대체된 이전 서술은 설계 문서(`산출물/08_ERD/exchange-schema-design.md`)의 3.3절 취소선 부분, 표의 6행·확정 g 행에 취소선과 대체 표기로 남아 있다.

## 확정: Q-15 (교환 완료 시 기존 티켓에 걸린 요청과 채팅)

확정 내용은 [CLAUDE.md](../../CLAUDE.md)의 "교환 수락·교환 완료" 항목이 기준이다. 근거만 적는다.

- 교환 완료 후 기존 두 티켓은 `EXCHANGED`가 되어 더 이상 내 자리가 아니므로, 그 티켓의 교환 요청은 같은 트랜잭션에서 `CLOSED`로 닫는다.
- 그 티켓에 걸린 다른 `CHATTING` 매칭은 자동 취소하지 않는다. 카드의 예약·교환 수락·교환 완료 버튼을 비활성화하고 "이미 교환된 좌석이에요"를 양쪽에 보여주며, 판단은 티켓 상태 `EXCHANGED`로 한다.
- 이전에는 요구사항 문서 §3.4 5번, §7 Q-15의 권장 기본값(다른 채팅까지 자동 취소)이 열린 질문으로 남아 있었고, 사용자 답변으로 자동 취소가 폐기됐다. 요청 CLOSED는 기본안 그대로 확정됐다. 문서 반영 상태는 [HISTORY.md](../../HISTORY.md)의 2026-10-10, 2026-10-11 항목 참고.
- 요청 CLOSED 쿼리는 `status = OPEN` 조건을 유지해야 한다 ([함정 3절](../gotchas/lock-order-and-index-pitfalls.md)).
