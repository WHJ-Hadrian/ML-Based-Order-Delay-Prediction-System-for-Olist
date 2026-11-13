"""通用报告渲染：任何分析结果都可以按统一文档结构渲染成 PDF / Word / HTML。"""
from __future__ import annotations

import io
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from app.api.analysis_reports import MEDIA_TYPES, _render_docx, _render_html, _render_pdf
from app.api.model_training import require_internal_training_token

router = APIRouter(prefix="/internal/v1/report-renders", include_in_schema=False)


class ReportSection(BaseModel):
    model_config = ConfigDict(extra="forbid")

    heading: str = Field(min_length=1, max_length=200)
    paragraphs: list[str] = Field(default_factory=list)
    table: list[list[str]] | None = None


class ReportRenderRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1, max_length=200)
    format: str = Field(default="pdf", pattern="^(pdf|docx|html)$")
    meta: list[list[str]] = Field(default_factory=list)
    sections: list[ReportSection] = Field(default_factory=list)


@router.post("")
def render_report(
    request: ReportRenderRequest,
    _: None = Depends(require_internal_training_token),
) -> StreamingResponse:
    document = {
        "title": request.title,
        "generatedAt": datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S"),
        "sections": [
            {
                "heading": section.heading,
                "paragraphs": [*request_meta_lines(request.meta), *section.paragraphs]
                if index == 0 else section.paragraphs,
                "table": section.table,
            }
            for index, section in enumerate(request.sections)
        ],
    }
    if request.format == "pdf":
        payload = _render_pdf(document)
    elif request.format == "docx":
        payload = _render_docx(document)
    else:
        payload = _render_html(document).encode("utf-8")
    extension = request.format
    headers = {
        "Content-Disposition": (
            f'attachment; filename="analysis-report.{extension}"; '
            f"filename*=UTF-8''{quote(request.title)}.{extension}"
        ),
        "Content-Length": str(len(payload)),
    }
    return StreamingResponse(io.BytesIO(payload), media_type=MEDIA_TYPES[extension], headers=headers)


def request_meta_lines(meta: list[list[str]]) -> list[str]:
    if not meta:
        return []
    parts = [f"{row[0]}：{row[1]}" for row in meta if len(row) >= 2]
    return ["；".join(parts)] if parts else []


__all__ = ["router"]
