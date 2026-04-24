"""ragent-eval 本地包。从 PyPI ragas distribution 读取版本号，供 /health 展示。

本地包目录（/app/ragas/）会 shadow PyPI `ragas` 的 `__init__.py`，所以不能靠
`import ragas; ragas.__version__` —— 那取到的是本文件。改用 importlib.metadata
查 dist-info 元数据，绕过 sys.path 优先级问题。

本地 dev 环境（未 `pip install ragas`）会走 fallback "unknown"，符合预期。
Docker 镜像内 requirements.txt 装了 ragas==0.2.*，会返回真实版本号。
"""
try:
    from importlib.metadata import version as _version

    __version__ = _version("ragas")
except Exception:
    __version__ = "unknown"
