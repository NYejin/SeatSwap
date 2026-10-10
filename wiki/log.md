# 위키 작업 기록

이 파일은 추가만 한다. 기존 항목은 고치거나 지우지 않고, 날짜 오름차순으로 끝에만 새 항목을 붙인다. 항목 형식은 `## [YYYY-MM-DD] <작업> | <제목>`이며 작업 종류는 ingest, query, lint, init 중 하나다. 제목 아래에 한 줄 설명을 붙일 수 있다.

## [2026-10-10] init | llm-wiki 구조와 린트 스크립트 생성
llm-wiki 스킬, researcher·wiki-curator 에이전트, `scripts/lint-wiki.mjs`를 만들었다. 페이지는 아직 없다.

## [2026-10-10] ingest | 교환 완료 시 티켓 처리: 안 2의 근거
`wiki/decisions/exchange-complete-new-ticket.md`를 추가했다. 선택지 3가지와 안 2를 고른 이유를 정리했다.

## [2026-10-10] ingest | 잠금 순서와 인덱스·유니크 키 함정
`wiki/gotchas/lock-order-and-index-pitfalls.md`를 추가했다. 교환 도메인의 잠금 순서 규약과 인덱스·유니크 키 함정을 정리했다.

## [2026-10-10] ingest | 용어집
`wiki/glossary.md`를 추가했다. 매칭 상태, 교환 요청, 추가금, 티켓과 마감 용어를 정리했다.

## [2026-10-10] ingest | 실시간 채팅(STOMP) JWT 인증·인가 조사
`wiki/research/stomp-jwt-auth.md`를 추가했다. 출처는 researcher 웹 조사 보고서이며 confidence는 medium이다.
