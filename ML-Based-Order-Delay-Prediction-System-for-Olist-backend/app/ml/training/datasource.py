"""Bounded training-data loading from inline rows or dataset-service CSV export."""

from __future__ import annotations

import hashlib
import io
import json
import tempfile
from dataclasses import dataclass
from typing import Any, Iterable
from urllib.parse import quote, urlencode

import numpy as np
import pandas as pd

CHUNK_SIZE = 1024 * 1024
MAX_EXPORT_BYTES = 256 * 1024 * 1024


class DatasetSourceError(RuntimeError):
    """Raised when an internal dataset export cannot be read safely."""

    def __init__(self, message: str, *, status_code: int = 422) -> None:
        super().__init__(message)
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class DatasetExportSource:
    dataset_version_id: str
    table_id: str
    owner_id: str
    columns: tuple[str, ...]
    max_rows: int = 100_000


@dataclass(frozen=True, slots=True)
class LoadedTrainingData:
    frame: pd.DataFrame
    fingerprint: str
    row_count: int
    source: dict[str, Any]


def load_training_data(
    *,
    rows: Iterable[dict[str, Any]] | None,
    source: DatasetExportSource | None,
    required_columns: Iterable[str],
    max_rows: int,
    max_cells: int,
    timeout_seconds: float = 120.0,
    max_bytes: int = MAX_EXPORT_BYTES,
    http_client: Any | None = None,
) -> LoadedTrainingData:
    columns = tuple(dict.fromkeys(required_columns))
    if not columns:
        raise DatasetSourceError("训练字段不能为空")
    if (rows is None) == (source is None):
        raise DatasetSourceError("必须且只能提供 rows 或 dataSource")

    if rows is not None:
        frame = pd.DataFrame(list(rows))
        _validate_columns(frame, columns)
        frame = frame.loc[:, list(columns)].reset_index(drop=True)
        _validate_bounds(frame, max_rows=max_rows, max_cells=max_cells)
        return LoadedTrainingData(
            frame=frame,
            fingerprint=_frame_fingerprint(frame),
            row_count=len(frame),
            source={"type": "inlineRows", "rowCount": int(len(frame))},
        )

    assert source is not None
    return _load_export(
        source=source,
        columns=columns,
        max_rows=max_rows,
        max_cells=max_cells,
        timeout_seconds=timeout_seconds,
        max_bytes=max_bytes,
        http_client=http_client,
    )


def _load_export(
    *,
    source: DatasetExportSource,
    columns: tuple[str, ...],
    max_rows: int,
    max_cells: int,
    timeout_seconds: float,
    max_bytes: int,
    http_client: Any | None,
) -> LoadedTrainingData:
    from app.config import settings

    token = (settings.model_training_internal_token or "").strip()
    if not token:
        raise DatasetSourceError("训练数据导出令牌尚未配置", status_code=503)
    if source.max_rows < 1 or source.max_rows > max_rows:
        raise DatasetSourceError(f"dataSource.maxRows 必须在 1 到 {max_rows} 之间")
    if set(source.columns) != set(columns):
        raise DatasetSourceError("dataSource.columns 必须与训练字段完全一致")

    base_url = settings.dataset_service_url.rstrip("/")
    if not base_url.startswith(("http://", "https://")):
        raise DatasetSourceError("数据集服务地址配置无效", status_code=503)
    query = urlencode(
        {
            "ownerId": source.owner_id,
            "columns": ",".join(source.columns),
            "maxRows": source.max_rows,
        }
    )
    url = (
        f"{base_url}/internal/model-training/dataset-versions/"
        f"{quote(str(source.dataset_version_id), safe='')}/tables/"
        f"{quote(str(source.table_id), safe='')}/export.csv?{query}"
    )
    headers = {"X-Model-Training-Token": token, "Accept": "text/csv"}
    client = http_client
    owns_client = client is None
    if owns_client:
        import httpx

        client = httpx.Client(
            timeout=httpx.Timeout(
                connect=min(10.0, timeout_seconds),
                read=timeout_seconds,
                write=timeout_seconds,
                pool=min(10.0, timeout_seconds),
            ),
            follow_redirects=False,
        )
    digest = hashlib.sha256()
    total_bytes = 0
    try:
        with tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024, mode="w+b") as buffer:
            with client.stream("GET", url, headers=headers) as response:
                if response.status_code != 200:
                    detail = _upstream_detail(response)
                    raise DatasetSourceError(
                        f"读取训练数据导出失败：{detail}",
                        status_code=_upstream_status(response.status_code),
                    )
                for chunk in response.iter_bytes(CHUNK_SIZE):
                    total_bytes += len(chunk)
                    if total_bytes > max_bytes:
                        raise DatasetSourceError("训练数据导出超过 256 MB 上限")
                    digest.update(chunk)
                    buffer.write(chunk)
            if total_bytes == 0:
                raise DatasetSourceError("训练数据导出为空")
            buffer.seek(0)
            frame = _read_csv(buffer, columns=columns, max_rows=max_rows)
    except DatasetSourceError:
        raise
    except Exception as error:
        raise DatasetSourceError(f"读取训练数据导出失败：{type(error).__name__}") from error
    finally:
        if owns_client:
            client.close()

    _validate_bounds(frame, max_rows=max_rows, max_cells=max_cells)
    return LoadedTrainingData(
        frame=frame,
        fingerprint=digest.hexdigest(),
        row_count=len(frame),
        source={
            "type": "datasetExport",
            "datasetVersionId": source.dataset_version_id,
            "tableId": source.table_id,
            "rowCount": int(len(frame)),
            "bytes": int(total_bytes),
            "sha256": digest.hexdigest(),
        },
    )


def _read_csv(buffer: Any, *, columns: tuple[str, ...], max_rows: int) -> pd.DataFrame:
    try:
        frame = pd.read_csv(
            buffer,
            usecols=list(columns),
            nrows=max_rows + 1,
            encoding="utf-8-sig",
            low_memory=False,
        )
    except pd.errors.EmptyDataError:
        frame = pd.DataFrame(columns=list(columns))
    except ValueError as error:
        message = str(error)
        if "Usecols do not match columns" in message or "Missing column provided" in message:
            raise DatasetSourceError("训练数据导出缺少所选字段") from error
        if "duplicate name" in message.lower():
            raise DatasetSourceError("训练数据导出存在重名字段") from error
        raise
    if len(frame) > max_rows:
        raise DatasetSourceError(f"训练数据超过 {max_rows} 行上限")
    missing = [name for name in columns if name not in frame.columns]
    if missing:
        raise DatasetSourceError(f"训练数据缺少所选字段：{', '.join(missing)}")
    if frame.columns.duplicated().any():
        raise DatasetSourceError("训练数据存在重名字段")
    return frame.loc[:, list(columns)].reset_index(drop=True)


def _validate_columns(frame: pd.DataFrame, columns: tuple[str, ...]) -> None:
    if frame.columns.duplicated().any():
        raise DatasetSourceError("训练数据存在重名字段")
    missing = [name for name in columns if name not in frame.columns]
    if missing:
        raise DatasetSourceError(f"训练数据缺少所选字段：{', '.join(missing)}")
    extra = [name for name in frame.columns if name not in columns]
    if extra:
        raise DatasetSourceError("训练数据包含未选择的字段")


def _validate_bounds(frame: pd.DataFrame, *, max_rows: int, max_cells: int) -> None:
    if len(frame) > max_rows:
        raise DatasetSourceError(f"训练数据超过 {max_rows} 行上限")
    if len(frame) * len(frame.columns) > max_cells:
        raise DatasetSourceError("训练数据超过行数与字段数资源上限")


def _frame_fingerprint(frame: pd.DataFrame) -> str:
    hashed = pd.util.hash_pandas_object(frame, index=True).to_numpy(dtype=np.uint64)
    digest = hashlib.sha256()
    digest.update(json.dumps(list(frame.columns), ensure_ascii=False).encode("utf-8"))
    digest.update(hashed.tobytes())
    return digest.hexdigest()


def _upstream_status(status_code: int) -> int:
    if status_code in (401, 403):
        return 502
    if status_code == 404:
        return 404
    if status_code in (409, 422):
        return status_code
    return 502


def _upstream_detail(response: Any) -> str:
    try:
        payload = response.json()
    except Exception:
        return f"HTTP {response.status_code}"
    if isinstance(payload, dict):
        return str(payload.get("message") or payload.get("detail") or payload.get("error") or response.status_code)
    return f"HTTP {response.status_code}"
