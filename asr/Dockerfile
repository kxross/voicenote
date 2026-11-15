FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
    fastapi==0.104.1 \
    uvicorn==0.24.0 \
    python-multipart==0.0.6 \
    wyoming==1.5.3 \
    httpx==0.27.2

COPY whisper_http_wrapper.py /app/

EXPOSE 5000

CMD ["python", "whisper_http_wrapper.py"]
