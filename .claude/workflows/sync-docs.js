export const meta = {
  name: 'sync-docs',
  description: '작업이 끝난 뒤 HISTORY.md·작업일지·위키 log를 갱신하고 STATS.md를 재생성한다. 커밋은 하지 않는다',
}

// 초안(검증 전). 서로 다른 파일을 쓰는 세 에이전트는 병렬로 실행해도 된다.
// args: {
//   date: '2026-10-11',          // Date를 스크립트에서 쓸 수 없어 args로 받는다(KST)
//   branch: 'feature/xxx',
//   title: '한 줄 제목',
//   changes: ['변경 사실 불릿', ...],   // 구현 사실만(근거 없는 서술 금지)
//   evidence: { prs: ['#40'], commits: ['abc1234'] },  // 확인된 값만
//   wikiPages?: ['wiki/...']      // 이번 작업으로 만들거나 갱신한 위키 페이지(있으면 log에 기록)
// }
// 미검증 항목: agent() 옵션 중 model 이름. /workflow-authoring 스킬로 확인한 뒤 수정한다.
const MODEL = { docs: 'haiku' }

if (!args || !args.date || !args.branch || !args.title || !Array.isArray(args.changes)) {
  return { error: 'args.date, args.branch, args.title, args.changes가 필요합니다' }
}

const facts =
  '날짜: ' + args.date + '\n브랜치: ' + args.branch + '\n제목: ' + args.title + '\n변경 사실:\n- ' + args.changes.join('\n- ') + '\n' +
  '근거(확인된 값만 사용): ' + JSON.stringify(args.evidence || {}) + '\n' +
  '규칙: 위 사실에 없는 내용은 쓰지 않는다. 해시·PR 번호는 근거에 있는 값만 쓰고 없으면 쓰지 않는다. 한국어. 커밋하지 않는다. 다른 파일은 수정하지 않는다.'

phase('문서 갱신')
const [history, worklog, wikiLog] = await parallel([
  agent(
    '너는 문서 담당이다. 루트 HISTORY.md만 수정한다. 형식을 먼저 읽고 맞춘다: 해당 날짜 제목(### ' + args.date + ') 아래 맨 위에 한 줄 항목을 추가한다(없으면 날짜 제목을 만든다). ' +
      '형식 `- [결정|방향전환|구현|문서|이슈|운영] 내용 — 근거`. 맨 위 "현재 상태" 5줄 중 바뀐 사실이 있으면 해당 줄만 고친다. 상세는 작업일지를 가리키고 중복해서 쓰지 않는다.\n' + facts,
    { label: 'HISTORY.md', model: MODEL.docs },
  ),
  agent(
    '너는 문서 담당이다. 산출물/07_작업일지/2026-10-08.md처럼 기존 작업일지 형식을 읽고 맞춰, 가장 최근 작업일지 파일 끝에 이번 작업의 섹션(구현 사실, 변경한 문서, 남은 확인/한계, 원본 반영 대기)을 추가한다. 과거 항목은 고치지 않는다. 오늘 날짜 파일이 없으면 새로 만들지 말고 가장 최근 파일에 추가한 뒤 그 사실을 보고한다.\n' + facts,
    { label: '작업일지', model: MODEL.docs },
  ),
  agent(
    '너는 위키 기록 담당이다. wiki/log.md 끝에 `## [' + args.date + '] <작업> | <제목>` 형식으로 항목을 추가한다(작업 종류는 ingest|query|lint|init 중 하나, 이번 작업이 위키 페이지를 만들거나 고친 경우에만 추가하고 아니면 아무것도 하지 않는다). ' +
      '위키 페이지: ' + JSON.stringify(args.wikiPages || []) + '. 추가 후 node scripts/lint-wiki.mjs를 실행해 오류 0건인지 확인하고 결과를 보고한다.\n' + facts,
    { label: '위키 log', model: MODEL.docs },
  ),
])

phase('통계 재생성')
const stats = await agent(
  'node scripts/update-stats.mjs 를 실행하고 STATS.md가 바뀌었는지(git diff --stat -- STATS.md)와 주요 수치(커밋 수, 병합 PR 수, 마이그레이션 최신 번호)를 보고한다. 다른 파일은 수정하지 않는다.',
  { label: 'STATS.md', model: MODEL.docs },
)

return { date: args.date, branch: args.branch, history, worklog, wikiLog, stats }
