@echo off
REM Python 가상환경(venv) 생성 + 의존성 설치 (Windows)
cd /d "%~dp0"

python -m venv .venv
call .venv\Scripts\activate.bat
pip install --upgrade pip
pip install -r requirements.txt

echo.
echo 가상환경 준비 완료. 다음으로 서버를 실행하세요:
echo   .venv\Scripts\activate.bat
echo   uvicorn main:app --reload --port 8001
echo.
echo ※ OCR 사용을 위해 시스템에 Tesseract가 설치되어 있어야 합니다.
echo    https://github.com/UB-Mannheim/tesseract/wiki 에서 설치 후 PATH 등록
