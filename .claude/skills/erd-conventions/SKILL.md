---
name: erd-conventions
description: DB 엔티티/ERD 관련 작업(신규 테이블, 관계 수정, JPA 엔티티 작성) 시 반드시 참고. 현재 확정된 11개 엔티티 기준선과 네이밍 규칙을 담고 있다. 기준 이미지는 산출물/08_ERD에 있다.
---

# ERD 컨벤션

## 기준선 (산출물/08_ERD/ERD.png)

User, Venue, Performance, Ticket, SeatMapLayout, SeatCorrection,
ExchangeRequest, ExchangeMatch, ChatRoom, Message, Review

## 네이밍 규칙
- 엔티티명: PascalCase 단수형 (`Ticket`, not `Tickets`)
- FK 컬럼: `{참조엔티티_snake}_id` (예: `venue_id`, `seatmap_id`)
- 상태값 컬럼은 `status`로 통일 (enum: PENDING/ACCEPTED/COMPLETED 등 문자열 저장)

## 관계 원칙
- `SeatMapLayout`은 **Venue(공연장) 단위**로 저장하고 재사용한다. Performance마다 새로 만들지 않는다
  (같은 공연장이면 좌석 배치가 동일 — NFR-03 재사용성).
- `ExchangeRequest`는 `Ticket`과 1:1 — 티켓 하나당 교환 요청은 하나만 유효.
- `ExchangeMatch`는 두 개의 `ExchangeRequest`(A측/B측)를 참조하는 단순 1:1 매칭 레코드다.
  추천 점수, 랭킹 등 알고리즘 매칭용 컬럼을 추가하지 않는다 (매칭 모델은 신청/수락 기반으로 고정).
- `Review`는 `ExchangeMatch` 완료 후에만 생성 가능하다.

## 변경 시 절차
1. db-schema-architect가 변경안 설계
2. `.dot` 파일 수정 → `dot -Tpng erd.dot -o ERD.png`로 재생성 (한글 라벨 사용 시 `fonts-nanum` 설치 필요)
3. 영향 있으면 산출물/04_요구사항정의서도 함께 갱신
4. 엔티티 구현은 `SeatSwap/backend/src/main/java/com/seatswap/domain/`에 반영
