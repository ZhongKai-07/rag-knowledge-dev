"""pydantic-settings 配置，从环境变量读取百炼 API key 等敏感值。"""
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # 百炼 API 配置
    dashscope_api_key: str = Field(..., description="百炼 API key，从 env 注入")
    dashscope_base_url: str = Field(
        default="https://dashscope.aliyuncs.com/compatible-mode/v1",
    )

    # 评测用模型（评价 RAG 输出质量）
    evaluator_chat_model: str = Field(default="qwen3.5-flash")
    evaluator_embedding_model: str = Field(default="text-embedding-v3")

    # 合成用强模型（生成 Gold Set 问答对）
    synthesis_strong_model: str = Field(default="qwen-max")
