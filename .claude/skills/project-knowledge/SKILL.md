---
name: project-knowledge
description: 산출물/03~05,08에 있는 프로젝트계획서·요구사항정의서·WBS·ERD의 핵심 내용을 요약한 참고 문서. docx/xlsx 원본을 직접 열지 못하는 상황에서도 이 스킬 하나로 서비스 개요·기능 목록·일정·데이터 모델을 파악할 수 있다. 기능을 구현하거나 설계 질문에 답할 때, 또는 산출물 문서 내용이 필요한데 원본을 열기 어려울 때 먼저 참고한다. 원본과 내용이 달라지면(산출물 문서를 수정하면) 이 스킬도 함께 갱신해야 한다.
---

# 프로젝트 지식 요약 (산출물 문서 기반)

이 파일은 `산출물/03_프로젝트계획서`, `산출물/04_요구사항정의서`, `산출물/05_WBS`,
`산출물/08_ERD` 원본(docx/xlsx/png)의 핵심 내용을 텍스트로 옮겨둔 요약본이다.
Claude Code는 바이너리 문서를 직접 파싱하지 못하므로, 에이전트들은 작업 중
이 문서 내용이 필요하면 원본을 열어보려 하기 전에 **이 스킬을 먼저 참고**한다.
더 정확하거나 최신 정보가 필요하면 산출물 원본을 직접 확인한다.

---

## 0. 방향 전환 (2026-10-07) — 먼저 읽을 것

이 절은 CLAUDE.md '매칭 모델'·'좌석표 트랙 동결'·'텍스트 좌석 입력 기반 매칭'·'확정 결정 세부'(2026-10-08)·'확인 필요'를 요약한 것이다.
**아래 1~7절의 좌석표·"단순 신청/수락" 서술이 이 절과 충돌하면 이 절(과 CLAUDE.md)이 우선**한다.

- **서비스 핵심 흐름**: 본인 좌석 1개 + 교환 희망 좌석 범위(예: 3열 3~5번, 4열 3~5번) + 추가금을 텍스트로 입력 → 범위를 개별 좌석으로 펼쳐 저장 →
  내 좌석이 상대 희망에 있고 상대 좌석이 내 희망에 있는 상대를 자동으로 찾아 후보 제시 → **매칭 → 채팅 → 교환 후 각자 수락 → 교환 확정/완료**(2026-10-08 확정; 후보 목록에서 사용자가 선택, 2차 답변 참고. 3차 답변: 공연 시작 후에도 매칭·예약·완료 가능). 같은 공연이면 회차 구분 없이 교환 가능, 매칭은 사용자가 원하는 회차끼리만
- **동결**: 좌석표 이미지 인식·좌석표 기반 범위 선택과 그에 딸린 미완 과제(V4 제재·신고, 관리자 API·페이지·정식 등록, 수정 로그 화면, DRAFT 삭제 정책,
  인식 정확도 추가 검증). 기존 코드·결정은 보존하되 새 매칭 코드가 의존하지 않는다
- **확정(사용자 결정, 2026-10-07)**: 위 핵심 흐름, 범위 자동 펼침 저장, 상호 일치 매칭, 다른 회차 허용, 좌석표 후순위
- **확정(사용자 답변, 2026-10-08)**:
  - 공연장 단위 구역 테이블 없음, 좌석은 공연 단위로 두고 구역·열·번을 텍스트로 직접 입력(구역 등록·자동완성·직사각형 범위 선택·펼침 상한은 좌석표 기능과 함께 후속). 열 표기(숫자/문자)는 사용자 입력. 공연장 관련 새 지시는 사용자에게 되묻는다
  - 교환은 같은 공연 안에서 회차 구분 없이 가능, 매칭은 사용자가 원하는 회차끼리만(희망 회차 지정). 후보 노출은 사용자가 설정한 회차 우선순위 순
  - 추가금 상한 없음 (과거 기록: 체크박스 [추가금 없음] [제시] — 3차·6차 답변으로 교체, 아래 참고)
  - 흐름 매칭 → 채팅 → 교환 후 각자 수락 → 확정/완료. 성사 후에는 마이페이지에 DB 기록만(관련 기능 없음)
  - 같은 회차·구역·열·번의 활성 티켓은 1개만(사용자당 활성 티켓 상한 예 20). 이미 등록된 좌석이면 안내 팝업/말풍선 + '내 티켓 인증' 링크(인증은 추후, 사진 업로드 → 관리자 수동 확인 예정)
  - 지정석·1매 제한 없음(스탠딩 등도 텍스트 입력), 연석·3자 이상 순환 교환은 구현하지 않되 모델이 막지 않게 함
  - 후기 기능 없음, 신뢰도 점수는 매칭에 쓰지 않음(우선순위는 사용자 설정), 사용자 신고는 필수(교환 핵심 흐름 이후)
- **확정(사용자 2차 답변, 2026-10-08)**:
  - **`venue` 테이블 삭제**(공연장은 공연 정보에 텍스트로 둔다고 이해, 공연장 검색·추가·정식 등록 폐지). 범위는 3차 답변으로 확정(아래), **4차 답변(2026-10-08)으로 구현 완료(미커밋, `feature/remove-venue`)**: 아래 '4차' 항목
  - 추가금 부호: **+는 내가 받을 금액, −는 내가 낼 수 있는 금액**(이전 기본안의 반대 의미 정정; 매칭에는 금액을 쓰지 않고 후보 목록 참고용 표시로 유지 — 6차 답변 확정)
  - 매칭: 시스템이 후보 목록을 띄우면 **사용자가 골라 채팅 시작**(자동 선택 아님). 한 티켓에 채팅 여러 개 동시 가능, **동시 '예약'은 불가**. **사용자 차단 기능**(차단 시 후보 제외·채팅 불가) 신규 요구. (2차의 '취소 후 같은 상대와 재매칭 불가'는 6차 답변으로 폐기 — 재매칭 불가는 차단·신고 때만)
  - 교환 완료: 티켓팅 사이트에서 양도를 끝낸 뒤 각자 '교환 완료'. 마이페이지 '교환 이력'에 `(기존 자리) -> (바꾼 자리)`(자리 정보 스냅샷 저장)
  - 제안 상태 흐름 가안: 후보 선택 → 채팅(다중) → 예약(티켓당 1개, 3차 답변 확정) → 양도 → 각자 완료 → 완료, 또는 취소(양쪽 완료 전 누구든 가능, 상태만 복귀, 재매칭 불가 아님 — 6차 답변)
- **확정(사용자 3차 답변, 2026-10-08)**:
  - **venue 삭제 범위 확정**: 공연 등록 화면의 공연장은 **텍스트 입력 한 칸(필수)**, 공연장 검색·추가, 공연 목록의 공연장 필터, 공연장 정식 등록(VERIFIED) 개념 모두 삭제. 코드·DB 반영(V2 마이그레이션, Venue 엔티티·컨트롤러·서비스·VenuePicker 삭제, `performance`에 공연장 이름 텍스트 컬럼)은 별도 작업으로 곧 시작 → 4차 답변 후 구현 완료(아래)
  - **venue 삭제 구현 완료(4차 답변, 2026-10-08, 미커밋)**: V2(`V2__drop_venue_use_venue_name.sql`, 프로시저 가드·재실행 가능)가 `performance.venue_name VARCHAR(100) NOT NULL`을 추가해 venue 이름을 백필하고 `venue_id`·FK·`venue` 테이블을 삭제. 버려진 정보는 venue.address·정식 등록 정보뿐(로컬 데이터). 공연장 이름은 공백 정리 후 1~100자(전각 통일·중복 판정 없음)
  - 공연 목록 검색은 **제목만**. `GET/POST /api/venues` 삭제. 응답은 `venueName`(문자열), 400 필드 오류 키 `venueName`. (공연 수정 PATCH는 5차 답변으로 제거됨)
  - **공연장 이름은 등록 후 수정 불가**: 티켓팅 링크 등록 시 공연 정보를 자동으로 읽어올 예정이라 수정이 필요 없고, 공연 진행 측 사정으로 공연장·회차·날짜가 바뀌면 사용자가 **관리자에게 수정 제안**(관리자 기능은 후속)
  - 등록 화면은 **3단계**: ①티켓팅 링크(중복 확인) ②공연 정보(제목·공연장 이름·회차, 지금은 직접 입력) ③확인·등록. 링크 입력 시 공연 이름·공연장·회차 등을 한꺼번에 미리 채워 '이 정보가 맞나요?'를 묻고 아니면 직접 수정 후 등록하는 **자동 입력은 다음 작업(미구현)**. seatmap-service 삭제로 요청·어댑터(SSRF 방어)는 Spring Boot 백엔드에 새로 만들고 이전 safe_fetch는 태그 `archive/seatmap-track-20261007` 참고
  - **5차 확정·구현 완료(2026-10-08, 미커밋, `feature/lock-performance-edit`)**: 공연은 등록 후 **아무도 수정하지 못한다**(제목·회차 추가/수정/삭제·공연 삭제 모두 불가, 무분별한 수정 방지). 수정은 추후 관리자 페이지의 '수정 제안'으로만(미구현·후속). PATCH/DELETE 공연, 회차 POST/PATCH/DELETE와 PerformanceSessionService 제거, 상세 화면 읽기 전용(백엔드 테스트 146건). 남은 공연 API: 등록·목록·상세·lookup. **회차도 링크에서 읽어오는 것으로 확정**(로직은 추후, 지금은 직접 입력). 이로써 4차의 '확인 필요' 2건(회차 자동 입력 범위, 등록 후 수정 범위)은 해소
  - **후속 작업 목록**: 링크 자동 입력(제목·공연장·회차) / **관리자 페이지 추가**(수정 제안 처리) / 교환 도메인 / 신고·차단 (로고 변경은 2026-10-09 완료 — 헤더 로고 이미지)
  - **추가금 매칭 규칙 교체**: 금액을 계산하지 않고 **추가금 유무만 확인**, 선택지 [추가금 X] / [상관없음](6차 답변으로 4유형 확장, 아래). 이전 '두 값의 합이 0 이하(이상)면 성립' 규칙은 **폐기 — 2026-10-08**
  - '예약' 확정(3차 답변; **'양쪽이 눌러야 예약'은 8차 답변(2026-10-09)으로 대체됨**, 아래 8차 항목): 채팅 합의 후 양쪽이 '이 사람과 교환할게요'를 눌러 예약하면 두 티켓이 잠겨 다른 채팅에서 같은 티켓을 또 예약 불가. 이후 양도 → 각자 완료. (취소 시 재매칭 불가 서술은 6차 답변으로 폐기)
  - ~~교환 완료 시 **내 Ticket의 좌석·회차를 새 자리로 갱신**~~(3차 답변, **8차 답변(2026-10-09)으로 대체됨**, 아래 8차 항목)하고 교환 이력에 (기존 자리) -> (바꾼 자리) 스냅샷 저장
  - 열·번 입력: 숫자는 `3~5` 범위로 입력하면 개별 좌석으로 펼치고 문자 열(`A열`)은 하나씩 추가. 이는 열과 번에만 해당, **구역은 별도 필수 입력**(펼침 대상 아님)
  - **공연 시작 후에도 교환(매칭·예약·완료) 가능**(공연 당일 현장 교환이 있으므로 시작 후 자동 마감 규칙 없음). 회차 당일 끝까지로 구체화(6차)
- **확정(사용자 6차 답변, 2026-10-08) — 교환 규칙**:
  - **추가금 4유형**: X(추가금 X, 지불 안 함) / ANY(상관없음) / POS(>0, 받아야만 교환) / NEG(<0, 낼 의향). 금액은 계산에 쓰지 않고 유형만 판정. **POS–POS 불성립, POS–X 불성립, 나머지 성립**(X–X·NEG–NEG는 2026-10-09 7차 답변으로 '성립' 확정). **추가금은 요청 단위가 아니라 희망 범위 단위**(범위마다 유형·금액이 다름; 7차 답변, V6). 금액은 후보 목록 참고용 표시. +는 받을 금액, −는 낼 수 있는 금액. 입력 UI는 [추가금 X]/[상관없음] + 금액 부호 입력
  - 예약으로 잠긴 티켓은 후보 목록에서 제외(예약 취소 시 복귀)
  - **취소 정책**: 매칭 취소 시 상태만 원상태로 복귀, 재매칭 불가 아님. 재매칭 불가는 상대를 **차단하거나 신고했을 때만**
  - 한쪽만 완료하고 방치: 자동 완료·취소 없음, 7일 경과 알림만, 양쪽 완료 전에는 누구든 취소 가능
  - 지난 회차: 회차 당일 끝(다음날 0시 KST)까지 등록·매칭 허용 후 자동 비활성. 티켓 내리기는 예약 중이 아니면 언제든 가능
  - 교환 완료 시 티켓 교체는 두 사람 모두 '교환 완료'를 누르는 순간 한 번에
- **확정(사용자 8차 답변, 2026-10-09; 문서만 정정, 코드는 별도 브랜치에서 구현 예정) — 상세는 `산출물/04_요구사항정의서/후속요구사항_제안알림_교환됨_공연정보입력.md` §3, §3A**:
  - **결정 1 (교환 완료 시 티켓 처리)**: 두 사람이 모두 '교환 완료'를 누르는 순간 한 트랜잭션에서 **기존 두 티켓을 `EXCHANGED`로 바꾸고 각자 새 자리 티켓을 INSERT**한다(소유자 그대로, 회차·구역·열·번은 상대의 기존 티켓 값). 3차 답변의 '내 티켓 좌석·회차 갱신'과 설계 문서 (g)의 '임시 INACTIVE 순서'는 **대체**. 매칭 행의 좌석은 교환 전 자리, 교환 후 자리는 새 티켓·`exchange_history`(old_ticket_id/new_ticket_id)에서 본다. 'COMPLETED 이후의 좌석은 교체 후 자리'는 더 이상 맞지 않다. 교환 완료된 매칭은 취소 불가, EXCHANGED 티켓은 내리기 불가
  - **결정 2 (예약 방식, 당근마켓 방식)**: **둘 중 한 명이 예약하면 RESERVED(두 티켓 잠금)**, **둘 중 한 명이 예약을 취소하면 CHATTING 복귀**(매칭 유지, 잠금 해제, 같은 쌍도 재예약 가능). 3차 답변의 '양쪽이 눌러야 예약'은 **대체**. `accept` 대신 `reserve`/`unreserve`, `a_reserved_at`/`b_reserved_at` 대신 `reserved_by_id`·`reserved_at`(가안)
  - **구현 상태 구분**: 위 두 규칙은 확정·**미구현**. 현재 코드는 양쪽 `accept` 방식이고 COMPLETED 전이·`exchange_history`·EXCHANGED·티켓 좌석 변경은 없다
  - **새 확인 필요**: Q-15(교환 완료 시 기존 요청 닫기·다른 CHATTING 자동 취소, 기본값 그렇게 한다), Q-16(예약으로 잠긴 티켓의 기존 다른 채팅, 기본값 유지), Q-17(예약 중 reject·cancel 막고 먼저 예약 취소, 기본값 막는다), Q-18(한쪽 완료 후에도 양쪽 완료 전 예약 취소 허용+완료 표시 초기화, 기본값 허용). 그 밖에 Q-1(후보 카드 같은 쌍 RESERVED 뱃지)·Q-5(알림 종류)는 해당 작업 착수 전 확인. 원본 산출물 04·05 반영 대기
- **확인 필요(사용자 확인 중, 구현 전 확인)**: 신고용 최소 관리자 기능 범위(신고 착수 시). (7차 답변 2026-10-09로 추가금 X–X·NEG–NEG 판정은 '성립' 확정·해소. 6차 답변으로 금액 표시용 유지·충돌 판정, 취소 후 재매칭, 한쪽 완료 방치, 지난 회차 처리는 해소)
- **폐기된 기본안(2026-10-08)**: 구역 자동완성, 펼침 300석 상한, 지정석·1매만, 후기·신뢰도 우선, 같은 회차만이 기본인 회차 조건
- **현재 코드 상태 (chore/remove-seatmap-track 기준)**: 인증·공연/회차 등록·조회(공연장은 텍스트)와 /api/users/me는 구현됨. 좌석표 코드·seatmap-service·빈 스켈레톤(교환·채팅·후기 등)은 삭제됐고(티켓 컨트롤러·서비스는 2026-10-08 새로 구현)
  좌석표 코드는 git 태그 `archive/seatmap-track-20261007`(master d199b36)에 보관. DB는 V1~V7(V6 `exchange_extra_per_range`·V7 `exchange_request_soft_delete`는 2026-10-09 7차 답변, 브랜치 `feature/range-extra` 미커밋, 컬럼 변경만이라 테이블 수 불변; V5 2026-10-08 exchange_match·exchange_ticket_lock; V2 2026-10-08 venue 삭제, V3 2026-10-08 ticket 좌석 컬럼, V4 2026-10-08 교환 희망 4개 테이블; 브랜치 `feature/exchange-want` 미커밋): users·performance·performance_session·ticket + exchange_request·exchange_want_range·exchange_want_seat·exchange_want_session + exchange_match·exchange_ticket_lock 10개 테이블(ticket에 seatmap_id 없음, 구역·열·번 label+key·status, performance.venue_name 텍스트),
  엔티티는 User·Performance·PerformanceSession·Ticket·ExchangeRequest·ExchangeWantRange·ExchangeWantSession과 enum UserRole·TicketStatus·ExchangeRequestStatus·ExtraType(Venue·VenueStatus 삭제), 백엔드 테스트 254건(교환 희망 등록 후), docker-compose는 mysql·backend·frontend 3개.
  **티켓 등록은 구현 완료(2026-10-08)**: `POST /api/tickets {sessionId, zone, row, col}`, `GET /api/tickets/me`, `DELETE /api/tickets/{id}`(소프트 삭제=INACTIVE, 본인만, 남의 티켓 404, 멱등 204, 교환 요청 닫기는 V4에서 구현, 예약 잠금 409는 매칭 구현 때). 열·번은 숫자/문자 허용(숫자 1~999 설정값), 정규화 NFKC·공백 제거·대문자·앞 0 제거·끝의 '열'/'번' 제거, 제어·제로폭 문자 거부. 중복 활성 좌석 409(`SEAT_ALREADY_REGISTERED`/`MY_TICKET_ALREADY_REGISTERED`), 사용자당 활성 20개 상한 422 `TICKET_LIMIT_REACHED`, 회차 당일 끝(다음날 0시 KST) 이후 등록 400(`sessionId`). 구역 자동완성 API는 만들지 않는다(구역은 필수 텍스트). 나머지 교환 도메인(희망 범위·매칭·채팅, 후기는 만들지 않기로 함)은 구현 전이고 V4 설계 확정안만 있음. 다음 작업은 V4
  **교환 희망 조건 등록은 구현 완료(2026-10-08, `feature/exchange-want`, 미커밋)**: `POST /api/exchange/requests {ticketId, extraType(X/ANY/POS/NEG), extraAmount, wantSessions[{sessionId,priority}], ranges[{zone,rowFrom,rowTo,colFrom,colTo}]}`, `GET /api/exchange/requests/me`, `PATCH/DELETE /api/exchange/requests/{id}`. 서버 안전 상한 5,000석·50범위(`exchange.want.*`, 초과 422 `WANT_SEAT_LIMIT_EXCEEDED`/`WANT_RANGE_LIMIT_EXCEEDED`, count/limit 포함), 겹침은 합집합 허용, 문자 열은 하나씩, 자기 좌석 포함은 희망 회차가 내 티켓 회차 하나뿐일 때만 422 `WANT_INCLUDES_OWN_SEAT`(다른 회차가 하나라도 있으면 같은 위치도 허용 — 사용자가 별도 결정 없이 추천안 채택, 이의 시 변경 가능), 티켓당 요청 1개 409 `REQUEST_ALREADY_EXISTS`, 남의 요청·티켓 403(없는 id 404), 추가금은 요청 단위(**7차 답변으로 범위 단위로 변경 — 아래 '범위별 추가금·요청 소프트 삭제'**; 이 항목의 요청 필드 `extraType/extraAmount`·`REQUEST_ALREADY_EXISTS`·요청 하드 삭제는 V6·V7 이전 기록). 티켓을 내리면 해당 요청이 CLOSED, 이후 수정은 422 `TICKET_NOT_ACTIVE`(예약 잠금 409·'진행 중 제안 409'는 매칭 구현 때 `ensureCanDeactivate`/`ensureNoActiveProposal` 훅에서, 지금은 훅만). 테스트 254건, 5,000석 POST 약 156ms. 지난 회차 마감 정책은 요청에도 적용(내 티켓 회차 마감이면 422 `SESSION_CLOSED`, 희망 회차가 마감이면 400 `wantSessions[i].sessionId`). 열·번의 부호 붙은 정수형(`-3`, `+3`, U+2212)은 400 거부(티켓·희망 범위 공통). 상한(5,000석) 판정은 겹침 포함 합이 아니라 합집합 기준이며 응답 `count`와 `wantSeatCount`의 의미가 같다. 잠금 순서 규약: 항상 티켓→요청 순, 요청만 잠그는 update/delete 경로에서는 티켓을 잠그지 않는다. 알려진 한계: 응답의 열·번 범위는 정규화 값(원문 표기 복원 불가)뿐('같은 자리의 다른 회차 교환' 한계는 해소). 매칭·잠금은 이후 V5에서 구현, 채팅·이력·차단은 미구현(V8 이후).
  **매칭 후보 조회는 구현 완료(2026-10-08, `feature/exchange-candidates`, 미커밋)**: `GET /api/exchange/requests/{id}/candidates?page&size`(기본 20, 최대 100). 판정은 같은 공연·양방향 희망 회차·양방향 희망 좌석·추가금 유형 호환표(POS–POS·POS–X·X–POS 불성립, X–X·NEG–NEG는 성립, 7차 답변으로 확정)·상대 요청 OPEN·티켓 ACTIVE·마감 지난 회차 제외·내 요청/같은 사용자 제외. 정렬은 내 희망 회차 priority → 상대 요청 최신순(점수·랭킹·신뢰도 없음, 같은 회차 우선 미적용). 응답: 상대 좌석·회차·닉네임·내/상대 추가금 유형·금액·`settlementHint`(POS–NEG이고 겹치면 {min,max}, 참고값), 신뢰도 필드 없음. **(당시 미적용이던 제외 조건 중 같은 쌍 열린 채팅·예약 잠금 티켓 제외는 V5 제안·수락 구현에서 `additionalExclusions()`에 적용됨; 차단 사용자 제외만 `user_block`이 없어 미적용)** 스키마 변경 없음, SQL에 `STRAIGHT_JOIN`(옵티마이저가 상대 요청 테이블을 풀스캔하는 문제 방지), 요청 1,000건·희망 좌석 83만 행 EXPLAIN 전부 const/ref/eq_ref, 응답 중앙값 100건 규모 28~43ms·1,000건 규모 42~69ms. 실제 MySQL 통합 테스트 `ExchangeCandidateQueryTest`는 `SEATSWAP_IT_*` 환경변수가 있을 때만 실행(기본 skip, 개발 DB 보호 장치), 기본 테스트 287건 중 17 skip. 후보가 수천 건 넘으면 keyset 페이징·총계 생략 필요(후속).

---

## 1. 서비스 개요 (산출물/03)

같은 공연의 티켓을 보유한 사람들끼리 좌석을 맞교환하거나, 좌석 등급 차이에 따른
추가금액을 주고받으며 자리를 교환하는 개인 포트폴리오 웹 서비스.

- **핵심 시나리오**: 단순 교환(1:1 맞교환) / 차액 거래(등급 차이 시 추가금 지불). 매칭은
  (2026-10-07 변경) **조건이 맞는 상대를 자동으로 찾아 후보로 보여주고 양쪽 수락으로 확정**(점수·랭킹·추천은 하지 않음, 0절). 교환 범위는 **같은 공연 단위**(다른 회차끼리도 가능, 2026-10-07 결정, 7절 참고).
- **타겟 플랫폼**: 모바일 웹(반응형, 필요 시 PWA). 네이티브 앱 제외.
- **제외 범위**: 실결제(PG) 연동 없음, 티켓팅 사이트 공식 API 연동 없음, 좌석 실시간 재고 연동 없음.

## 2. 기술 스택 및 선정 이유 (산출물/03)

| 구분 | 기술 | 선정 이유 요약 |
|---|---|---|
| Frontend | React, TypeScript, Vite | 기존 숙련도, 인터랙티브 UI(좌석맵/채팅) 적합, 타입 안전성, 채용시장 범용성 |
| Backend | Spring Boot, Security(JWT), JPA, WebSocket(STOMP) | 기존 Java/Spring 경험, 관계형 데이터 적합(현재 10개 테이블, 차단·채팅·이력 등 나머지 교환 도메인은 V8 이후 추가 예정), Security/JWT 생태계 성숙, WebSocket 내장 |
| DB | MySQL | FK 관계 많은 구조라 RDBMS 적합, JPA 호환성, 기존 사용 경험 |
| 이미지 인식 서버 | FastAPI, OpenCV, Tesseract | Python이 이미지/OCR 생태계 중심, 메인 서버와 책임 분리, 비동기 처리에 강함 |

### backend와 seatmap-service를 분리한 이유 (산출물/03 8.1절과 동일 내용)
- **책임 성격이 다름**: backend는 인증·거래/매칭 상태 관리 같은 트랜잭션 중심 로직, seatmap-service는
  이미지 한 장을 좌표 JSON으로 바꾸는 단발성 변환 작업. 한 서버에 섞으면 핵심 비즈니스 로직과
  이미지 전처리 코드의 경계가 흐려짐
- **장애 격리**: 외부 사이트에서 가져온 이미지를 다루는 특성상 예측 어려운 입력(비정상 이미지,
  메모리 급증 등)에 노출되기 쉬움. 분리해두면 좌석 인식 쪽 문제가 로그인·결제 등 핵심 기능에
  영향을 주지 않음
- **포트폴리오 관점**: 책임 분리 + 서비스 간 REST 통신 구조를 실제로 구현한 경험 자체가 설명 거리가 됨
- **트레이드오프**: 서비스가 2개로 늘어 배포/통신 복잡도가 증가함 — docker-compose.yml로 한 번에
  띄우도록 구성해 완화함

### backend / seatmap-service를 별도 서비스로 분리한 이유

- **언어 선택**: OpenCV·Tesseract 등 이미지 처리/OCR 생태계가 Python 중심으로 성숙해 있어, Java로 구현하는 것보다 훨씬 효율적
- **책임 분리**: backend는 트랜잭션 중심(인증, 거래/매칭 상태 관리, 데이터 정합성), seatmap-service는 단발성 변환 작업(이미지 1장 → 좌표 JSON). 한 서버에 섞으면 핵심 비즈니스 로직과 이미지 전처리 코드의 경계가 흐려짐
- **장애 격리**: 외부 사이트에서 가져온 이미지를 다루는 특성상 예측 어려운 상황(비정상 이미지, 메모리 사용량 급증 등)이 생길 수 있음. 분리해두면 좌석 인식 쪽 문제가 로그인·결제 등 핵심 기능에 영향을 주지 않음
- **포트폴리오 관점**: 책임을 분리한 마이크로서비스 구조(서비스 간 REST 통신)를 실제로 구현한 경험 자체가 설명 포인트가 됨
- **트레이드오프**: 서비스가 2개로 늘어 배포·통신 복잡도가 증가함 — `SeatSwap/docker-compose.yml`로 전체를 한 번에 띄우도록 구성해 완화함

## 3. 핵심 기능 (MVP) — 산출물/03, 산출물/04 FR 요약

| 기능 | 설명 |
|---|---|
| 회원/인증 | 이메일 회원가입·로그인, JWT |
| 공연 등록 / 좌석맵 등록(좌석맵은 동결) | 공연 등록(링크 입력 시 제목·공연장·날짜 범위만 미리 채움, 회차는 직접 입력), 좌석맵은 사용자 이미지 업로드/이미지 주소 입력 (2026-10-07 변경, 링크 자동 수집 불가) |
| 좌석 자동 인식 (동결) | OpenCV+OCR로 좌표·행/열 번호 자동 추출 |
| 좌석맵 터치 선택 (동결) | SVG 오버레이로 본인 좌석 선택 |
| 오류 신고/보정 (동결) | OFFICIAL 좌석표: 동일 정정 2건 이상 시 자동 반영, 1건은 검토 대기 / DRAFT: 수정 즉시 반영 + 수정 로그 |
| 교환 요청/매칭 | 단순 교환 또는 차액 거래. 본인 좌석 1개 + 희망 좌석 범위·희망 회차(우선순위)·추가금(유형 X/ANY/POS/NEG, 6차 답변) 입력 → 조건이 맞는 상대를 후보로 → 채팅 → 각자 수락 → 확정(2026-10-08 확정, 0절). 같은 공연이면 회차 구분 없이 가능하나 매칭은 희망 회차끼리만. 추가금은 유형만 판정(합 규칙 폐기, POS–POS·POS–X 불성립), 남은 확인 항목은 0절 '확인 필요' |
| 실시간 채팅 | WebSocket(STOMP) |
| 거래 상태 관리 | 제안→수락→교환완료 |
| 리뷰/신뢰도 | **만들지 않기로 함(2026-10-08)**. 대신 사용자 신고를 교환 핵심 흐름 이후 추가(필수) |

전체 FR/NFR/UC 상세 번호와 우선순위·담당자는 `산출물/04_요구사항정의서` 참고
(팀 프로젝트 양식: 구분/서비스/기능명/기능설명/개발상태/개발우선순위/담당자/선행기능/비고).

## 4. 좌석맵 인식 파이프라인 (산출물/03, seatmap-recognition-pattern 스킬과 동일 내용) — 동결(2026-10-07), 재개 시 참고

> **좌석표 트랙 동결(2026-10-07), 코드는 git 태그 `archive/seatmap-track-20261007`에 보관, 아래는 보존용 기록이다.** 이 절과 7절에 나오는 `feature/*` 브랜치는 모두 master에 병합된 뒤 삭제됐고(PR #14~#20), 이후 chore/remove-seatmap-track에서 좌석표 코드·seatmap-service를 삭제했다. 아래 '구현 완료'·'미병합' 같은 표현은 당시 시점의 기록이다.

1. 이미지 확보 — (2026-10-07 변경) 사용자가 올린 이미지(/recognize) 또는 입력한 이미지 주소(/recognize-url). 원본은 저장하지 않고 좌표만 저장
2. 좌석 블록 검출 — 배경(흰색 계열) 제외 + connectedComponentsWithStats. 색상/판매상태 매핑 안 함
3. 행 번호 인식 — y좌표 클러스터링 + Tesseract OCR
4. 열 번호 부여 — 행 내 x좌표 순 정렬, 통로를 건너도 번호를 이어씀(aisleMode=continue 기본, 2026-10-07). 실제와 다르면 사용자가 수정 요청·수정
5. 오류 보정 — OFFICIAL 좌석표는 동일 정정 2건 이상 시 자동 반영

### 좌석맵 확보·좌석표 흐름 (2026-10-07 결정, CLAUDE.md와 동일)
- 링크 스크래핑 불가: 멜론·YES24·티켓링크는 공개 페이지에 좌석맵이 없고(예매·보안문자 뒤), 인터파크는 robots.txt가 전면 금지. 로그인·캡차 우회·내부 API 조사는 하지 않음
- 좌석표 상태: 공연장 정식 등록 전에는 사용자 이미지 인식 결과를 `DRAFT`(공연장당 하나, 재사용·수정)로 저장 → 관리자가 공연장·공연을 정식 등록하면 `OFFICIAL`, 이후 그 공연장 공연에 자동 연결
- 좌석표 세부(2026-10-07 사용자 결정): DRAFT는 공연장 안에서 **구역(zone)별 하나**(구역별 여러 개 허용), OFFICIAL은 지금은 여러 개 허용 후 **추후 공연장당 1개로 제한 예정**. 정식 등록은 좌석표 등록 시에만 가능해 Venue VERIFIED와 좌석표 OFFICIAL은 항상 함께 바뀜(공연에는 정식 상태 없음, Venue.status UNVERIFIED/VERIFIED만).
- 티켓은 좌석표 없이 먼저 등록(`ticket.seatmap_id` NULL 허용, `image_url` 삭제), 교환글 등록 시 DRAFT 좌석표 업로드. OFFICIAL은 사용자 직접 수정 불가(정정 신고로만), 관리자는 직접 수정 가능. 제재는 SEATMAP_EDIT(수정·신고 정지)/ACCOUNT(계정 정지) 두 종류, 신고(abuse_report)는 항상 로그, 회원탈퇴는 익명화(FK 유지)
- 좌석맵 서비스 응답의 좌석에 안정적 식별자 `uid`(예 s0001, 검출 공간 순서 기준 결정적, 행/열 보정·aisleMode 무관, 하위 호환)를 추가 — 수정 로그·정정 신고가 좌석을 가리키는 키
- 좌석 표기(2026-10-07): 한국 좌석 체계의 '열'(앞에서부터 1열, 뒤로 갈수록 커짐)과 '번'(한 열 안에서 왼쪽→오른쪽 1번, 2번…). 화면은 'N열 M번', 구역(층)이 둘 이상이면 '구역 N · M열 K번'(필드명 row/col 유지). 좌석에 `section`(위에서부터 1,2,3, 열 번호는 구역별 재시작)이 있고 응답에 `sections`가 추가됨(하위 호환). 백엔드는 section을 보존(없으면 1, 1~50 검증)
- 임시 기능 TEMP-DRAFT-DELETE(테스트용, 제거 예정): `DELETE /api/seatmaps/{id}`, DRAFT만, `SEATMAP_DEV_DRAFT_DELETE=true`인 동안 로그인 사용자 누구나 삭제(false면 작성자·ADMIN만, 그 외 403), 'TODO: 임시 기능' 주석으로 위치 표시
- DRAFT 선점·스팸 제한(2026-10-07): 공연장당 좌석표(구역) 상한 기본 20(422 `ZONE_LIMIT_REACHED`, ADMIN도 적용), 사용자당 24시간 내 등록 기본 10건(429 `DAILY_LIMIT_REACHED`, ADMIN 제외), 설정 `SEATMAP_MAX_ZONES_PER_VENUE`·`SEATMAP_DAILY_LIMIT_PER_USER`. 삭제 권한은 작성자·ADMIN(OFFICIAL 불가, 참조 시 409, 작성자 NULL 행은 ADMIN만), 응답에 `canDelete`. 한계: 삭제 후 재업로드로 일일 제한 우회 가능(V3 수정 로그에서 보완)
- DRAFT는 확인용으로만 표시. 교환 매칭은 본인 좌석 정보 + 희망 좌석 범위로 하고, 좌표 기반 선택·매칭은 OFFICIAL에서만
- 모든 수정은 로그(누가·언제·전후). 악의적 수정은 신고나 관리자 확인이 있을 때만 제재(자동 제재 없음). 관리자는 DB에서 ADMIN 직접 부여로 시작
- 좌석표 수정·정정 신고 구현(2026-10-07, Flyway V3, `feature/seatmap-edit-log`, 당시 미병합 → PR #20으로 병합·이후 코드 삭제): `PATCH /api/seatmaps/{id}/seats`(expectedVersion·reason 필수, changes는 ROW_LABEL/COL_LABEL) — DRAFT는 로그인 누구나 즉시 반영(USER_EDIT), OFFICIAL은 ADMIN만(ADMIN_EDIT, 일반 사용자 403). 버전 충돌 409 `VERSION_CONFLICT`, 422 `UNKNOWN_SEAT`/`DUPLICATE_SEAT_NUMBER`/`NO_CHANGE`/`DUPLICATE_CHANGE`(중복 번호는 변경 좌석의 최종 (구역,열,번)만 검사). `POST /api/seatmaps/{id}/corrections`는 OFFICIAL만(DRAFT 409): 서로 다른 신고자 2명(threshold 설정, 최소 2) 이상이면 자동 반영(CORRECTION_APPLIED, actor NULL, 같은 좌석·필드의 다른 PENDING은 SUPERSEDED), 1건이면 PENDING, 반영하면 번호가 중복되면 보류+review_note. 관리자 직접 수정도 같은 좌석·필드의 PENDING을 SUPERSEDED로 바꿈. note는 저장하지 않음. `GET /api/seatmaps/{id}/revisions`·`/{revisionId}`는 ADMIN만. 최초 인식 시 RECOGNIZED 로그 1건
- 수정·신고 남용 방지: 사용자당 24시간 신고 30건(429 `CORRECTION_LIMIT_REACHED`, ADMIN 제외), 사용자당 PENDING 50건(429 `PENDING_LIMIT_REACHED`), 수정 요청 24시간 200건(429 `EDIT_LIMIT_REACHED`, ADMIN 제외), 락 대기 실패 503 `BUSY`. 수정·반영은 좌석표 행 FOR UPDATE + @Version
- 프론트: 번호 수정 모드(직접 입력, 같은 열 번호 일괄 이동, 열 번호 변경, 변경 대기 목록·미리보기·사유 입력 후 한 번에 저장, 변경 좌석은 점선+사선 빗금), 오류 신고(OFFICIAL, 열·번 순차 POST·409 건너뜀, note 없음), 이탈 경고는 beforeunload+confirm(BrowserRouter라 useBlocker 불가), role은 /users/me로 UI 분기(서버가 최종 판정), 목록에 N석 표시
- V3 한계·미결정: 수정 로그가 있는 DRAFT 삭제 정책(현재 RECOGNIZED 로그만 있으면 native delete로 로그째 삭제, 수정·정정 로그가 있으면 409 — 로그 보존 원칙과 충돌, soft delete는 V4 설계 후보, 임시 플래그 기본 true는 운영 전 false로), 삭제 후 재등록으로 일일 한도 우회, 관리자의 PENDING 검토·반려 API 없음(REJECTED 미사용), PROMOTED/REVERTED 로그 기록 코드 없음(관리자 정식 등록 서비스에서 남겨야 함), 수정 로그 조회 화면 없음, 정정 신고는 사실상 OFFICIAL이 아직 없어 단위 테스트·임시 DB로만 검증
- 이미지 보관: 원본·주소 저장 안 함, 좌표는 원본 픽셀 기준, 화면은 SVG. 필요 시 핫링크/서비스 보관으로 확장(컬럼은 Flyway로 추가)
- 링크 자동 입력: 공개 페이지(JSON-LD·og)에서 제목·공연장·날짜 범위만(회차 시각은 직접 입력), 자동 등록 없음, 인터파크 제외

실측 검증(과거 기획 단계): 딥러닝 없이 약 207~210개 좌석 블록 정확 검출 확인됨 (실제 멜론티켓 이미지 기준). 2026-10-07 feature/seatmap-service는 합성 이미지 정확도 100%이나 실제 이미지 검증은 아직 못함.

## 5. 일정 단계 (산출물/05_WBS 요약)

1. 기획 — 완료
2. 환경설정 — Spring Boot/React 스켈레톤, DB 스키마 (완료)
3. 회원/인증
4. 좌석맵 파이프라인
5. 공연/티켓 관리
6. 교환 매칭
7. 채팅
8. 거래/신뢰도
9. 배포
10. 문서화(완료보고서)

세부 날짜/기간은 `산출물/05_WBS` xlsx 참고.

## 6. 데이터 모델 (산출물/08_ERD 요약)

**현재 기준선: V1~V7, 10개 테이블** — User(users, role USER/ADMIN), Performance(`venue_name` 텍스트 NOT NULL 100자, V2), PerformanceSession, Ticket(V3로 좌석 컬럼 확장), ExchangeRequest(미삭제 요청은 티켓당 1개, status OPEN/CLOSED/DELETED·deleted_at·live_flag; 추가금 컬럼은 V6에서 제거)·ExchangeWantRange(입력 범위 + 범위별 extra_type X/ANY/POS/NEG·extra_amount, V6)·ExchangeWantSeat(펼친 좌석, PK request+zone/row/col key, 범위의 추가금을 비정규화해 보유, V6)·ExchangeWantSession(희망 회차+priority) (V4, 자식 3개는 request ON DELETE CASCADE), ExchangeMatch(request_a/b·ticket_a/b·user_a/b FK, status CHATTING/RESERVED/COMPLETED/CANCELED, a/b_reserved_at·a/b_completed_at, canceled_by_id(NULL=시스템)·canceled_at, 생성 컬럼 request_low/high·open_flag로 같은 요청 쌍 열린 매칭 1개 UK, CHECK ck_exchange_match_distinct_tickets·ck_exchange_match_canceled, FK 인덱스는 단일 컬럼)·ExchangeTicketLock(PK ticket_id, match_id, 두 FK ON DELETE CASCADE) (V5). **V6**: `exchange_want_range`·`exchange_want_seat`에 범위별 추가금(extra_type/extra_amount, CHECK에 `extra_amount IS NOT NULL` 명시)을 두고 `exchange_request`의 추가금 컬럼 제거, `exchange_match`에 추가금 스냅샷 4컬럼(a/b_extra_type, a/b_extra_amount). **V7**: `exchange_request`에 status DELETED·`deleted_at`(소프트 삭제), 생성 컬럼 `live_flag`+`uk_exchange_request_live_ticket`(미삭제 요청만 티켓당 1개). 차단·채팅·이력은 V8 이후 예정. (V1의 Venue는 2026-10-08 V2로 삭제됨)
Ticket은 `performance_session_id`·`user_id`, 텍스트 좌석 `zone_label/zone_key`·`row_label/row_key`·`col_label/col_key`(표시용+정규화 키), `status`(ACTIVE/INACTIVE), 생성 컬럼 `active_flag`, `created_at/updated_at`를 가진다(V3). 유일 제약 `uk_ticket_active_seat`(회차·구역·열·번·active_flag)로 활성 티켓만 좌석당 1개, `idx_ticket_user_status`. `seatmap_id`는 없다. 교환·채팅 테이블은 V4(설계 확정안: `산출물/08_ERD/exchange-schema-design.md`)로 추가한다. 로컬 DB에 V3가 이미 적용되어 있으며 파일을 수정하면 체크섬 불일치로 기동 불가(롤백은 `docker compose down -v`).
아래는 방향 전환 전(V1 12개 → V3 14개 → V4 예정 16개)의 보존용 기록이며 이전 V1~V3와 좌석표 테이블은 삭제되어 태그 `archive/seatmap-track-20261007`에 보관된다:
V1 12개 = User, Venue, Performance, PerformanceSession, Ticket, SeatMapLayout, SeatCorrection, ExchangeRequest, ExchangeMatch, ChatRoom, Message, Review (V3에서 `seat_map_revision`·`seat_map_revision_item` 추가, V4 `abuse_report`·`user_sanction`은 예정만 있었음).
(2026-10-06 공연 회차 `PerformanceSession` 추가로 11 → 12였음.)

주요 관계 (현재 10개 테이블에 있는 것: ExchangeRequest 1:N ExchangeMatch(a측/b측), Ticket 1:N ExchangeMatch(a/b), User 1:N ExchangeMatch(a/b), ExchangeMatch 1:N ExchangeTicketLock(최대 2), Ticket 1:0..1 ExchangeTicketLock, Performance 1:N PerformanceSession, User 1:N Performance, User 1:N Ticket, PerformanceSession 1:N Ticket, Ticket 1:1 ExchangeRequest, ExchangeRequest 1:N WantRange/WantSeat/WantSession, PerformanceSession 1:N WantSession. 아래 Venue 관련 항목은 V2로 삭제된 과거 설계. 나머지는 방향 전환 전 설계의 보존용 기록):
- Venue 1:N SeatMapLayout / 1:N Performance
- Performance 1:N PerformanceSession (회차: 날짜·시간), User 1:N Performance (등록자)
- SeatMapLayout 1:N SeatMapRevision(수정 로그 헤더, append-only) 1:N SeatMapRevisionItem(좌석·필드별 전후, `seat_uid` 기준) (V3)
- SeatCorrection(V3 개편: 1신고=1행, status PENDING/APPLIED/REJECTED/SUPERSEDED, `vote_count` 삭제, 대기 중 중복 신고는 생성 컬럼 `pending_key` UNIQUE로 방지)은 `applied_revision_id`로 반영된 수정 로그를, SeatMapRevisionItem은 `correction_id`로 만든 신고를 가리킴. `seat_map_layout`에 `seat_count`·인덱스 (created_by, created_at) 추가
- SeatMapLayout 1:N SeatCorrection / 1:N Ticket (V2: Ticket의 seatmap_id는 NULL 허용, DRAFT는 공연장+구역당 1개)
- User 1:N Ticket, PerformanceSession 1:N Ticket (Ticket은 공연이 아니라 회차를 참조)
- Ticket 1:1 ExchangeRequest (교환 후보·매칭 검증은 회차가 속한 **공연이 같은지** 기준, 회차까지 같을 필요 없음) — 2026-10-07: 희망 범위·희망 좌석 자식 테이블 추가 예정(가안, erd-conventions 스킬 참고)
- ExchangeRequest 1:N ExchangeMatch (A측/B측)
- ExchangeMatch 1:1 ChatRoom, ChatRoom 1:N Message
- ExchangeMatch 1:N Review

공연·회차·공연장 규칙 (2026-10-06 결정, 공연장·수정 부분은 2026-10-08 V2로 정정 — 아래 '과거' 표기 항목은 삭제된 설계):
- 공연 등록: 로그인 사용자 누구나, 티켓팅 링크(sourceUrl) 입력. 중복 판정은 링크 정규화 값 `source_key` unique
  (사이트별 상품 ID `{site}:{productId}`, 미지원 사이트는 일반 URL 정규화)
- 회차: 공연 1:N, `starts_at` 분 단위, (공연, 일시) unique. **좌석 교환은 같은 공연 안이면 회차가 달라도 가능**(2026-10-07 변경, 이전에는 같은 회차끼리만으로 기록됨). 교환 조건은 좌석 위치 + 회차 일시이고, 다른 회차끼리 교환하면 교환 후 티켓의 회차가 바뀐다
- 공연장: **현재는 `performance.venue_name` 텍스트(필수 1~100자, 등록 후 수정 불가, 중복 판정 없음)**. (과거, 삭제됨: 검색 후 선택·없으면 추가, `normalized_name` unique. SeatMapLayout은 Venue 단위 재사용(NFR-03)은 좌석표 동결로 보존용)
- 수정/삭제: **없음(2026-10-08 5차 답변)**. 공연은 등록 후 아무도 수정·삭제하지 못하고 회차 추가·수정·삭제도 없다. 변경은 추후 관리자 페이지의 '수정 제안'으로만(후속).
  (과거 규칙, 폐기: 공연은 등록자만 수정·삭제, 회차 추가는 누구나, 회차 수정·삭제는 등록자만 + 티켓 0건일 때)

상세 다이어그램은 `산출물/08_ERD/ERD.png` (및 `erd.dot` 소스) 참고.

## 7. 현재 진행 상태

- **2026-10-08 2차 답변 반영**: venue 테이블 삭제 확정(코드·DB 반영 대기, 범위 확인 필요), 추가금 부호 정정, 후보 목록 선택·사용자 차단·재매칭 불가·동시 채팅/예약 금지, 교환 완료 방식·교환 이력을 0절에 반영하고 '확인 필요'를 새로 정리했다.
  산출물 04·05·08 원본 반영은 대기(docx/xlsx 미수정). 다음: venue 삭제 범위 확인 → V2 마이그레이션·코드 정리 → 교환 스키마 설계(차단·이력 포함)
- **2026-10-08 3차 답변 반영**: venue 삭제 범위 확정, 추가금 매칭을 유무([추가금 X]/[상관없음])로 교체('합 ≤ 0' 폐기), 예약·완료 시 Ticket 갱신·열/번 입력(구역 필수)·공연 시작 후에도 교환 가능을 0절에 반영하고 '확인 필요'를 재정리했다. 산출물 04·05·08 원본 반영은 대기(docx/xlsx 미수정). 다음: venue 삭제 코드·V2 마이그레이션 → 교환 스키마 설계 → 티켓 등록 → 매칭·채팅 → 신고·차단
- **2026-10-08 교환 희망 조건 등록 구현(미커밋, `feature/exchange-want`)**: Flyway V4 4개 테이블·API 4종·안전 상한·티켓 내리기 시 요청 CLOSED(위 현재 코드 상태 참고). 테스트 254건(리뷰 반영 후). 다음은 V5 이후(차단·예약 잠금·채팅·이력; 후보 조회는 이후 구현됨). erd.dot은 4개 테이블을 현재로 승격(graphviz 없어 렌더링 불가, 문법만 점검). 산출물 04·05 원본 반영 대기.
- 매칭 조회 API: 매칭 조회 API 구현 완료(2026-10-08, 미커밋, `feature/exchange-ui`) — 새 마이그레이션 없음: `GET /api/exchange/matches/me?role=SENT|RECEIVED|ALL&status=...&page&size`(내 매칭 목록)와 `GET /api/exchange/matches/{id}`(상세; 비참여자는 404). 응답 필드: id, status, mySide, role, myRequestId, myTicketId, mySeat, counterpartRequestId, counterpartTicketId, counterpartSeat, counterpartNickname, myExtraType/Amount, counterpartExtraType/Amount, myReservedAt, counterpartReservedAt, canceledBy, canceledAt, createdAt, updatedAt. L8 N+1 해소(조회 쿼리 일괄 조인). ~~COMPLETED 이후의 좌석은 교체 후 자리이며 교환 전 자리는 후속 교환 이력 스냅샷에서 본다.~~ (8차 답변(2026-10-09)으로 정정: 매칭 행의 좌석은 항상 교환 전 자리, 교환 후 자리는 새 티켓·교환 이력에서 본다) 백엔드 테스트 기본 408건·환경변수 포함 425건(이전 384/401건).
- 교환 프론트: 교환 프론트 화면 5개 구현 완료(2026-10-08, 미커밋, `feature/exchange-ui`, 명령 7): `/tickets/new` 티켓 등록(FR-03), `/tickets` 내 티켓(내리기·교환 조건 진입), `/tickets/:ticketId/exchange` 희망 조건 폼(범위 카드: 구역·열·번 범위, 요청 단위 희망 회차 우선순위, 추가금 4유형 라디오, 총 N석 합집합 미리보기와 5,000석·50범위 경고), `/exchange/requests/:requestId/candidates` 후보 목록·제안하기(신뢰도 표시 없음, 참고용 금액 구간), `/exchange/matches` 내 매칭(보낸/받은 탭, 예약 동의·거절·취소; 채팅·교환 완료 버튼은 '준비 중'. 예약 동의 UI는 현재 구현 사실이며 8차 답변으로 예약하기/예약 취소 방식으로 바뀔 예정). 구역 자동완성은 만들지 않았다(확정). 헤더에 '내 티켓'·'내 매칭', 마이페이지 링크, 공연 상세에 '이 공연 티켓 등록' 버튼. 프론트 에이전트가 정한 항목: 열 끝·번 끝을 비우면 시작값으로 채워 전송, 상한은 하드코딩(서버 count/limit 우선), 마감 회차는 선택 불가. 알려진 한계: 브라우저 수동 점검·실서버 연동은 아직 하지 않았다. 다음 작업: 채팅·교환 완료·교환 이력·차단/신고(V8 이후). (요청 삭제 정책·낯선 제안 문제는 7차 답변으로 결정 완료 — 요청 소프트 삭제, 수정·삭제 시 CHATTING 매칭 시스템 취소 후 수정 허용. 추가금 라디오는 범위 카드마다로 바뀜.)
- **2026-10-08 매칭 후보 조회 구현(미커밋, `feature/exchange-candidates`)**: 후보 조회 API·STRAIGHT_JOIN·실측 EXPLAIN(위 현재 코드 상태 참고). 기본 테스트 287건 중 17 skip. 다음은 V5(차단·같은 쌍 채팅·예약 잠금 테이블) + 후보 제외 조건 추가(`additionalExclusions()`). 남은 확인: X–X·NEG–NEG 판정. 산출물 04·05 원본 반영 대기.
- **2026-10-08 제안·수락(매칭·예약) 구현(미커밋, `feature/exchange-propose-accept`)**: 사용자 결정(명령 6)으로 '요청당 제안 1개→수락' 대신 확정 흐름(후보 선택→채팅 여러 개 동시→양쪽 예약→각자 완료)에 매핑. Flyway V5(`exchange_match`·`exchange_ticket_lock`만; 현재 10개 테이블). API: `POST /api/exchange/requests/{id}/proposals {targetRequestId}`(매칭 CHATTING 생성, 한 요청에 여러 개 동시 가능, 재검증 실패 422 `NOT_A_CANDIDATE`, 같은 쌍 열린 매칭 409 `MATCH_ALREADY_OPEN`), `POST /api/exchange/matches/{id}/accept`(호출자 쪽 예약 동의, 양쪽이 누르면 RESERVED + 두 티켓 잠금 한 트랜잭션, 다른 매칭에서 잠겼으면 409 `TICKET_ALREADY_RESERVED`, 이미 눌렀으면 멱등 200), `/reject`(제안받은 b측만)·`/cancel`(참여자 누구나) -> CANCELED(`canceled_by_id`, 시스템=NULL, RESERVED였으면 잠금 해제, 취소 후 같은 쌍 재매칭 허용), 불허 전이 409 `MATCH_STATE_CONFLICT`. 훅 채움: 열린 매칭 있으면 요청 PATCH/DELETE 409 `ACTIVE_MATCH_EXISTS`, 잠긴 티켓 내리기 409 `TICKET_RESERVED`(아니면 요청 CLOSED + 그 티켓 CHATTING 매칭 시스템 취소), 후보 조회에서 예약 잠금 티켓·같은 쌍 열린 매칭 제외(차단은 `user_block` 없어 미적용), 내 티켓이 잠겨 있으면 후보 조회 422 `TICKET_LOCKED`, 요청 삭제 시 CANCELED 매칭 먼저 삭제·COMPLETED 있으면 409 `MATCH_HISTORY_EXISTS`(7차 답변으로 폐기, 소프트 삭제). 범위 밖: COMPLETED·채팅·교환 이력·차단(매칭 조회 API는 이후 구현 — 아래 항목). ~~티켓 좌석·회차 갱신은 COMPLETED 시점 한 트랜잭션 교체~~(8차 답변(2026-10-09)으로 대체: COMPLETED 시점에 기존 두 티켓 EXCHANGED + 새 자리 티켓 INSERT, 구현 예정), 이미 시작한 채팅의 accept에는 회차 마감 검사 없음(확정). 잠금 순서 티켓 id↑→요청 id↑→매칭. 교훈: FK 인덱스에 갱신 컬럼(status 등)을 붙이면 UPDATE가 부모 행 S 잠금으로 교착(실제 재현) -> 단일 컬럼 FK 인덱스. 테스트 기본 384건(341 실행·43 skip·실패 0), 환경변수 포함 401건 통과, 동시성 HTTP 50라운드x4 불변식 위반 0·교착 0. 다음은 V8 이후(차단·채팅·완료·이력; V6·V7은 범위별 추가금·소프트 삭제에 사용). X–X·NEG–NEG는 7차 답변으로 성립 확정. 산출물 04·05 원본 반영 대기.
- **2026-10-08 교환 UI 및 매칭 조회 API(미커밋, `feature/exchange-ui`)**: GET /api/exchange/matches/me·{id}(마이그레이션 없음, 테스트 408/425건)와 프론트 교환 화면 5개(/tickets/new·/tickets·/tickets/:ticketId/exchange·/exchange/requests/:requestId/candidates·/exchange/matches). 브라우저 수동 점검·실서버 연동 미실시. 산출물 04·05 원본 반영 대기.
- **2026-10-08 티켓 등록 구현(미커밋, `feature/ticket-register`)**: Flyway V3·티켓 API 3종·정규화·제한(위 현재 코드 상태 참고). 백엔드 테스트 183건. erd.dot은 ticket을 현재(V3 적용)로 갱신(렌더링 불가, DOT 문법만 점검). 산출물 04·05 원본(docx/xlsx)은 미수정(반영 대기, 티켓 등록의 FR 번호는 이 요약에 없어 확인 필요). 사고 기록은 작업일지 2026-10-08 참고.
- **2026-10-08 6차 답변 반영(교환 규칙 확정, 문서만)**: 추가금 4유형 판정표, 잠긴 티켓 후보 제외, 취소 후 재매칭 불가 폐기(차단·신고 때만), 한쪽 완료 방치(7일 알림만), 지난 회차(당일 끝 다음날 0시 KST까지), 완료 시 티켓 한 번에 교체를 0절에 반영. 남은 확인: X–X·NEG–NEG 판정, 신고용 최소 관리자 기능. 산출물 04·05·08 원본 미수정(반영 대기).
- **2026-10-09 8차 답변 반영(교환 완료 티켓 모델·예약 방식, 문서만, 브랜치 `docs/exchange-complete-ticket-model`)**: 위 0절 '확정(사용자 8차 답변)' 참고. CLAUDE.md·에이전트 3종·erd-conventions·이 스킬·exchange-schema-design.md·backend/frontend README의 낡은 서술을 '8차 답변으로 대체됨'으로 정정하고 CLAUDE.md '확인 필요'에 Q-15~18 추가. 후속요구사항 문서를 `산출물/04_요구사항정의서`에 추가. 코드는 변경 없음(Java 주석 3곳만 정정). 산출물 04·05 xlsx/docx 원본 반영 대기.
- **2026-10-08~09 범위별 추가금·요청 소프트 삭제 구현(미커밋, `feature/range-extra`, 7차 답변)**: ①추가금은 요청 단위가 아니라 **희망 범위 단위**(범위-추가금 짝; 한 티켓에서 1열 낼 의향/5열 추가금 X/10열 받아야 교환 가능). ②겹치는 범위에서 유형·금액이 다르면 422 `WANT_EXTRA_CONFLICT`(완전히 같은 겹침만 허용), 응답 최상위 `conflicts:[[i,j]]`. ③**요청 소프트 삭제**(상태 DELETED·`deleted_at`; 삭제 후 같은 티켓에 새 요청 가능; 삭제 시 want_range/seat/session 삭제; 삭제된 요청은 후보·수정·`/requests/me`에서 제외, 내 매칭 목록에는 '(삭제)' 회색 표시; 삭제된 요청 수정·후보 조회 409 `REQUEST_DELETED`, 삭제 API 멱등 204). ④`exchange_match`에 추가금 스냅샷 4컬럼. ⑤요청 수정·삭제 시 CHATTING 매칭은 시스템 취소(canceled_by NULL), RESERVED가 있으면 409 `ACTIVE_MATCH_EXISTS`('예약된 매칭이 있어…'), 완료 매칭이 있어도 삭제 허용(`MATCH_HISTORY_EXISTS` 제거; COMPLETED 기능은 아직 없음). ⑥**X–X·NEG–NEG 성립 확정**. ⑦미삭제 요청만 티켓당 1개(`uk_exchange_request_live_ticket`, `live_flag`). ⑧Flyway V6(`__exchange_extra_per_range`)·V7(`__exchange_request_soft_delete`), 재실행 가능 프로시저 패턴, 현재 V1~V7·10개 테이블, 차단·채팅·이력은 V8 이후. ⑨후보 응답 `myExtra*`=내 범위에서 상대 좌석이 속한 범위의 추가금, `extraType/extraAmount`=상대 범위에서 내 좌석이 속한 범위의 추가금. ⑩CHECK 보강. 테스트 기본 439건(76 skip)·환경변수 포함 456건, 후보 4,000건 규모 응답 중앙값 약 98~107ms, EXPLAIN `type=ALL` 없음, 이관 시나리오 `MigrationV6V7MysqlTest`. 프론트: 범위 카드마다 추가금 라디오 4개·카드 복제·클라이언트 겹침 경고·후보 '내 조건/상대 조건'·내 매칭 회색 '(삭제)'/'(내 조건 삭제됨)'. 산출물 04·05 원본 반영 대기(08 erd.dot·설계 문서는 반영).
- **2026-10-08 5차 답변 반영(공연 수정 잠금, 구현 완료·미커밋, `feature/lock-performance-edit`)**: 공연 등록 후 수정·삭제 불가(관리자 수정 제안은 후속), 회차도 링크에서 읽어오는 것으로 확정(지금은 직접 입력), 후속 작업에 '관리자 페이지 추가'·'로고 변경' 명시(로고 변경은 2026-10-09 완료). 4차의 확인 필요 2건 해소. 산출물 04·05 원본(docx/xlsx)은 미수정(반영 대기).
- **2026-10-08 4차 답변 반영(venue 삭제 구현 완료, 미커밋)**: V2·`performance.venue_name`·/api/venues 삭제·검색 제목만·공연장 이름 수정 불가(관리자 수정 제안은 후속)·등록 화면 3단계·링크 자동 입력은 다음 작업을 0절에 반영하고 '확인 필요' 2건(회차 자동 입력 범위, 등록 후 제목·회차 수정 범위)을 추가했다. 검증: 백엔드 169건, 임시 DB 3곳에서 V2·백필·SIGNAL 가드 확인, 프론트 tsc·build. 산출물 04·05 원본·docx/xlsx 미수정(반영 대기), 08 erd.dot은 4개 테이블로 갱신(렌더링 불가, DOT 문법만 점검). 다음: 링크 기반 공연 정보 자동 입력(백엔드 어댑터·SSRF·3단계 화면 연결) → 교환 스키마 설계
- **2026-10-08 확정 답변 반영**: CLAUDE.md '확정 전 기본안'의 사용자 답변 11건을 확정 결정으로 옮기고(0절), 해석이 필요한 항목은 '확인 필요' 목록으로 남겼다.
  산출물 04(매칭 FR)·05(교환 도메인 단계)·08(erd.dot) 원본 반영은 대기. 다음 순서: 확인 필요 답변 → 교환 도메인 설계 → 티켓 등록 → 자동 매칭 → 채팅 → 신고
- **2026-10-07 방향 전환**: 좌석표 트랙을 동결하고 텍스트 좌석 입력 기반 자동 매칭을 핵심 흐름으로 삼는다(0절). 다음 작업 순서: 하네스·작업일지 반영 →
  스키마 설계(db-schema-architect, 사용자 확인) → 티켓 등록(구역·열·번, 좌석표 없이) → 희망 조건 등록(범위 펼침) → 후보 조회 → 제안·수락 → 프론트 화면
- 기획/문서화 완료: 산출물/03, 04, 05, 08
- `SeatSwap/backend`, `SeatSwap/frontend`, `SeatSwap/seatmap-service` 코드 스켈레톤 생성 완료 (생성 당시 기록, seatmap-service는 이후 삭제·태그 보관)
  (생성 당시 기록. 현재는 엔티티 5종, 좌석표·seatmap-service·빈 스켈레톤은 삭제됨 — 0절 참고)
- **회원가입/로그인/JWT 인증(FR-01) 실제 구현 완료**: JwtTokenProvider, JwtAuthenticationFilter,
  CustomUserDetailsService, SecurityConfig(CORS 포함), AuthService/AuthController,
  POST /api/auth/signup·login·refresh
- **FR-01 프론트 로그인 연동 구현 + 리뷰 반영 완료 (2026-10-02, 커밋 7dcf8a0)**: authApi(login/signup/refresh),
  AuthProvider(localStorage 토큰 저장·복원, 탭 간 동기화), ProtectedRoute(state.from 복귀),
  LoginPage(입력 검증, 백엔드 400 `{message}`/`{field:msg}` 에러 표시, 모바일 우선 CSS Modules — 2026-10-03 Tailwind로 전환됨).
  code-reviewer 리뷰 반영 완료: access exp 60초 전 선제 재발급 타이머, single-flight refreshSession,
  refresh 400일 때만 토큰 삭제(네트워크/5xx는 보존), redirect 경로 검증(`//`·백슬래시 거부),
  필드 오류 중복 표시 제거, 인터셉터 없는 refresh 전용 authClient. `npm run build` 통과
- 프론트 tsconfig에 `noEmit` 추가 (tsc가 src에 .js를 생성해 vite가 옛 .js를 번들하던 문제 해결)
- **FR-01 회원가입 구현 + 2차 리뷰 반영 완료 (2026-10-02, 커밋 7dcf8a0)**
  - 프론트 SignupPage: 이메일/비밀번호/비밀번호 확인/닉네임, 클라이언트 검증 + 서버 필드 오류 표시,
    가입 후 자동 로그인 → 원래 경로 복귀, 자동 로그인 실패 시 /login 안내 + 이메일 프리필
  - 로그인/가입 공용 모듈: authValidation.ts, useAuthRedirect.ts, AuthTextField.tsx, AuthForm.module.css
  - 백엔드 가입 입력 규칙: 이메일 trim + 소문자 정규화(가입·로그인 공통), 최대 100자 /
    비밀번호 8~64자 + UTF-8 72바이트 이하(bcrypt 한계), 공백만 불가 / 닉네임 trim 후 2~20자,
    보이지 않는 문자(Cf/Cc, 한글 채움 문자) 불가, **닉네임 중복 허용**
  - 이메일 중복(동시 가입 레이스 포함) → 400 `{"email": "이미 가입된 이메일입니다."}`
    (DataIntegrityViolation은 email unique 위반만 중복으로 매핑, 그 외는 로그 + 500)
  - 공통 예외 처리: 깨진 JSON 400, Spring 표준 예외는 원래 상태코드 + 한국어 문구, 그 외 500,
    5xx 상태 유지, Security 예외 rethrow. `/error` permitAll. 오류 메시지 한국어로 통일
  - 요청 DTO toString 비밀번호 마스킹, 로그인 시 72바이트 넘는 비밀번호 즉시 실패
  - 백엔드 단위 테스트 23건(AuthInputNormalizer 7, GlobalExceptionHandler 5, AuthService 11),
    build.gradle에 `useJUnitPlatform()` 추가(없어서 테스트가 0건으로 건너뛰어졌음)
  - 검증: Docker 스택 E2E는 2차 리뷰 반영 전에만 수행. 반영 후에는 `npm run build`, `gradle build`
    (단위 테스트 포함)만 통과
  - 주의: 가입 검증 규칙이 DTO 어노테이션 / 서비스 상수 / 프론트 authValidation.ts 세 곳에 중복 —
    규칙 변경 시 세 곳 모두 수정
- **FR-01 401/403 구분 + 401 재발급 인터셉터 구현, 3차 리뷰 반영 완료 (2026-10-03, 커밋 33bca20, PR #1로 master 머지)**
  - 원칙: 401 = 미인증(재발급 대상), 403 = 권한 없음(재발급 안 함). refresh 일시 장애 시 로그아웃하지 않고 토큰 보존
  - [backend] JwtAuthenticationEntryPoint / JwtAccessDeniedHandler / SecurityErrorResponseWriter
    (SecurityConfig 등록): 401 `{"message":"로그인이 필요합니다."}`, 403 `{"message":"접근 권한이 없습니다."}`,
    401/403에도 CORS 헤더 유지, `/api/auth/**` 동작 유지
  - [backend] JwtAuthenticationFilter: 삭제된 사용자·JWT 파싱 오류(UsernameNotFoundException | JwtException)만
    401, 그 외 예외는 전파. 필터 서블릿 자동 등록 비활성화(이중 등록 방지). RefreshRequest 메시지 한국어화
  - [frontend] 401 인터셉터: 401 + 미재시도 + 인증 엔드포인트(`/auth/login|signup|refresh`, 화이트리스트) 아님
    → single-flight refresh → 1회 재시도. refresh 400·토큰 없음·재시도 후 401 → 토큰 삭제 + 로그아웃
    (→ /login, 원래 경로 유지). 네트워크/5xx → 토큰 유지 + "서버 오류/연결 불가" 표시, 5초 쿨다운.
    403은 재발급 안 함. tokenStorage.subscribe로 같은 탭 로그아웃 알림.
    모듈: authInterceptor.ts, session.ts, authClient.ts (순환 import 없음)
  - 테스트: 백엔드 총 42건(이번에 19건 추가) 통과, `npm run build` 통과. curl 15케이스·프론트 통합 5/5·
    목 시나리오 18/18 확인. 단 3차 리뷰 반영분은 빌드·단위 테스트·목 시나리오로만 검증(Docker 중지)
- **Header + 공통 레이아웃 구현, 4차 리뷰 반영 완료 (2026-10-03, 커밋 c77eea8, PR #2로 master 머지)**
  - AppLayout + Outlet으로 전 페이지 공통 레이아웃, 콘텐츠 영역 `<main>`. 공통 전역 스타일은 `src/index.css`
  - 로고 → `/`, 메뉴: 교환 목록(`/exchange`)·티켓 등록(`/tickets/new`), 현재 경로 강조(aria-current)
  - 로그인: 이메일(말줄임) + 마이페이지 + 로그아웃(홈 이동 후 로그아웃). `/me` API가 없어 닉네임 대신 이메일 표시
    (→ feature/user-me에서 /me 기반 닉네임 표시로 변경, 아래 항목 참고)
  - 비로그인: 로그인·회원가입 링크, 로그인 후 원래 페이지 복귀(로그인/회원가입 화면에서는 기존 복귀 경로 유지)
  - 초기 로딩 중 인증 영역 자리 유지(레이아웃 이동 방지)
  - 768px 미만 햄버거 메뉴(Esc·바깥 터치·경로 이동·화면 확대 시 닫힘, 포커스 관리), 상단 고정, 터치 영역 44px 이상.
    768px 기준은 640px에서 한 줄에 안 들어간다는 추정치 계산에 근거
  - 검증: `npm run build`, tsc(미사용 검사 포함) 통과, dev 서버 `/`·`/login`·`/signup`·`/exchange` 200.
    브라우저 클릭 확인은 미실시
- **브랜치 전략 (2026-10-03, CLAUDE.md에 추가, PR #3으로 master 머지)**: 접두사 feature/(새 기능), fix/(버그),
  chore/(설정·인프라), docs/(문서만)
- **프론트 스타일링 CSS Modules → Tailwind CSS v4 전환, 5차 리뷰 반영 완료 (2026-10-03, 커밋 33704a4, PR #4로 master 머지 — 브랜드 컬러 포함)**
  - 현재 프론트 스택: React + TypeScript + Vite + **Tailwind CSS v4** (tailwindcss, @tailwindcss/vite 4.3.3,
    vite 플러그인 등록, postcss/tailwind 설정 파일 없음). 2절 표는 03 계획서 기준이라 Tailwind가 없음
  - 규칙(react-conventions 스킬 "CSS 전략" 절): Tailwind 유틸리티 클래스만 사용(CSS Module·@apply 미사용),
    완성된 클래스 문자열만 사용, 브레이크포인트 640/768/1024px 고정(rem 아님 — JS matchMedia와 일치),
    지원 브라우저 Safari 16.4+ / Chrome 111+ / Firefox 128+
  - index.css: `@import "tailwindcss"` + `@theme`(폰트, 브랜드 색 토큰 primary #8A2BE2 / secondary #BEA886 / accent #E8A33D, gray·red hex 고정, 브레이크포인트 px 고정).
    AuthForm/Header/AppLayout.module.css 삭제, 반복 클래스 조합은 authFormClasses.ts 상수
  - 추가: 버튼·링크 focus-visible 포커스 링, 오류 입력칸 빨간 테두리·빨간 포커스 링
  - 5차 리뷰 높음 0 / 중간 2 / 낮음 9, 삭제된 CSS 규칙 누락 0건(빌드 CSS 대조)
  - 검증: tsc, vite build 통과, dev 서버 `/`·`/login`·`/signup`·`/exchange` 200. 브라우저 육안 확인 미실시
  - 결정: 브랜드 컬러 메인 #8A2BE2 / 보조 #BEA886(글자색 금지) / 강조 #E8A33D, blue 클래스는 primary 토큰으로 교체
- **내 정보 조회 `/me` + 마이페이지 + 인증 주체 userId 전환, 6차 리뷰 반영 완료 (2026-10-03, feature/user-me 브랜치, PR #6으로 master 머지)**
  - [backend] `GET /api/users/me` → 200 `{id, email, nickname, trustScore}`. 미인증·삭제된 사용자·refresh token 사용 → 401
  - [backend] **인증 사용자 식별은 토큰 userId 기준** (email 기준에서 전환): AuthUserPrincipal, 필터는 토큰 sub로 findById.
    이유: 향후 이메일 변경 시 옛 토큰이 같은 이메일을 새로 쓰는 다른 사용자로 인증될 위험 차단.
    spring-boot-conventions 스킬에 "인증 사용자 식별은 userId", "인증 주체 소실은 AuthenticationException → 401" 조항 추가
  - [backend] 테스트 51건 통과
  - [frontend] 헤더: 닉네임 표시(로딩·실패 시 이메일), 햄버거 메뉴에서는 닉네임 옆에 이메일 작게, 마이페이지 버튼은
    메인색 채운 버튼(데스크톱은 메뉴 링크 유지), 마이페이지·로그아웃 한 줄 배치
  - [frontend] 마이페이지: 닉네임·이메일·신뢰도 점수·로그아웃, 로딩/오류/다시 시도. 내 티켓·거래 내역·받은 리뷰는
    "준비 중", **회원탈퇴 버튼은 "준비 중"으로 비활성화**
  - [frontend] 프로필 상태: 사용자 전환·로그아웃 시 늦게 온 응답 폐기, 공용 useLogout 훅. trustScore 타입 `number | null`
  - 6차 리뷰 높음 0 / 중간 3 / 낮음 8 반영
  - 검증: 프론트 통합(usersApi.me 200, 토큰 없음 401), curl(200, 401 케이스), `npm run build`·tsc 통과.
    userId 전환 이후분은 Docker 중지로 빌드·단위 테스트로만 검증. 브라우저 육안 확인은 사용자 몫
- **Spring Boot 3.3.0 → 3.3.13 업그레이드로 CVE-2025-22228 해결 (2026-10-03, chore/upgrade-spring-security 브랜치, PR #5로 master 머지)**
  - dependency-management 1.1.4 → 1.1.7, Spring Security 6.3.10
  - BCrypt 72바이트 회귀 테스트 추가(이 브랜치 기준 총 49건 통과). 업그레이드 후에도 matches는 앞 72바이트만 비교 →
    **로그인 72바이트 사전 차단은 계속 유지해야 함** (테스트로 고정)
  - plain jar 생성 비활성화 (Docker 빌드 jar 다중 COPY 문제 방지). code-reviewer 리뷰 높음 0, 회귀 없음
  - mysql-connector-j 8.4.0 수동 지정 유지 (Boot 3.3.13 BOM 관리 버전 8.3.0이 더 낮음)
  - 결정: 3.3.13으로 일단 마무리, 추후 4.x 메이저 업그레이드 (3.3·3.4·3.5 라인 OSS 지원 종료, 3.3.13에도
    Security·Framework·Tomcat CVE 다수 잔존)
- FR-01 후속 과제:
  - 3차 리뷰 반영분 Docker 재기동 후 curl·통합 itest 재검증
  - 2차 리뷰 반영분 Docker 스택 재기동 후 E2E 재검증, 브라우저에서 가입/로그인 화면 직접 확인
  - 필터에서 전파된 예외(DB 장애 등)는 Spring 기본 `/error` 포맷(`{timestamp,status,error,path}`)으로 나감 —
    `{message}` 포맷 통일 검토, CORS 헤더 유지 여부 실측
  - 403 경로는 역할 기반 규칙이 없어 슬라이스 테스트로만 확인
  - (완료 2026-10-02) `/error` permitAll + 공통 예외 핸들러, SignupPage
  - 브라우저에서 Header 직접 확인 (햄버거 열고 닫기, 로그아웃 이동, 메뉴 강조, 640~1024px 폭 넘침 여부)
  - index.html viewport에 `viewport-fit=cover` 없음 → safe-area 여백 미적용 (켜려면 다른 화면 여백도 함께 조정)
  - 알림 아이콘 (백엔드 기능 필요)
  - 브라우저에서 Tailwind 전환 화면 확인 (360 / 640 / 768 / 1024px)
  - (완료 2026-10-03) 색 팔레트 방침 결정 — 브랜드 토큰
  - iOS 실기기에서 비활성 입력칸 흐림 정도 확인
  - (완료 2026-10-03) 401 AuthenticationEntryPoint, 401 재발급·재시도 인터셉터, Header 로그인 상태 메뉴/로그아웃
  - (결정 2026-10-03) 프론트 테스트 러너(vitest) 도입 안 함 → 해당 후속 과제 종료
  - (완료 2026-10-03) 내 정보 조회 `GET /api/users/me`, Spring Security CVE-2025-22228 패치 업그레이드(3.3.13 / 6.3.10)
  - [backend] Spring Boot 4.x 메이저 업그레이드 (Spring 7 / Security 7 / Hibernate 7 / Jackson 3, Gradle 업그레이드 가능성) — 배포 전
  - [backend] 회원탈퇴 기능
  - /me 요청 하나에 사용자 조회 2회(필터 + 서비스) — 필요 시 최적화
  - 브라우저에서 헤더·마이페이지 확인 (햄버거 메뉴 이메일·마이페이지 버튼, 회원탈퇴 비활성)
  - [docs] 04 요구사항정의서 FR-01 하위에 "내 정보 조회" 추가 필요 (원본 확보 후)
- **공연·공연장·회차 등록/조회(FR-02) 구현 + 7차 리뷰 반영 완료 (2026-10-06, feature/performance 브랜치, 커밋·푸시 예정)**
  - 스키마는 당시 12개 엔티티(현재는 V1~V7 10개 테이블), 규칙은 6절 참고. 시각은 KST 일원화(JpaAuditingConfig + Clock(Asia/Seoul), Dockerfile·compose TZ=Asia/Seoul)
  - [backend] 공연장 검색·추가(같은 이름 재추가는 200으로 기존 반환), 공연 목록(asOf로 기준 시각 고정)·상세·lookup·등록·수정·삭제,
    회차 추가·수정·삭제. 공연 중복(링크) 409 + performanceId, 회차 중복 409, 과거 회차 400, 수정·삭제는 등록자만(403),
    티켓이 있으면 공연장 변경·삭제·회차 변경 불가(409). 오류 포맷 400/401/403/404/409 통일
  - [backend] SourceKeyResolver·TicketingSite: 인터파크/멜론/YES24/티켓링크는 `{site}:{productId}`, 그 외는 호스트+경로+정렬 쿼리+프래그먼트
    (합쳐지는 것보다 놓치는 쪽 선택), SSRF 대비 허용 호스트 목록(좌석맵 수집 단계용)
  - [backend] 동시성: 공연 행 PESSIMISTIC_WRITE로 변경·삭제·회차 변경 직렬화, 제약 이름으로 중복 판별,
    분류 안 된 DataIntegrityViolation은 409, `spring.jpa.open-in-view=false`. source_key는 utf8mb4_bin(대소문자 구분)
  - [frontend] 홈(공개 라우트: 비로그인 서비스 소개 / 로그인 공연 검색·목록·더 보기), 공연 등록 5단계(링크 확인·제목·공연장·회차·등록),
    공연 상세(보호 라우트, 등록자만 수정·삭제, 페이지 내 확인 상자, 외부 링크 호스트 표시 + 비공식 예매처 안내),
    AuthTextField→TextField·components/ui.ts 공용화, 포커스 링 대비 5.96:1, 중립 배경 surface 토큰
  - 테스트: 백엔드 161건 통과. 7차 리뷰 높음 2 / 중간 8 / 낮음 13 반영
  - DB 조치: Docker DB를 새 스키마로 재생성(users 유지), source_key를 utf8mb4_bin으로 ALTER, 기존 행 시각 +9시간 보정
  - 검증: 실제 서버 smoke 통과, 프론트 itest 13/13. 브라우저 육안 확인은 미실시. 사용자가 등록한 공연 데이터는 보존
  - 결정: 공연은 로그인 사용자 누구나 등록, 홈 공개·공연 상세 보호 라우트, 링크 기반 공연정보 자동 입력(아래 다음 단계 3번),
    정정 정책 보류, 메인 페이지 Phase 1 구현 보류. venue.normalized_name은 ai_ci 유지, idx_performance_session_starts_at은 미사용이나 유지
  - 이슈: ddl-auto update는 컬럼 길이·NOT NULL·collation 변경·컬럼 삭제를 반영하지 못해 이번엔 수동 DDL·테이블 재생성 처리
  - 후속: 회차 추가 개수 상한·스팸 정리, 공연장 무제한 생성·중복 정리, 예매처별로 같은 공연이 갈라지는 문제(같은 공연장+비슷한 제목 안내),
    티켓 등록 시 공연 행 잠금(M6), 실제 MySQL 통합 테스트 없음(Testcontainers는 의존성 승인 필요), Flyway 도입 검토(의존성 승인 필요),
    좌석맵 수집 시 SSRF 대비, 브라우저에서 홈·공연 등록·공연 상세 확인,
    [docs] 04 FR-02 하위 항목(공연장 검색·추가, 링크 조회, 회차)·08_ERD 원본에 PerformanceSession·source_key 반영 (원본 확보 후)
- 다음 단계 (2026-10-06 확정 순서):
  1. 공연·공연장·회차 등록/조회(`feature/performance`) 마무리 — 커밋·푸시·머지
  2. 좌석맵 인식 서비스 1차 구현 — 안전한 요청 기반(SSRF 방어: https·443, 호스트 정확 일치, 사설 IP 차단, 리다이렉트 재검증,
     크기·시간 제한)과 사이트별 어댑터(`TicketingSite`)를 함께 구현
  3. 같은 어댑터로 공연정보(제목·공연장·회차) 읽기 → 등록 화면에 미리 채워 사용자가 확인한 뒤 등록
     (CLAUDE.md "링크 기반 공연정보 자동 입력" 결정 참고). 악의적 수정·허위 정보 방지책은 추후 결정
  4. 티켓 등록 + 좌석맵에서 내 좌석 선택
- 후속 과제 (2026-10-06 결정): 공연 정보 정정 정책(등록자 외 수정 수단, 리뷰 M3) — 보류, 상세는 `산출물/04_요구사항정의서/FR-02_공연정보_정정정책_후속과제.md`
- 완료 (2026-10-06): 시간대 수정 전에 저장된 `created_at`/`updated_at`을 KST로 +9시간 보정 (users 2, venue 1, performance 1, performance_session 1행, `starts_at`은 제외)
- 보류 (참고용): 메인 페이지 Phase 1 명세 — react-conventions 스킬 "계획된 화면 명세" 참고, 당장 구현하지 않음
- **[보존용 기록] 아래 2026-10-07 좌석표 관련 항목의 '미병합·병합 대기·구현 완료·커밋 예정' 표기는 당시 시점이다. 실제로는 모두 master에 병합(PR #14~#20)된 뒤 브랜치가 삭제됐고, 좌석표 트랙 동결로 코드는 삭제되어 태그 `archive/seatmap-track-20261007`에 보관되어 있다.**
- **2026-10-07 결정·진행 (docs/seatmap-decisions, 상세는 위 4절 및 산출물/07_작업일지/2026-10-07.md)**
  - 좌석맵은 사용자 이미지 업로드/주소 입력이 정식 경로, DRAFT/OFFICIAL 좌석표 흐름, 열 번호 continue 기본, 링크 자동 입력은 제목·공연장·날짜 범위만
  - 별도 브랜치에서 구현해 PR #8~#11로 master에 **병합됨**(단, fix/session-time-step의 리뷰 반영 커밋 9ae3d3d는 병합 전, Flyway 운영 DB baseline 미적용): fix/session-time-step(회차 시각 10분 단위 입력),
    chore/flyway-migration(Flyway 도입, V1), feature/seatmap-service(Phase 0/1: safe_fetch SSRF 방어, 멜론 어댑터,
    검출·OCR·열번호 파이프라인, API 5종, pytest 186건)
  - FR-02 정정 정책 문서: 관리자 정식 등록 + 수정 로그로 방향 결정, 상태는 '부분 결정'
  - 산출물 원본(04 xlsx/05 WBS/08 ERD) 반영 대기 항목은 2026-10-07.md 끝의 '원본 반영 대기' 목록 참고
- **2026-10-07 결정 — 교환 범위는 공연 단위 (CLAUDE.md와 동일)**: 같은 공연의 다른 회차 티켓끼리도 교환 가능. 매칭 판정·후보 조회·신청 유효성 검증은 session이 아니라 performance 기준.
  OFFICIAL 좌석표는 회차와 무관하게 공연장 단위로 공유. DRAFT 공연은 본인 좌석 정보 + 희망 좌석 범위로 매칭하고 희망 범위에 회차를 포함할 수 있음.
  **미정**: 차액 계산 방식, 같은 회차 우선 노출 여부 등 세부. 이 결정에 따른 04 요구사항정의서·08_ERD 원본 반영은 원본 확보 후 (07 작업일지 기록 위치는 사용자가 정함)
- **2026-10-07 이어서 진행(스키마 설계 ~ V2 구현, 상세는 산출물/07_작업일지/2026-10-07.md '이어서 진행한 작업')**
  - 결정: DRAFT 구역별 하나·OFFICIAL 복수 허용 후 1개 제한 예정, 티켓 먼저 등록·교환글에서 DRAFT 업로드, 제재 2종, 회원 익명화, 신고 항상 로그, 좌석 uid (4절 참고)
  - 구현 완료·병합됨(PR #16·#15, 이후 코드 삭제·태그 보관): `feature/admin-seatmap-schema`(Flyway V2: users.role, venue.status, seat_map_layout 상태·version·이미지 크기·승격,
    image_url 삭제, ticket.seatmap_id nullable, draft_key; role은 매 요청 DB 로드, /api/admin/** ADMIN, /me에 role; 테스트 195건),
    `feature/seatmap-seat-uid`(좌석 uid, pytest 187건). (erd.dot은 방향 전환 후 5개 테이블 기준으로 새로 작성됨)
  - 없는 것: 관리자 API/서비스, V4(제재·신고), 관리자 페이지 (V3는 아래 `feature/seatmap-edit-log`에서 구현). 정책 미정: 이미 OFFICIAL이 있는 공연장의 DRAFT 허용 여부. Testcontainers 미도입
  - 이슈: Docker Desktop 꺼진 채 재빌드 시 mysql이 Exited(137) → 백엔드 `UnknownHostException: mysql`, mysql 먼저 기동으로 해결
- **2026-10-07 좌석표 등록·조회 구현과 실제 좌석표 시험 (상세는 산출물/07_작업일지/2026-10-07.md '좌석표 등록·조회 구현과 실제 좌석표 시험')**
  - `feature/seatmap-register`(당시 미병합 → PR #17로 병합, 코드는 이후 삭제·태그 보관): `POST /api/venues/{venueId}/seatmaps`(multipart file, zoneName, aisleMode) → seatmap-service `/recognize` 중계(X-Internal-Key 선택, 연결 5초·읽기 60초) → DRAFT 저장(좌표만, 이미지 미저장). `GET /api/seatmaps/{id}`, `GET /api/venues/{venueId}/seatmaps`.
    같은 공연장+구역 DRAFT 중복 시 409(seatMapId), 동시 인식 제한(전역 3·사용자당 1 → 429 RATE_LIMITED / 503 BUSY), 인식 결과 검증(좌석 6000 상한 등), 이미지 시그니처 검사, 업스트림 오류는 고정 한국어 문구.
    프론트: SeatMapOverlay(SVG·줌·키보드), 좌석표 조회 화면(DRAFT '확인용' 안내, 오류 신고 버튼 비활성 '준비 중'), 업로드 화면. 백엔드 테스트 254건, 프론트 tsc·build 통과. 임시 기능 TEMP-DRAFT-DELETE 포함(4절 참고)
  - `feature/seatmap-real-image`(워크트리 dev-seatmap, 커밋 eb8104a, 당시 미병합 → PR #18로 병합): 실제 좌석표 대응 인식 개선. 사용자가 올린 3개 층 449x549 이미지 시험 — 개선 전 좌석 1619·구역 구분 없음·열 OCR 0 → 개선 후 좌석 1674·구역 3(1F 23열 974석, 2F 10열 430석, 3F 6열 270석)·열 라벨 OCR 33/39. pytest 199건, 합성 48종 100% 유지.
    **한계**: 정답 없이 눈·격자 규칙으로 추정, 임계값은 이미지 1장 기준, 열 라벨 6/39 미판독(보간), 층 이름 미인식, 같은 색 좌석 위주 이미지·회색 좌석 8개 미만이면 실패, 이미지 안 글자(무대 표시)가 좌석으로 잡힐 수 있음
  - `feature/draft-limits`(2026-10-07, 당시 미병합 → PR #19로 병합): DRAFT 선점·스팸 대응 1차. 구역 상한 20(422)·사용자 일일 10건(429, ADMIN 제외), 검사 순서 공연장 확인 → 같은 구역 DRAFT 409 → 구역 상한 → 일일 제한 → 동시성 제한(RATE_LIMITED 429/BUSY 503) → 인식. 삭제는 작성자·ADMIN(403, 임시 플래그 true면 누구나), 응답 `canDelete`. 한계: 삭제 후 재업로드로 일일 제한 우회, count~insert 사이 락 없어 상한 소폭 초과 가능, 인덱스 없는 count(`seat_map_layout(created_by, created_at)` 인덱스는 V3 후보). 백엔드 테스트 270건, 프론트 tsc·build 통과. 미구현: DRAFT 수정+수정 로그(V3), 신고·제재(V4)
  - 이슈/후속: DRAFT 선점·스팸 정책 중 제한·삭제 권한은 위 브랜치에서 1차 구현, 수정 로그·신고/제재는 미정(V3·V4). 목록 seatCount 미제공(seat_count는 Flyway V3 필요 → V3 브랜치에서 해결), 업로드 화면에 공연장 이름 없음, 좌석표 수정·오류 신고 API/UI 없음(V3 브랜치에서 구현), 관리자 API·정식 등록·관리자 페이지 없음, 요청 본문 이중 버퍼링(요청당 최대 약 30MB), Testcontainers 미도입, OFFICIAL 있는 공연장의 DRAFT 허용 여부 미정. 백엔드 ↔ seatmap-service 연동은 이번에 완료. 링크 기반 공연정보 미리 채우기·티켓 등록(좌석표 없이)·교환글+DRAFT 업로드는 다음 단계
- **2026-10-07 좌석표 수정·정정 신고(Flyway V3) (상세는 산출물/07_작업일지/2026-10-07.md '좌석표 수정·정정 신고(V3)')**
  - `feature/seatmap-edit-log`(당시 미병합 → PR #20으로 병합): V3 = `seat_map_layout.seat_count`·idx(created_by, created_at), `seat_map_revision`(수정 로그 헤더, append-only, UK(seatmap_id, revision_no)), `seat_map_revision_item`(좌석·필드별 전후), `seat_correction` 개편(1신고=1행, `vote_count` 삭제, target_seat_uid·target_field·normalized_value·layout_version·검토/적용 필드, status PENDING/APPLIED/REJECTED/SUPERSEDED, 중복 방지는 PENDING 한정 생성 컬럼 `pending_key` UNIQUE). 파일 맨 앞에서 `seat_correction`에 행이 있으면 SIGNAL로 즉시 실패(실패 시 `flyway repair` 필요, backend README 참고)
  - API·남용 방지·프론트 동작은 4절 참고. 테스트: 백엔드 320건 통과, 임시 DB(빈 DB·덤프 복사본·seat_correction 행 있는 복사본)에서 V3 적용·validate 통과, 프론트 tsc·build 통과
  - 한계·후속: 4절 'V3 한계·미결정' 참고. Testcontainers 통합 테스트 미도입(동시성·제약은 임시 DB 수동 확인만). 산출물 원본(04·05) 반영 대기, erd.dot은 V3 구현 완료로 반영(병합 후 현재로 승격)
- 참고: 2026-10-02 기준 저장소에 `산출물/` 03/04/05/08 원본이 없음. 원본 확보 전까지 1~6절은 이 스킬이 유일한 텍스트 출처
- 작업일지(산출물/07): 날짜별 `YYYY-MM-DD.md` 파일, 이어지는 작업 묶음은 시작일 파일에 `## 날짜` 섹션을 추가.
  현재 `2026-07-26.md`(본문 헤더 2026-07-23, 기획 단계), `2026-10-02.md`(2026-10-02 + 2026-10-03 + 2026-10-06 섹션), `2026-10-07.md`
- 결정 (2026-10-03): 프론트 테스트 러너(vitest)는 도입하지 않음 — 인터셉터 분기는 저장소 밖 임시 스크립트로만 검증된 상태
