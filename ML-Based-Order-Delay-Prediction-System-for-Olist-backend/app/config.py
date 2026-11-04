from pathlib import Path
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_prefix="OLIST_",
        extra="ignore",
    )

    service_name: str = "model-serving"
    model_name: str = "order-delay-model"
    model_alias: str = "Production"
    mlflow_tracking_uri: str = "http://localhost:5000"
    object_store_endpoint: str = "http://localhost:4566"
    object_store_bucket: str = "olist-artifacts"
    rule_run_api_base_url: str = "http://localhost:8082"
    rule_run_api_token: Optional[str] = None
    rule_run_api_timeout_seconds: float = 30.0
    rule_execution_enabled: bool = False
    clickhouse_host: str = "localhost"
    clickhouse_port: int = 8123
    clickhouse_username: str = "default"
    clickhouse_password: str = ""
    clickhouse_secure: bool = False
    rule_preview_max_sample_rows: int = 5000
    rule_query_max_rows_to_read: int = 1_000_000
    rule_query_max_bytes_to_read: int = 268_435_456
    rule_query_max_execution_time_seconds: int = 30
    rule_output_max_rows: int = 100_000
    rule_output_max_bytes: int = 268_435_456
    model_training_internal_token: Optional[str] = None
    model_artifact_directory: Path = Path("./data/models")


settings = Settings()
