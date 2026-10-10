export const meta = {
  name: 'verify-feature',
  description: '병렬 리뷰어 3명이 정확성·보안과 동시성·접근성과 문구를 검토하고 적대적 교차 검증으로 높음·중간 지적만 추린다',
}

// 초안(검증 전). 읽기 전용이라 자유롭게 병렬로 실행해도 된다.
// args: { target: '검토할 범위 설명(브랜치명, 또는 master 대비 diff)', context?: '확정 결정·설계 요약 문자열' }
// 미검증 항목: agent() 옵션 중 model 이름. /workflow-authoring 스킬로 확인한 뒤 수정한다.
const MODEL = { review: 'opus', crossCheck: 'fable' }

if (!args || !args.target) {
  return { error: 'args.target(검토할 브랜치 또는 범위)이 필요합니다' }
}

const findingSchema = {
  type: 'object',
  required: ['reviewer', 'findings'],
  properties: {
    reviewer: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        required: ['severity', 'file', 'issue', 'fix'],
        properties: {
          severity: { type: 'string', enum: ['높음', '중간', '낮음'] },
          file: { type: 'string' },
          line: { type: 'number' },
          issue: { type: 'string' },
          failureScenario: { type: 'string' },
          fix: { type: 'string' },
        },
      },
    },
  },
}

const common =
  '너는 읽기 전용 코드 리뷰어다. 파일을 수정하지 않는다. 검토 범위: ' + args.target + '\n' +
  (args.context ? '맥락: ' + args.context + '\n' : '') +
  '지적마다 심각도(높음/중간/낮음), 파일:줄, 근거, 실패 시나리오(입력과 결과), 구체적 수정안을 쓴다. CLAUDE.md의 확정 결정에 어긋나는 지적은 하지 않는다. 확인하지 못한 것은 추측하지 말고 쓰지 않는다.'

const lenses = [
  { name: '정확성', focus: '로직 오류, 상태 전이, 경계값, 입력 검증, 예외 처리, N+1, 테스트 공백, 문서와 코드의 불일치' },
  { name: '보안·동시성', focus: '인가·정보 노출, 인젝션, 잠금 순서(티켓 id 오름차순→요청→매칭), 교착, 경쟁 조건, 트랜잭션 경계, FK 인덱스 규칙, 마이그레이션 안전성' },
  { name: '접근성·문구', focus: 'label 연결, role=alert 사용 원칙, 포커스 이동, 대비, 터치 영역, 한국어 문구의 정확성, 오류 코드 매핑, 사용자 변경(레이아웃·헤더) 훼손 여부' },
]

phase('리뷰')
const reviews = await parallel(
  lenses.map(l =>
    agent(common + '\n관점: ' + l.name + '\n중점: ' + l.focus, { label: '리뷰: ' + l.name, model: MODEL.review, schema: findingSchema }),
  ),
)

const candidates = reviews
  .filter(Boolean)
  .flatMap(r => r.findings.map(f => ({ ...f, reviewer: r.reviewer })))
  .filter(f => f.severity === '높음' || f.severity === '중간')

if (candidates.length === 0) {
  return { target: args.target, confirmed: [], note: '높음·중간 지적이 없습니다', reviewed: reviews.filter(Boolean).length }
}

phase('적대적 교차 검증')
// 지적을 한꺼번에 넘겨 한 명이 코드를 직접 읽고 반박을 시도한다(에이전트 수 절약). 20건을 넘으면 앞의 20건만 검증한다.
const batch = candidates.slice(0, 20)
const verdicts = await agent(
  '너는 적대적 검증자다. 아래 지적 각각이 거짓 양성인지 코드를 직접 읽어 반박해 보라. 파일을 수정하지 않는다. ' +
    '각 지적에 verdict(CONFIRMED=재현·확인됨, PLAUSIBLE=가능하나 확인 못 함, REFUTED=틀림)와 한 줄 근거를 붙이고, REFUTED는 왜 틀렸는지 쓴다. 검토 범위: ' + args.target + '\n\n' +
    '지적 목록:\n' + JSON.stringify(batch.map((f, i) => ({ id: i, ...f })), null, 2),
  {
    label: '교차 검증',
    model: MODEL.crossCheck,
    schema: {
      type: 'object',
      required: ['verdicts'],
      properties: {
        verdicts: {
          type: 'array',
          items: {
            type: 'object',
            required: ['id', 'verdict', 'reason'],
            properties: {
              id: { type: 'number' },
              verdict: { type: 'string', enum: ['CONFIRMED', 'PLAUSIBLE', 'REFUTED'] },
              reason: { type: 'string' },
            },
          },
        },
      },
    },
  },
)

const verdictById = new Map((verdicts ? verdicts.verdicts : []).map(v => [v.id, v]))
const confirmed = batch
  .map((f, i) => ({ ...f, verdict: verdictById.get(i) || { verdict: 'PLAUSIBLE', reason: '교차 검증 결과 없음' } }))
  .filter(f => f.verdict.verdict !== 'REFUTED')

return {
  target: args.target,
  confirmed,
  refuted: batch.length - confirmed.length,
  skippedOverLimit: Math.max(0, candidates.length - batch.length),
  lowCount: reviews.filter(Boolean).reduce((n, r) => n + r.findings.filter(f => f.severity === '낮음').length, 0),
}
