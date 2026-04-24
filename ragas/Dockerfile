FROM python:3.11-slim

WORKDIR /app

# 装系统依赖（ragas 需要 gcc 编译某些 wheel）
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY ragas ./ragas

EXPOSE 9091

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:9091/health').read()"

CMD ["uvicorn", "ragas.app:app", "--host", "0.0.0.0", "--port", "9091"]
