#!/usr/bin/env bash
# Python 가상환경(venv) 생성 + 의존성 설치 (macOS/Linux)
set -e
cd "$(dirname "$0")"

python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

echo ""
echo "가상환경 준비 완료. 다음으로 서버를 실행하세요:"
echo "  source .venv/bin/activate"
echo "  uvicorn main:app --reload --port 8001"
echo ""
echo "※ OCR 사용을 위해 시스템에 Tesseract가 설치되어 있어야 합니다:"
echo "   macOS: brew install tesseract tesseract-lang"
echo "   Ubuntu: sudo apt-get install tesseract-ocr tesseract-ocr-kor"
