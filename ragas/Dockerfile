FROM python:3.11-slim

WORKDIR /app

# 装系统依赖（ragas 需要 gcc 编译某些 wheel）；切清华源避免国内代理干扰
RUN sed -i 's|http://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g; s|http://security.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true \
    && apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
# pip 同样切清华源；HTTPS 镜像直连，无需代理
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt

COPY ragas ./ragas

EXPOSE 9091

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:9091/health').read()"

# --loop asyncio (NOT uvloop): RAGAS 0.2.x calls nest_asyncio.apply() at import time,
# which fails on uvloop ("Can't patch loop of type <class 'uvloop.Loop'>").
CMD ["uvicorn", "ragas.app:app", "--host", "0.0.0.0", "--port", "9091", "--loop", "asyncio"]
