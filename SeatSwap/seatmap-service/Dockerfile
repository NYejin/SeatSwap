FROM python:3.11-slim

# OpenCV + Tesseract OCR 런타임 의존성
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr tesseract-ocr-kor \
    libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# 로그 즉시 출력, Tesseract 스레드 1개(요청당 CPU 폭주 방지), 컨테이너는 비root로 실행
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 OMP_THREAD_LIMIT=1
RUN useradd --system --uid 10001 --no-create-home app

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
USER app

EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
