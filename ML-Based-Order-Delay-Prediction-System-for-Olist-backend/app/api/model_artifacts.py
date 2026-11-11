"""Internal, owner-scoped model artifact inspection and export."""

from __future__ import annotations

import tempfile
from typing import Any, Iterator
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse

from app.api.model_training import get_artifact_store, require_internal_training_token
from app.ml.training.artifacts import ModelArtifactStore

router = APIRouter(prefix="/internal/v1/model-artifacts", include_in_schema=False)
CHUNK_SIZE = 1024 * 1024


@router.get("/{artifact_id}/manifest")
def artifact_manifest(
    artifact_id: UUID,
    owner_id: str = Query(alias="ownerId", min_length=1, max_length=255),
    _: None = Depends(require_internal_training_token),
    artifact_store: ModelArtifactStore = Depends(get_artifact_store),
) -> dict[str, Any]:
    try:
        return artifact_store.manifest(str(artifact_id), owner_id=owner_id)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail="模型产物不存在") from error
    except RuntimeError as error:
        raise HTTPException(status_code=409, detail="模型产物完整性校验失败") from error


@router.get("/{artifact_id}/export.zip")
def export_artifact(
    artifact_id: UUID,
    owner_id: str = Query(alias="ownerId", min_length=1, max_length=255),
    _: None = Depends(require_internal_training_token),
    artifact_store: ModelArtifactStore = Depends(get_artifact_store),
) -> StreamingResponse:
    output = tempfile.TemporaryFile()
    try:
        artifact_store.write_bundle(str(artifact_id), owner_id=owner_id, output=output)
    except FileNotFoundError as error:
        output.close()
        raise HTTPException(status_code=404, detail="模型产物不存在") from error
    except RuntimeError as error:
        output.close()
        raise HTTPException(status_code=409, detail="模型产物完整性校验失败") from error
    except Exception:
        output.close()
        raise
    output.seek(0)
    return StreamingResponse(
        _iter_file(output),
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="model-{artifact_id}.zip"'},
    )


def _iter_file(output: Any) -> Iterator[bytes]:
    try:
        while chunk := output.read(CHUNK_SIZE):
            yield chunk
    finally:
        output.close()
