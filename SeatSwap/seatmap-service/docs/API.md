# seatmap-service API 계약 (백엔드 호출용)

- Base URL: 컨테이너 간 `http://seatmap-service:8001`, 로컬 `http://localhost:8001`
- **인증/노출**: 이 서비스는 사용자 인증이 없는 내부 서비스다. 두 겹으로 막는다.
  1. `docker-compose.yml`에서 호스트 포트를 `127.0.0.1:8001:8001`로만 연다(외부 인터페이스 노출 금지). 백엔드는 compose 내부망
     `http://seatmap-service:8001`로 호출하므로 호스트 포트가 필요 없다.
  2. 환경변수 `SEATMAP_INTERNAL_KEY`를 설정하면 `/api/*` 요청에 헤더 `X-Internal-Key: <같은 값>`이 없을 때 401 `UNAUTHORIZED`
     (본문을 읽기 전에 거절). **미설정이면 개발 모드로 모두 허용**한다. 운영/공용 환경에서는 반드시 설정하고, 백엔드 호출 코드가
     같은 값을 헤더로 보내야 한다. `/health`는 키 없이 열려 있다.
  3. `SEATMAP_ENABLE_DOCS=false`로 `/docs`, `/redoc`, `/openapi.json`을 끌 수 있다(운영 권장, 기본은 켜짐).
- 요청 본문 상한: 업로드(`/recognize`)는 10MB(+멀티파트 여유 64KB), 그 외 JSON은 16KB. `Content-Length`가 넘으면 읽지 않고
  즉시 413, 없거나 거짓이어도(청크 전송) 읽는 도중 상한을 넘는 순간 중단하고 413.
- 요청/응답 JSON은 camelCase. 좌표는 모두 **원본 이미지 픽셀** 기준.
- 오류는 항상 `{"code": "...", "message": "..."}` (message는 사용자에게 보여줘도 되는 한국어 문구)
- 이미지는 어디에도 저장하지 않는다 (요청 처리 중 메모리에서만 사용).
- `site` 코드: 백엔드 `TicketingSite.key()`와 동일 (`interpark`/`melon`/`yes24`/`ticketlink`).
  **현재 지원: `melon`만.** 그 외는 400 `UNSUPPORTED_SITE`.
  (2026-10-07 조사: 인터파크·YES24·티켓링크도 공개 접근으로는 좌석맵 이미지를 얻을 수 없어 어댑터를 만들지 않았다. 아래 표 참고.
  이 사이트들의 좌석맵은 업로드 또는 이미지 주소 경로로 인식한다.)

| 사이트 | 어댑터 | 좌석맵 자동 수집 | 근거 |
|---|---|---|---|
| melon | O | 사실상 불가(공개 페이지에 없음, 구조만 지원) | 좌석 배치는 예매 팝업·보안문자 뒤 |
| interpark | X | 불가 | robots.txt가 `User-agent: *`에 `Disallow: /` (SeatSwapBot 접근 금지) |
| yes24 | X | 불가 | 공개 상품 페이지 HTML에 좌석맵 없음. 회차/좌석은 AJAX, 예매 팝업 흐름 뒤 |
| ticketlink | X | 불가 | 공개 상품 페이지는 JS 셸(포스터 이미지만). 좌석맵은 `/reserve`(robots 금지)·내부 API |

## seatJson 형식 (seats 배열의 원소)

```json
{ "uid": "s0123", "row": 3, "col": 5, "x": 187, "y": 210, "w": 18, "h": 18, "section": 1 }
```

- `section`: 구역(층) 번호, 위에서부터 1, 2, 3... 한 이미지에 구역이 하나뿐이면 1. **행 번호(`row`)는 구역 안에서 1부터(또는 라벨대로) 다시 시작하므로
  좌석의 식별은 `(section, row, col)`이다.** (`section` 추가는 필드 추가만이라 기존 소비자는 무시해도 동작하지만,
  여러 층 이미지에서는 `(row, col)`만으로는 층 사이에 중복되므로 저장 쪽에서 `section`을 함께 보관해야 한다.)

- `uid`: 좌석 안정 식별자(문자열, 최대 32자). 수정 로그·정정 신고가 "어느 좌석"인지 가리키는 데 쓴다.
  검출된 블록을 공간 순서(y, x, w, h)로 정렬한 일련번호(`s0001`부터)이며, 한 인식 결과 안에서 유일하다.
  같은 이미지를 다시 인식하면 같은 uid가 나오고(결정적), OCR 결과·`aisleMode`·정정으로 `row`/`col`이 바뀌어도 uid는 바뀌지 않는다.
  기존 필드(row, col, x, y, w, h)와 응답 구조는 그대로(필드 추가만).

## 엔드포인트

### GET /health
`{"status":"ok","tesseract":true}` — tesseract가 false면 행 번호가 순번으로 대체된다.

### POST /api/seatmap/analyze  (한 번에: 이미지 확보 + 인식)
요청 `{"site":"melon","productId":"213480","aisleMode":"continue"}` (aisleMode 생략 가능)
응답 200: 인식 결과(아래) + `"sourceImageUrl": "https://cdnticket.melon.co.kr/..."`
서비스가 `productId`로 상품 페이지 URL을 직접 조립해 요청한다. 링크 원문은 받지 않는다.
공개 좌석맵 이미지가 없으면 422 `SEATMAP_NOT_AVAILABLE` (아래 "현재 한계" 참고).

### POST /api/seatmap/discover  (이미지 후보만 찾기)
요청 `{"site":"melon","productId":"213480"}`
응답 200 `{"site","productId","pageUrl","images":[{"url","source"}]}`; 후보가 없으면 422 `SEATMAP_NOT_AVAILABLE`.

### POST /api/seatmap/recognize  (이미지 업로드 인식)
`multipart/form-data`, 필드 `file`(PNG/JPEG/WebP, 10MB 이하), 쿼리 `aisleMode=continue|skip`(기본 continue).

### POST /api/seatmap/recognize-url  (이미지 주소 인식)
요청 `{"site":"melon","imageUrl":"https://cdnticket.melon.co.kr/....png","aisleMode":"continue"}`
`imageUrl`은 해당 사이트 어댑터가 허용한 이미지 호스트(정확 일치, https)만 가능. 아니면 400 `IMAGE_URL_NOT_ALLOWED`.

### 인식 결과 (analyze / recognize / recognize-url 공통)
```json
{
  "image": {"width": 700, "height": 400},
  "seats": [{"uid": "s0001", "row": 1, "col": 1, "x": 70, "y": 40, "w": 18, "h": 18, "section": 1}],
  "rows": [{"row": 1, "rowSource": "ocr", "labelConfidence": 91.0, "seatCount": 16, "section": 1,
            "aisles": [{"afterCol": 8, "gapPx": 44, "missingSlots": 2}]}],
  "sections": [{"section": 1, "rowCount": 13, "seatCount": 208, "ocrRowsRead": 13,
                "bbox": {"x": 70, "y": 40, "w": 388, "h": 332}}],
  "stats": {"blockCount": 208, "rowCount": 13, "ocrRowsRead": 13, "discardedComponents": 13,
            "splitSeats": 0, "sectionCount": 1},
  "warnings": [{"code": "AISLE_DETECTED", "message": "..."}]
}
```
- `rowSource`: `ocr`(라벨을 읽음) / `inferred`(못 읽어서 이웃 행의 증가·감소 규칙으로 보간) / `sequence`(순번 대체, 확인 필요).
- `sections`(응답 최상위, 위에서 아래 순): 구역(층) 요약. `rowCount`/`seatCount`/`ocrRowsRead`와 구역을 감싸는 `bbox`(원본 픽셀).
  구역은 **행 중심 간격이 정상 행 피치의 4배 이상 벌어지는 지점**(층 사이 공백·배지)에서 나눈다. 2.5~4배의 애매한 간격은
  한 층 안의 가로 통로일 수 있어 나누지 않되, 그 아래 행의 라벨이 1로 다시 시작하면 층 경계로 인정한다. 애매해서 나누지 않았거나
  나눈 뒤에도 아래 구역 번호가 위에서 이어지면 경고 `SECTION_SPLIT_UNCERTAIN`.
- `stats.splitSeats`: 색이 칠려 서로 붙어 있던 좌석 덩어리에서 개별 좌석으로 분리해 복원한 수. `stats.sectionCount`: 구역 수.
- 행 번호(`rowSource`)는 구역별로 정한다: 라벨을 읽은 행은 `ocr`, 못 읽은 행은 구역 안의 증가/감소 규칙으로 `inferred`, 라벨이 하나도 없으면 구역마다 1부터 `sequence`.
  라벨은 행 양끝뿐 아니라 **블록 사이 통로**에서도 찾고(좌석 밖의 작은 글자 덩어리), 구역의 1,2,3... 규칙과 어긋나는 오판독은 표결로 바로잡는다.
- 열 번호는 한 행 안에서 왼쪽->오른쪽 1,2,3... (블록·통로를 건너 이어서, `aisleMode=continue`).
- `aisleMode`: `continue`는 통로를 건너도 열 번호를 이어서 부여, `skip`은 통로의 빈 좌석 수(`missingSlots`)만큼 번호를 건너뜀(결번).
  어느 쪽이 맞는지는 공연장마다 달라 사용자 보정 대상이므로 `aisles`는 항상 내려준다.
- `warnings` 항목의 JSON 필드는 `{code, message}`로 변하지 않는다. 내부 스키마 클래스 이름만 내장 `Warning`을 가리지 않도록
  `WarningInfo`로 바꿨고, OpenAPI(`/openapi.json`) 컴포넌트 이름도 `WarningInfo`가 된다(클라이언트 코드를 생성해 쓴다면 반영).
- `stats.discardedComponents`: 좌석 후보 크기였지만 모양·크기가 달라 제외한 요소 수(글자, 범례, 크기가 다른 블록 포함).
- `warnings.code`: `OCR_UNAVAILABLE`, `OCR_TIME_BUDGET`(OCR 시간 예산 30초 초과), `ROW_LABEL_UNREAD`(신뢰도 60 미만 포함),
  `ROW_LABEL_OUTLIER`(읽힌 행 번호가 나머지 행의 증가/감소 규칙과 어긋남, 값은 바꾸지 않고 알리기만 함),
  `ROW_NUMBER_DUPLICATED`(같은 구역 안에서 중복), `SECTION_SPLIT_UNCERTAIN`, `AISLE_DETECTED`, `BLOCKS_DISCARDED`(제외한 비슷한 요소가 좌석 수의 20%/3개 초과).
  `ROW_*`와 `BLOCKS_DISCARDED`가 있으면 프론트에서 "확인 필요" 표시를 권장.
- 붙은 좌석 덩어리: 색이 칠려 있는 좌석은 가장자리 반투명 픽셀 때문에 이웃과 한 요소로 붙는다(회색 좌석은 틈이 배경색에 가까워 떨어진다).
  좌석보다 큰 요소는 농도가 평평한 "몸통"으로 다시 나눠 보고, 그래도 붙어 있으면 이웃 좌석의 피치로 격자 분할한다.
  분할 결과가 좌석 크기와 맞지 않으면(무대 글자, 층 배지, 범례) 통째로 제외한다. 색 값은 매핑하지 않는다(농도 대비만 사용).
- 좌석 크기는 절대값이 아니라 "가장 흔한 블록 크기"(같은 크기 8개 이상)를 기준으로 ±(0.75~1.35배)만 인정한다. 배경색은 이미지
  가장자리에서 추정한다(흰색 계열이 기본, 어두운 배경도 가능).
- 처리 상한: 해상도 12MP(변 8000px), 연결요소 20만 개, 좌석 블록 6000개, 행 300개 -> 넘으면 413/422 `IMAGE_TOO_LARGE`/`IMAGE_TOO_COMPLEX`.
  동시에 인식하는 이미지는 2장, 자리를 5초 안에 못 얻으면 503 `BUSY`. 16비트 PNG는 8비트로, 알파 PNG는 흰 배경에 합성해 처리한다.

## 오류 코드

| HTTP | code | 의미 |
|---|---|---|
| 400 | `BAD_REQUEST` | 요청 형식 오류 |
| 400 | `UNSUPPORTED_SITE` | 지원하지 않는 사이트 |
| 400 | `INVALID_PRODUCT_ID` | productId가 숫자 형식이 아님 |
| 400 | `IMAGE_URL_NOT_ALLOWED` | 허용 호스트가 아니거나 https가 아닌 이미지 주소 |
| 400 | `FETCH_BLOCKED` | 요청 안전 규칙 위반(리다이렉트가 허용 밖, 내부 IP로 해석 등) — 요청하지 않음 |
| 401 | `UNAUTHORIZED` | `SEATMAP_INTERNAL_KEY` 설정 시 `X-Internal-Key` 누락/불일치 |
| 404 / 405 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` | 없는 경로 / 허용되지 않는 메서드 (프레임워크 기본 형식이 아니라 같은 `{code,message}`) |
| 413 | `IMAGE_TOO_LARGE` | 업로드 10MB 초과 또는 해상도 상한 초과(12MP/변 8000px) |
| 413 | `REQUEST_TOO_LARGE` | JSON 요청 본문이 16KB 초과 |
| 415 | `UNSUPPORTED_IMAGE` | PNG/JPEG/WebP가 아니거나 헤더 불일치·디코딩 실패 |
| 422 | `SEATMAP_NOT_AVAILABLE` | 사이트가 좌석맵을 로그인·캡차 없이 공개하지 않음 → 업로드/이미지 주소 입력 안내 |
| 422 | `NO_SEATS_DETECTED` | 이미지에서 좌석 블록을 찾지 못함 |
| 422 | `IMAGE_TOO_COMPLEX` | 연결요소/블록/행 수 상한 초과 (좌석맵이 아니거나 노이즈가 많음) |
| 429 | `RATE_LIMITED` | 호스트당 분당 20건 초과 (아래 한계 참고) |
| 502 | `UPSTREAM_FETCH_FAILED` | 외부 사이트 오류/타임아웃/크기·Content-Type 위반 |
| 503 | `BUSY` | 동시 인식 한도(2장) 때문에 5초 안에 처리 자리를 못 얻음 (재시도 가능) |
| 500 | `INTERNAL_ERROR` | 그 외 (원인은 서버 로그에만 남고 응답에는 노출하지 않음) |

백엔드 권장 처리: 422 `SEATMAP_NOT_AVAILABLE`는 오류가 아니라 "수동 입력 경로로 안내"로 취급. 502/429/503은 재시도 가능.
타임아웃은 백엔드 쪽에서 60초 이상으로 둘 것 (외부 요청 최대 20초 + 대기 5초 + 인식·OCR 최대 약 30초).

차단·외부 오류(FETCH_BLOCKED, 429 이상)와 처리되지 않은 예외는 서버 로그(logger `seatmap`)에 WARNING/ERROR로 남는다.
`/corrections`(오류 신고)는 이 서비스에 **없다**(제거). 신고 접수·"동일 정정 2건 이상이면 자동 반영" 판정·저장은 Spring Boot가 담당한다.

## 외부 요청 안전 규칙 (app/safe_fetch.py)
https·443만, 어댑터가 선언한 호스트 정확 일치, DNS 해석된 모든 IP가 공인 주소여야 함(사설·루프백·링크로컬·메타데이터·
CGNAT·예약·멀티캐스트·IPv4-mapped 차단), 검증한 IP로 직접 접속(DNS rebinding 방지), 리다이렉트 수동 처리(최대 3회, 매 hop 재검증),
응답 크기(HTML 2MB / 이미지 10MB), 식별 가능한 User-Agent(`SeatSwapBot/0.1 ...`), 호스트당 분당 20건 제한.
Content-Type은 이미지가 `image/png`·`image/jpeg`·`image/webp`와 **정확히 일치**할 때만 받는다(svg+xml, gif 등 거부), 상품 페이지는 `text/html`.
검증을 통과한 IP가 여러 개면 순서대로 최대 4개까지(총 데드라인 안에서) 접속을 시도한다(예: IPv6 라우팅 불가 시 IPv4로 폴백).
검증하지 않은 IP로는 절대 접속하지 않는다. 실패한 시도의 소켓은 바로 닫는다.
요청 경로의 비ASCII 문자(예: 한글 리다이렉트 Location)는 퍼센트 인코딩해서 보내고, 그래도 요청을 만들 수 없으면(ValueError 등)
500이 아니라 502 `UPSTREAM_FETCH_FAILED`로 응답한다.

시간 제한: **DNS 해석 + 접속 + 헤더 + 본문을 합친 총 20초 데드라인**을 모든 단계가 공유한다. DNS는 별도 스레드에서 5초 타임아웃,
접속 5초, 이후 소켓 읽기 타임아웃은 매번 `min(10초, 남은 시간)`으로 줄이며, 본문은 16KB 단위로 읽으며 매 반복 데드라인을 검사한다.
추가로 워치독 타이머가 데드라인에 소켓을 강제로 닫아, 헤더나 본문을 한 바이트씩 흘려보내는 slow-loris 응답도 20초를 넘기지 못한다.
알려진 한계: 타임아웃된 DNS 조회 스레드는 `getaddrinfo`가 끝날 때까지 남는다(스레드 풀 4개).

**빈도 제한의 한계**: 프로세스 메모리 안에서 호스트별 60초 슬라이딩 윈도로 센다. `safe_get` 호출 1회가 호스트당 1건(리다이렉트 hop마다 세지 않음).
uvicorn worker를 N개로 늘리면 한도도 N배가 되고, 재시작하면 초기화되며, 호출한 사용자별 공정성은 없다.
사용자별 제한이 필요하면 백엔드(Spring Boot)에서 걸어야 한다.

## 현재 한계 (Phase 0 실측, 2026-10-06, 멜론 prodId=213480)
공개 상품 페이지에는 개별 좌석맵 이미지가 없다 (포스터·할인표·공지·앱 홍보 이미지만 있음). 개별 좌석도는 회차 선택 ->
예매 팝업 -> 보안문자 흐름 뒤에서만 나오므로 우회하지 않는다. 따라서 멜론 `analyze`/`discover`는 현재 대부분
422 `SEATMAP_NOT_AVAILABLE`이며, 실제 사용 경로는 `recognize`(업로드) / `recognize-url`이다.

## 확장 자리
`SiteAdapter.parse_performance_info()`가 3단계(공연정보 제목·공연장·회차 읽기)용 자리표시로 있다. 아직 아무 어댑터도 구현하지 않았다.
