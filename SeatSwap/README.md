# SeatSwap — 로컬 실행 가이드

이 프로젝트는 세 가지 런타임(Java/Spring Boot, Node/React, Python/FastAPI)이
섞여 있어서, **Docker Compose로 한 번에 격리된 환경에서 띄우는 방법(권장)**과
**각 서비스를 개별 가상환경(venv, node_modules, gradle)에서 직접 띄우는 방법**
두 가지를 모두 제공합니다.

---

## 방법 A. Docker Compose로 한 번에 실행 (권장)

전체 스택(mysql + backend + seatmap-service + frontend)을 각각 격리된 컨테이너로
띄웁니다. 로컬에 Java/Node/Python을 설치할 필요가 없습니다.

### 사전 준비
- Docker Desktop 설치 (https://www.docker.com/products/docker-desktop/)

### 실행

```bash
cp .env.example .env    # 필요 시 DB_PASSWORD, JWT_SECRET 값 수정
docker compose up --build
```

### 접속
| 서비스 | 주소 |
|---|---|
| 프론트엔드 | http://localhost:5173 |
| 백엔드 API | http://localhost:8080/api |
| 좌석 인식 서비스 | http://localhost:8001 |
| MySQL | localhost:3306 (user: root) |

### 종료 / 초기화
```bash
docker compose down          # 컨테이너 종료
docker compose down -v       # 컨테이너 종료 + DB 데이터까지 삭제
```

### 코드 수정 반영
Dockerfile은 개발용으로 구성되어 있어 frontend/seatmap-service는 코드 변경이
바로 반영됩니다(volume mount 추가 시). backend는 재빌드가 필요합니다:
```bash
docker compose up --build backend
```

---

## 방법 B. 서비스별 개별 가상환경에서 실행

Docker 없이 각자 언어별 가상환경에서 직접 띄우는 방법입니다. 3개 터미널이 필요합니다.

### 0. 공통 — MySQL 준비
로컬에 MySQL 8을 설치하고 DB를 생성합니다.
```sql
CREATE DATABASE seatswap;
```

### 1. backend (Spring Boot)
Gradle Wrapper가 포함되어 있어 시스템에 Gradle을 따로 설치할 필요가 없습니다.
**반드시 `gradle`이 아니라 `./gradlew`(Windows는 `gradlew.bat`)를 사용하세요** — 버전 고정 및
VS Code 확장과의 충돌 방지를 위해 Wrapper로 통일했습니다.
```bash
cd backend
# 필요 시 환경변수로 DB 접속정보 오버라이드 (application.yml 참고)
export SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/seatswap
export SPRING_DATASOURCE_PASSWORD=changeme

./gradlew bootRun        # macOS/Linux/Git Bash
gradlew.bat bootRun      # Windows cmd/PowerShell
```
기본 포트: `8080`

> VS Code에서 "Cannot mutate the dependency attributes..." 같은 Gradle 동기화 에러가 난다면,
> `./gradlew`가 생긴 뒤에도 VS Code를 완전히 종료하고 java/gradle 프로세스를 정리한 뒤
> 다시 열어야 합니다 (Wrapper가 있어도 기존에 꼬인 캐시/데몬은 저절로 안 풀립니다).

### 2. seatmap-service (FastAPI) — Python 가상환경(venv)
```bash
cd seatmap-service
./setup_venv.sh        # Windows는 setup_venv.bat
source .venv/bin/activate   # Windows는 .venv\Scripts\activate.bat
uvicorn main:app --reload --port 8001
```
OpenCV/OCR 실행을 위해 시스템에 Tesseract가 설치되어 있어야 합니다:
- macOS: `brew install tesseract tesseract-lang`
- Ubuntu: `sudo apt-get install tesseract-ocr tesseract-ocr-kor`
- Windows: https://github.com/UB-Mannheim/tesseract/wiki 설치 후 PATH 등록

기본 포트: `8001`

### 3. frontend (React + Vite)
```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```
기본 포트: `5173`

---

## 폴더 구조
```
01_소스코드/
├── docker-compose.yml   # 방법 A 진입점
├── .env.example
├── backend/             # Spring Boot (Dockerfile 포함)
├── frontend/             # React + Vite (Dockerfile 포함)
└── seatmap-service/      # FastAPI + OpenCV/OCR (Dockerfile, venv 스크립트 포함)
```

## 자주 겪는 문제
| 증상 | 원인/해결 |
|---|---|
| backend가 mysql 연결 실패 | Docker Compose는 healthcheck로 mysql 기동 후 backend가 뜨도록 되어 있음. 개별 실행 시 MySQL이 먼저 켜져 있는지 확인 |
| seatmap-service에서 OCR 에러 | Tesseract 시스템 설치 누락 — 위 설치 명령 참고 |
| frontend에서 API 호출 실패(CORS 등) | SecurityConfig에 CORS 설정 적용됨 (localhost:5173 허용). 배포 시 실제 프론트 도메인으로 교체 필요 |
| MySQL Workbench에서 `seatswap` 계정으로 접속 시 Access denied | MySQL은 `MYSQL_USER`/`MYSQL_PASSWORD` 환경변수를 **데이터 볼륨이 비어있는 최초 기동 시에만** 읽어서 계정을 만든다. 이미 떠 있던 환경을 업데이트했다면 `docker compose down -v`로 볼륨까지 지운 뒤 `docker compose up --build`로 다시 띄워야 `seatswap` 계정이 생성됨. (`-v` 없이 내리면 기존 볼륨이 남아 계정이 안 만들어짐) |
