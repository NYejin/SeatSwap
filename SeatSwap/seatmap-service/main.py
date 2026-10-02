from fastapi import FastAPI
from app.api import seatmap

app = FastAPI(title="SeatSwap Seatmap Recognition Service")
app.include_router(seatmap.router, prefix="/api/seatmap", tags=["seatmap"])

# Spring Boot 메인 서버와 분리된 별도 프로세스로 실행 (결정사항, spring-boot-conventions 스킬 참고)
