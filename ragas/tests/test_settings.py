import os
from unittest.mock import patch

import pytest


def test_settings_reads_env_vars(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "sk-test-xxx")
    monkeypatch.setenv("EVALUATOR_CHAT_MODEL", "qwen3.5-flash")
    monkeypatch.setenv("SYNTHESIS_STRONG_MODEL", "qwen-max")

    # 新鲜 import 绕过 lru_cache
    from ragas.settings import Settings
    settings = Settings()

    assert settings.dashscope_api_key == "sk-test-xxx"
    assert settings.evaluator_chat_model == "qwen3.5-flash"
    assert settings.synthesis_strong_model == "qwen-max"
    assert settings.dashscope_base_url.startswith("https://dashscope")


def test_settings_missing_api_key_raises(monkeypatch):
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    from ragas.settings import Settings
    with pytest.raises(Exception):
        Settings()
