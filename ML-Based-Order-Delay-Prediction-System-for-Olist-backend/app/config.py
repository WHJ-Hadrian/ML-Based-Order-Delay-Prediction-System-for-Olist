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


settings = Settings()
