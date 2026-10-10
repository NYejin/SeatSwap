---
name: llm-wiki
description: 프로젝트 위키(wiki/)의 스키마와 ingest·query·lint 절차. Karpathy의 LLM Wiki 패턴(사람이 원문을 모으고 LLM이 위키 페이지를 쓰고 갱신·교차 링크하며 유지)을 따른다. 결정 근거·함정·조사 결과·용어를 위키에 올리거나, 위키에서 답을 찾거나, 위키를 점검할 때 반드시 참고한다.
---

# llm-wiki

사람이 원문을 모으고, LLM이 위키 페이지를 쓰고 갱신하고 교차 링크하며 유지하는 방식이다. 코드에서 바로 알 수 있는 것은 위키에 쓰지 않는다. 위키는 "왜 그렇게 했나", "무엇이 함정인가", "무엇을 조사했나", "이 용어는 무엇인가"를 담는다.

## 구조

```
wiki/
├── index.md       목차. 모든 페이지가 여기서 링크된다
├── log.md         추가만 하는 작업 기록
├── glossary.md    용어집
├── concepts/      개념 설명
├── decisions/     결정 근거 (결정 자체는 CLAUDE.md, 여기엔 근거·대안·링크만)
├── research/      조사 결과 (출처·접근일·요약)
└── gotchas/       함정·재현 조건·해결
raw/               원문 보관. .gitignore라 커밋되지 않는다
```

- **공개 저장소**이므로 제3자 원문(기사, 문서 전문, 캡처 등)을 커밋하지 않는다. `raw/`는 로컬 보관용이다. 위키에는 **출처 URL·접근일·요약**만 둔다.
- `log.md` 형식: `## [YYYY-MM-DD] <작업> | <제목>` (작업 = ingest|query|lint|init). 아래 한 줄 설명을 붙일 수 있다. 기존 항목은 고치지 않고 날짜 오름차순으로 끝에만 추가한다.

## 페이지 frontmatter

```yaml
---
title: 한국어 제목
type: concept            # concept | decision | research | gotcha | glossary | index
tags: [태그1, 태그2]
sources: [https://example.com/a, ../decisions/foo.md]   # 비어 있으면 []
updated: 2026-10-10      # YYYY-MM-DD
confidence: medium       # high | medium | low
status: draft            # draft | stable | stale | superseded
---
```

- confidence: **high** = 코드·공식 문서·실측으로 확인, **medium** = 신뢰할 만한 단일 출처 또는 부분 확인, **low** = 추정·미확인·출처 빈약.
- status: draft(작성 중), stable(검토됨), stale(낡았을 수 있음), superseded(다른 페이지로 대체됨 — 본문 첫머리에 대체 페이지 링크).
- 내용을 고치면 `updated`를 오늘 날짜로 바꾼다.

## 규칙

- 파일명은 영문 소문자 kebab-case(`seat-lock-deadlock.md`), 제목(title)과 본문은 한국어.
- 링크는 상대 경로 마크다운 링크(`[제목](../gotchas/foo.md)`). 위키 페이지끼리 교차 링크한다.
- 시크릿·개인정보·긴 원문·저작권 있는 장문 인용을 쓰지 않는다.
- **CLAUDE.md의 확정 결정은 복사하지 말고 링크만** 한다(`../../CLAUDE.md`). 결정을 새로 만들지 않는다.
- **한 사실은 한 곳에만** 쓰고 나머지는 링크한다.
- 불확실하면 `confidence: low`로 표기하고 본문에 무엇이 미확인인지 적는다.

## 위키로 올리는 기준

올린다: 결정의 근거와 대안, 겪은 함정(재현 조건·해결), 조사 결과, 프로젝트 용어.
올리지 않는다: 코드를 읽으면 알 수 있는 것, 일시적 작업 상태(→ HISTORY.md), 확정 결정 본문(→ CLAUDE.md).

## 절차

### ingest (한 번에 한 출처)
1. 출처를 읽는다(조사 보고서, 문서, URL 등). 원문은 필요하면 `raw/`에 로컬 보관만 한다.
2. 요약 페이지를 만들거나 기존 페이지를 갱신한다(frontmatter 포함, 출처 URL·접근일 기록).
3. 관련 페이지에 교차 링크를 추가한다.
4. `wiki/index.md`에 새 페이지를 등록한다.
5. `wiki/log.md`에 `## [날짜] ingest | 제목` 항목을 끝에 추가한다.
6. `node scripts/lint-wiki.mjs`로 검증하고 오류를 고친다.

### query
1. `wiki/index.md`를 먼저 읽고 관련 페이지만 읽는다.
2. 답에 근거 페이지(경로)를 표기한다.
3. 위키에 없으면 "위키에 없음"이라고 답한다. 필요하면 researcher에게 조사를 의뢰하고, 결과는 ingest로 올린다.
4. 의미 있는 질의는 `log.md`에 `query` 항목으로 남길 수 있다(선택).

### lint
- **스크립트(토큰 0)**: `node scripts/lint-wiki.mjs` — frontmatter 필수 키·enum, 고아 페이지, 깨진 링크, index 누락, updated 90일 초과, log 형식·날짜 순서, superseded 페이지가 여전히 링크되는지.
- **LLM lint(월 1회)**: 페이지 간 모순, 낡은 서술, CLAUDE.md 확정 결정과의 불일치 판단. 결과는 수정하고 `log.md`에 `lint` 항목으로 남긴다.

## 역할 분담

| 일 | 담당 | 모델 |
|---|---|---|
| 웹·문서 조사 (읽기 전용, 보고서 반환) | researcher | haiku |
| ingest, lint 실행, 위키 파일 쓰기 | wiki-curator | sonnet |
| 무엇을 위키에 올릴지 결정, 결정 사항 | 메인 / 사용자 | |

wiki-curator의 쓰기 범위는 `wiki/` 아래뿐이다.
