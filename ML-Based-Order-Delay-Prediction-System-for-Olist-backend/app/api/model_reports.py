"""Internal model report generation in PDF, Word, HTML, JSON and CSV formats."""

from __future__ import annotations

import csv
import html
import io
import json
import re
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from app.api.model_training import get_artifact_store, require_internal_training_token
from app.ml.training.artifacts import ModelArtifactStore

router = APIRouter(prefix="/internal/v1/model-reports", include_in_schema=False)
ALL_SECTIONS = ("overview", "comparison", "detail", "importance", "sample", "conclusion")
MEDIA_TYPES = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "html": "text/html; charset=utf-8",
    "json": "application/json; charset=utf-8",
    "csv": "text/csv; charset=utf-8",
}
EXTENSIONS = {"pdf": "pdf", "docx": "docx", "html": "html", "json": "json", "csv": "csv"}
CJK_FONT = "STSong-Light"
ALGORITHM_LABELS = {
    "random_forest": "随机森林",
    "logistic_regression": "逻辑回归",
    "decision_tree": "决策树",
    "xgboost": "XGBoost",
    "lightgbm": "LightGBM",
    "svm": "支持向量机",
    "knn": "K 近邻",
    "ridge_regression": "岭回归",
    "linear_regression": "线性回归",
    "xgboost_regression": "XGBoost 回归",
    "lightgbm_regression": "LightGBM 回归",
}


class ReportComparisonRow(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    name: str | None = None
    algorithm: str | None = None
    version: str | None = None
    metrics: dict[str, float] = Field(default_factory=dict)
    metric_details: dict[str, Any] = Field(default_factory=dict, alias="metricDetails")
    decision_threshold: float | None = Field(default=None, alias="decisionThreshold")


class ModelReportRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    owner_id: str = Field(alias="ownerId", min_length=1, max_length=255)
    artifact_id: UUID = Field(alias="artifactId")
    format: str = Field(default="pdf", pattern="^(pdf|docx|html|json|csv)$")
    sections: list[str] = Field(default_factory=lambda: list(ALL_SECTIONS), max_length=20)
    title: str = Field(default="模型评估报告", min_length=1, max_length=200)
    model_name: str | None = Field(default=None, alias="modelName", max_length=200)
    model_version: str | None = Field(default=None, alias="modelVersion", max_length=100)
    comparison: list[ReportComparisonRow] = Field(default_factory=list, max_length=50)
    sample_explanation: dict[str, Any] | None = Field(default=None, alias="sampleExplanation")


@router.post("")
def create_model_report(
    request: ModelReportRequest,
    _: None = Depends(require_internal_training_token),
    artifact_store: ModelArtifactStore = Depends(get_artifact_store),
) -> StreamingResponse:
    try:
        manifest = artifact_store.manifest(str(request.artifact_id), owner_id=request.owner_id)
    except FileNotFoundError as error:
        raise HTTPException(status_code=404, detail="模型产物不存在") from error
    except RuntimeError as error:
        raise HTTPException(status_code=409, detail="模型产物完整性校验失败") from error

    sections = [name for name in ALL_SECTIONS if name in set(request.sections)] or list(ALL_SECTIONS)
    document = _build_document(manifest, request, sections)
    try:
        if request.format == "pdf":
            payload = _render_pdf(document)
        elif request.format == "docx":
            payload = _render_docx(document)
        elif request.format == "html":
            payload = _render_html(document).encode("utf-8")
        elif request.format == "json":
            payload = json.dumps(document, ensure_ascii=False, indent=2, default=str).encode("utf-8")
        else:
            payload = _render_csv(document).encode("utf-8-sig")
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail=str(error)) from error

    extension = EXTENSIONS[request.format]
    filename = f"model-report-{request.artifact_id}.{extension}"
    safe_title = re.sub(r'[\\/:*?"<>|\r\n]+', "_", request.title).strip() or "model-report"
    download_name = f"{safe_title}.{extension}"
    return StreamingResponse(
        io.BytesIO(payload),
        media_type=MEDIA_TYPES[request.format],
        headers={
            "Content-Disposition": (
                f'attachment; filename="{filename}"; '
                f"filename*=UTF-8''{quote(download_name)}"
            ),
            "Content-Length": str(len(payload)),
        },
    )


def _build_document(
    manifest: dict[str, Any], request: ModelReportRequest, sections: list[str]
) -> dict[str, Any]:
    explainability = manifest.get("explainability") or {}
    metric_details = manifest.get("metricDetails") or {}
    return {
        "title": request.title,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sections": sections,
        "model": {
            "name": request.model_name,
            "version": request.model_version,
            "artifactId": manifest.get("artifactId"),
            "algorithm": manifest.get("algorithm"),
            "algorithmLabel": ALGORITHM_LABELS.get(str(manifest.get("algorithm")), manifest.get("algorithm")),
            "task": manifest.get("task"),
            "targetColumn": manifest.get("targetColumn"),
            "featureColumns": manifest.get("featureColumns", []),
            "featureTypes": manifest.get("featureTypes", {}),
            "datasetVersionId": manifest.get("datasetVersionId"),
            "createdAt": manifest.get("createdAt"),
            "trainRows": manifest.get("trainRows"),
            "calibrationRows": manifest.get("calibrationRows"),
            "validationRows": manifest.get("validationRows"),
            "testRows": manifest.get("testRows"),
            "testSize": manifest.get("testSize"),
            "randomState": manifest.get("randomState"),
            "decisionThreshold": manifest.get("decisionThreshold"),
            "trainingSeconds": manifest.get("trainingSeconds"),
        },
        "metrics": manifest.get("metrics", {}),
        "metricDetails": metric_details,
        "crossValidation": metric_details.get("crossValidation"),
        "confusionMatrix": manifest.get("confusionMatrix"),
        "validationMetrics": manifest.get("validationMetrics", {}),
        "featureImportance": explainability.get("globalImportance", []),
        "permutationImportance": explainability.get("permutationImportance", []),
        "explanationMethod": explainability.get("method"),
        "localExplanation": explainability.get("localExplanation"),
        "sampleExplanation": request.sample_explanation,
        "comparison": [
            row.model_dump(by_alias=True) for row in request.comparison
        ],
        "conclusion": _conclusion(manifest),
    }


def _conclusion(manifest: dict[str, Any]) -> list[str]:
    task = manifest.get("task")
    metrics = manifest.get("metrics") or {}
    details = manifest.get("metricDetails") or {}
    train_rows = manifest.get("trainRows") or 0
    test_rows = manifest.get("testRows") or 0
    notes: list[str] = []
    if task == "classification":
        auc = details.get("rocAuc") or metrics.get("roc_auc") or metrics.get("auc")
        recall = details.get("recall") or metrics.get("recall")
        precision = details.get("precision") or metrics.get("precision")
        f1 = details.get("f1") or metrics.get("f1")
        if auc is not None:
            notes.append(f"测试集 ROC-AUC 为 {float(auc):.4f}。")
        if recall is not None and precision is not None:
            notes.append(f"正类召回率 {float(recall):.2%}，精确率 {float(precision):.2%}。")
        if f1 is not None:
            notes.append(f"综合 F1 为 {float(f1):.4f}。")
    else:
        for key, label in (("mae", "MAE"), ("rmse", "RMSE"), ("r2", "R²"), ("mape", "MAPE")):
            value = details.get(key) if details.get(key) is not None else metrics.get(key)
            if value is not None:
                notes.append(f"测试集 {label} 为 {float(value):.4f}。")
    if train_rows and test_rows and test_rows / max(1, train_rows) < 0.15:
        notes.append("测试集占比偏小，指标波动可能较大，建议补充独立验证集。")
    notes.append("报告指标来自离线测试集，上线前仍需在真实业务分布上复核。")
    return notes


def _cv_rows(cross_validation: dict[str, Any]) -> list[tuple[str, Any, Any]]:
    means = cross_validation.get("mean") or {}
    stds = cross_validation.get("std") or {}
    if not isinstance(means, dict):
        return [(str(cross_validation.get("metric") or "指标"), means, cross_validation.get("std"))]
    labels = {"rocAuc": "ROC-AUC", "f1Macro": "宏平均 F1", "rmse": "RMSE", "r2": "R²"}
    keys = [key for key in ("rocAuc", "f1Macro", "rmse", "r2") if key in means]
    if not keys:
        keys = list(means)[:3]
    return [(labels.get(key, key), means.get(key), stds.get(key)) for key in keys]


def _render_pdf(document: dict[str, Any]) -> bytes:
    try:
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
        from reportlab.lib.units import mm
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        from reportlab.platypus import (
            PageBreak,
            Paragraph,
            SimpleDocTemplate,
            Spacer,
            Table,
            TableStyle,
        )
    except Exception as error:  # pragma: no cover - optional dependency
        raise RuntimeError("当前环境未安装 reportlab，无法生成 PDF 报告") from error

    pdfmetrics.registerFont(UnicodeCIDFont(CJK_FONT))
    buffer = io.BytesIO()
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "CJKTitle", parent=styles["Title"], fontName=CJK_FONT, fontSize=20, leading=26
    )
    heading_style = ParagraphStyle(
        "CJKHeading", parent=styles["Heading2"], fontName=CJK_FONT, fontSize=14, leading=20, spaceBefore=12
    )
    body_style = ParagraphStyle(
        "CJKBody", parent=styles["BodyText"], fontName=CJK_FONT, fontSize=9.5, leading=15
    )
    document_template = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=16 * mm,
        rightMargin=16 * mm,
        topMargin=16 * mm,
        bottomMargin=16 * mm,
        title=document.get("title") or "模型评估报告",
    )
    story: list[Any] = [
        Paragraph(_escape(document.get("title") or "模型评估报告"), title_style),
        Paragraph(f"生成时间：{_escape(str(document.get('generatedAt')))}", body_style),
        Spacer(1, 8),
    ]
    model = document.get("model") or {}
    story.append(Paragraph("一、模型概况", heading_style))
    overview_rows = [
        ["项目", "内容"],
        ["模型名称", _text(model.get("name"))],
        ["模型版本", _text(model.get("version"))],
        ["算法", _text(model.get("algorithmLabel") or model.get("algorithm"))],
        ["任务类型", "分类" if model.get("task") == "classification" else "回归"],
        ["目标字段", _text(model.get("targetColumn"))],
        ["特征数量", str(len(model.get("featureColumns") or []))],
        ["训练 / 测试行数", f"{model.get('trainRows')} / {model.get('testRows')}"],
        ["决策阈值", _text(model.get("decisionThreshold"))],
        ["数据版本", _text(model.get("datasetVersionId"))],
    ]
    story.append(_pdf_table(overview_rows, body_style, Table, TableStyle, colors, A4))

    sections = document.get("sections") or []
    if "detail" in sections or "overview" in sections:
        story.append(Paragraph("二、测试集指标", heading_style))
        metrics = document.get("metrics") or {}
        details = document.get("metricDetails") or {}
        rows = [["指标", "数值"]]
        for key, value in sorted(metrics.items()):
            rows.append([_metric_label(key), _number(value)])
        for key in ("rocAuc", "prAuc", "accuracy", "precision", "recall", "f1", "macroF1", "mae", "rmse", "r2", "mape"):
            if key in details and key not in metrics:
                rows.append([_metric_label(key), _number(details[key])])
        story.append(_pdf_table(rows, body_style, Table, TableStyle, colors, A4))
        confusion = document.get("confusionMatrix")
        if confusion:
            story.append(Paragraph("混淆矩阵", heading_style))
            story.append(_pdf_table(_matrix_rows(confusion), body_style, Table, TableStyle, colors, A4))
        cross_validation = document.get("crossValidation")
        if cross_validation:
            story.append(Paragraph("交叉验证", heading_style))
            rows = [["折数", "指标", "均值", "标准差"]]
            for label, mean, std in _cv_rows(cross_validation):
                rows.append([str(cross_validation.get("folds")), label, _number(mean), _number(std)])
            story.append(_pdf_table(rows, body_style, Table, TableStyle, colors, A4))

    comparison = document.get("comparison") or []
    if "comparison" in sections and comparison:
        story.append(Paragraph("三、模型对比", heading_style))
        rows = [["模型", "算法", "主指标", "阈值"]]
        for row in comparison:
            metrics = row.get("metrics") or {}
            details = row.get("metricDetails") or {}
            primary = details.get("rocAuc") or metrics.get("roc_auc") or metrics.get("auc")
            if primary is None:
                primary = details.get("r2") or metrics.get("r2")
            rows.append([
                _text(row.get("name")),
                _text(ALGORITHM_LABELS.get(str(row.get("algorithm")), row.get("algorithm"))),
                _number(primary),
                _text(row.get("decisionThreshold")),
            ])
        story.append(_pdf_table(rows, body_style, Table, TableStyle, colors, A4))

    importance = document.get("featureImportance") or document.get("permutationImportance") or []
    if "importance" in sections and importance:
        story.append(Paragraph("四、特征重要性", heading_style))
        rows = [["排名", "特征", "重要性"]]
        for item in importance[:15]:
            rows.append([
                str(item.get("rank")),
                _text(item.get("feature")),
                _number(item.get("importanceMean")),
            ])
        story.append(_pdf_table(rows, body_style, Table, TableStyle, colors, A4))

    sample = document.get("sampleExplanation") or document.get("localExplanation")
    if "sample" in sections and sample:
        story.append(Paragraph("五、单样本解释", heading_style))
        probability = sample.get("probability")
        if probability is not None:
            story.append(Paragraph(f"预测概率：{float(probability):.2%}", body_style))
        if sample.get("naturalLanguage"):
            story.append(Paragraph(_escape(str(sample["naturalLanguage"])), body_style))
        contributions = sample.get("contributions") or []
        if contributions:
            rows = [["特征", "取值", "贡献"]]
            for item in contributions[:10]:
                rows.append([
                    _text(item.get("label") or item.get("feature")),
                    _text(item.get("value")),
                    _number(item.get("contribution")),
                ])
            story.append(_pdf_table(rows, body_style, Table, TableStyle, colors, A4))
        counterfactuals = sample.get("counterfactuals") or []
        if counterfactuals:
            story.append(Paragraph("反事实建议", heading_style))
            for item in counterfactuals[:5]:
                story.append(Paragraph("• " + _escape(str(item.get("message"))), body_style))

    if "conclusion" in sections:
        story.append(Paragraph("六、评估结论", heading_style))
        for note in document.get("conclusion") or []:
            story.append(Paragraph("• " + _escape(str(note)), body_style))

    document_template.build(story)
    return buffer.getvalue()


def _pdf_table(rows: list[list[str]], style: Any, Table: Any, TableStyle: Any, colors: Any, page: Any) -> Any:
    width = page[0] - 32 * 2.834645669
    column_widths = [width * 0.24, width * 0.76] if len(rows[0]) == 2 else None
    table = Table(rows, colWidths=column_widths, repeatRows=1)
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, -1), CJK_FONT),
        ("FONTSIZE", (0, 0), (-1, -1), 8.5),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eef2ff")),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#1e293b")),
        ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#cbd5f5")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
    ]))
    return table


def _render_docx(document: dict[str, Any]) -> bytes:
    try:
        from docx import Document
        from docx.shared import Pt
    except Exception as error:  # pragma: no cover - optional dependency
        raise RuntimeError("当前环境未安装 python-docx，无法生成 Word 报告") from error

    word = Document()
    normal = word.styles["Normal"]
    normal.font.name = "宋体"
    normal.font.size = Pt(10)
    word.add_heading(document.get("title") or "模型评估报告", level=0)
    word.add_paragraph(f"生成时间：{document.get('generatedAt')}")
    model = document.get("model") or {}
    word.add_heading("一、模型概况", level=1)
    table = word.add_table(rows=0, cols=2)
    table.style = "Table Grid"
    for label, value in (
        ("模型名称", model.get("name")),
        ("模型版本", model.get("version")),
        ("算法", model.get("algorithmLabel") or model.get("algorithm")),
        ("任务类型", "分类" if model.get("task") == "classification" else "回归"),
        ("目标字段", model.get("targetColumn")),
        ("特征数量", len(model.get("featureColumns") or [])),
        ("训练 / 测试行数", f"{model.get('trainRows')} / {model.get('testRows')}"),
        ("决策阈值", model.get("decisionThreshold")),
        ("数据版本", model.get("datasetVersionId")),
    ):
        cells = table.add_row().cells
        cells[0].text = str(label)
        cells[1].text = _text(value)

    word.add_heading("二、测试集指标", level=1)
    metrics_table = word.add_table(rows=1, cols=2)
    metrics_table.style = "Table Grid"
    metrics_table.rows[0].cells[0].text = "指标"
    metrics_table.rows[0].cells[1].text = "数值"
    details = document.get("metricDetails") or {}
    merged = dict(document.get("metrics") or {})
    for key in ("rocAuc", "prAuc", "accuracy", "precision", "recall", "f1", "macroF1", "mae", "rmse", "r2", "mape"):
        if key in details and key not in merged:
            merged[key] = details[key]
    for key, value in merged.items():
        cells = metrics_table.add_row().cells
        cells[0].text = _metric_label(key)
        cells[1].text = _number(value)

    confusion = document.get("confusionMatrix")
    if confusion:
        word.add_heading("混淆矩阵", level=2)
        matrix = _matrix_rows(confusion)
        grid = word.add_table(rows=0, cols=len(matrix[0]))
        grid.style = "Table Grid"
        for row in matrix:
            cells = grid.add_row().cells
            for index, value in enumerate(row):
                cells[index].text = str(value)

    cross_validation = document.get("crossValidation")
    if cross_validation:
        word.add_heading("交叉验证", level=2)
        for label, mean, std in _cv_rows(cross_validation):
            word.add_paragraph(
                f"{cross_validation.get('folds')} 折交叉验证 · {label} 均值 {_number(mean)}，"
                f"标准差 {_number(std)}。"
            )

    comparison = document.get("comparison") or []
    if "comparison" in (document.get("sections") or []) and comparison:
        word.add_heading("三、模型对比", level=1)
        grid = word.add_table(rows=1, cols=4)
        grid.style = "Table Grid"
        for index, label in enumerate(("模型", "算法", "主指标", "阈值")):
            grid.rows[0].cells[index].text = label
        for row in comparison:
            metrics = row.get("metrics") or {}
            detail = row.get("metricDetails") or {}
            primary = detail.get("rocAuc") or metrics.get("roc_auc") or metrics.get("auc")
            if primary is None:
                primary = detail.get("r2") or metrics.get("r2")
            cells = grid.add_row().cells
            cells[0].text = _text(row.get("name"))
            cells[1].text = _text(ALGORITHM_LABELS.get(str(row.get("algorithm")), row.get("algorithm")))
            cells[2].text = _number(primary)
            cells[3].text = _text(row.get("decisionThreshold"))

    importance = document.get("featureImportance") or document.get("permutationImportance") or []
    if "importance" in (document.get("sections") or []) and importance:
        word.add_heading("四、特征重要性", level=1)
        grid = word.add_table(rows=1, cols=3)
        grid.style = "Table Grid"
        for index, label in enumerate(("排名", "特征", "重要性")):
            grid.rows[0].cells[index].text = label
        for item in importance[:15]:
            cells = grid.add_row().cells
            cells[0].text = str(item.get("rank"))
            cells[1].text = _text(item.get("feature"))
            cells[2].text = _number(item.get("importanceMean"))

    sample = document.get("sampleExplanation") or document.get("localExplanation")
    if "sample" in (document.get("sections") or []) and sample:
        word.add_heading("五、单样本解释", level=1)
        if sample.get("probability") is not None:
            word.add_paragraph(f"预测概率：{float(sample['probability']):.2%}")
        if sample.get("naturalLanguage"):
            word.add_paragraph(str(sample["naturalLanguage"]))
        for item in (sample.get("counterfactuals") or [])[:5]:
            word.add_paragraph("• " + str(item.get("message")))

    word.add_heading("六、评估结论", level=1)
    for note in document.get("conclusion") or []:
        word.add_paragraph("• " + str(note))

    buffer = io.BytesIO()
    word.save(buffer)
    return buffer.getvalue()


def _render_html(document: dict[str, Any]) -> str:
    model = document.get("model") or {}
    parts = [
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">",
        f"<title>{html.escape(str(document.get('title') or '模型评估报告'))}</title>",
        "<style>body{font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;"
        "max-width:960px;margin:32px auto;padding:0 16px;color:#1f2937;line-height:1.7}"
        "h1{font-size:26px}h2{margin-top:28px;border-left:4px solid #2563eb;padding-left:8px}"
        "table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px}"
        "th,td{border:1px solid #cbd5e1;padding:6px 10px;text-align:left}"
        "th{background:#eef2ff}.muted{color:#64748b}</style></head><body>",
        f"<h1>{html.escape(str(document.get('title') or '模型评估报告'))}</h1>",
        f"<p class=\"muted\">生成时间：{html.escape(str(document.get('generatedAt')))}</p>",
        "<h2>一、模型概况</h2>",
        _html_table(
            ["项目", "内容"],
            [
                ["模型名称", model.get("name")],
                ["模型版本", model.get("version")],
                ["算法", model.get("algorithmLabel") or model.get("algorithm")],
                ["任务类型", "分类" if model.get("task") == "classification" else "回归"],
                ["目标字段", model.get("targetColumn")],
                ["特征数量", len(model.get("featureColumns") or [])],
                ["训练 / 测试行数", f"{model.get('trainRows')} / {model.get('testRows')}"],
                ["决策阈值", model.get("decisionThreshold")],
                ["数据版本", model.get("datasetVersionId")],
            ],
        ),
        "<h2>二、测试集指标</h2>",
    ]
    merged = dict(document.get("metrics") or {})
    details = document.get("metricDetails") or {}
    for key in ("rocAuc", "prAuc", "accuracy", "precision", "recall", "f1", "macroF1", "mae", "rmse", "r2", "mape"):
        if key in details and key not in merged:
            merged[key] = details[key]
    parts.append(_html_table(["指标", "数值"], [[_metric_label(k), _number(v)] for k, v in merged.items()]))
    confusion = document.get("confusionMatrix")
    if confusion:
        parts.append("<h3>混淆矩阵</h3>" + _html_table(*_matrix_rows(confusion)))
    cross_validation = document.get("crossValidation")
    if cross_validation:
        detail = "".join(
            f"<p>{cross_validation.get('folds')} 折 · {html.escape(label)} 均值 "
            f"{_number(mean)}，标准差 {_number(std)}。</p>"
            for label, mean, std in _cv_rows(cross_validation)
        )
        parts.append("<h3>交叉验证</h3>" + detail)
    comparison = document.get("comparison") or []
    if "comparison" in (document.get("sections") or []) and comparison:
        rows = []
        for row in comparison:
            metrics = row.get("metrics") or {}
            detail = row.get("metricDetails") or {}
            primary = detail.get("rocAuc") or metrics.get("roc_auc") or metrics.get("auc")
            if primary is None:
                primary = detail.get("r2") or metrics.get("r2")
            rows.append([
                row.get("name"),
                ALGORITHM_LABELS.get(str(row.get("algorithm")), row.get("algorithm")),
                _number(primary),
                row.get("decisionThreshold"),
            ])
        parts.append("<h2>三、模型对比</h2>" + _html_table(["模型", "算法", "主指标", "阈值"], rows))
    importance = document.get("featureImportance") or document.get("permutationImportance") or []
    if "importance" in (document.get("sections") or []) and importance:
        parts.append(
            "<h2>四、特征重要性</h2>"
            + _html_table(
                ["排名", "特征", "重要性"],
                [[item.get("rank"), item.get("feature"), _number(item.get("importanceMean"))] for item in importance[:15]],
            )
        )
    sample = document.get("sampleExplanation") or document.get("localExplanation")
    if "sample" in (document.get("sections") or []) and sample:
        block = ["<h2>五、单样本解释</h2>"]
        if sample.get("probability") is not None:
            block.append(f"<p>预测概率：{float(sample['probability']):.2%}</p>")
        if sample.get("naturalLanguage"):
            block.append(f"<p>{html.escape(str(sample['naturalLanguage']))}</p>")
        contributions = sample.get("contributions") or []
        if contributions:
            block.append(
                _html_table(
                    ["特征", "取值", "贡献"],
                    [[item.get("label") or item.get("feature"), item.get("value"), _number(item.get("contribution"))] for item in contributions[:10]],
                )
            )
        for item in (sample.get("counterfactuals") or [])[:5]:
            block.append(f"<p>• {html.escape(str(item.get('message')))}</p>")
        parts.append("".join(block))
    parts.append("<h2>六、评估结论</h2><ul>")
    for note in document.get("conclusion") or []:
        parts.append(f"<li>{html.escape(str(note))}</li>")
    parts.append("</ul></body></html>")
    return "".join(parts)


def _render_csv(document: dict[str, Any]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["section", "item", "metric", "value"])
    model = document.get("model") or {}
    for key, value in model.items():
        if isinstance(value, (list, dict)):
            continue
        writer.writerow(["model", key, "", value])
    for key, value in (document.get("metrics") or {}).items():
        writer.writerow(["metrics", "", key, value])
    for key, value in (document.get("metricDetails") or {}).items():
        if isinstance(value, (dict, list)):
            continue
        writer.writerow(["metricDetails", "", key, value])
    confusion = document.get("confusionMatrix")
    if confusion:
        labels = confusion.get("labels") or []
        matrix = confusion.get("matrix") or []
        for row_index, row in enumerate(matrix):
            for column_index, value in enumerate(row):
                writer.writerow([
                    "confusionMatrix",
                    f"{labels[row_index] if row_index < len(labels) else row_index}->{labels[column_index] if column_index < len(labels) else column_index}",
                    "",
                    value,
                ])
    for item in (document.get("featureImportance") or [])[:20]:
        writer.writerow(["featureImportance", item.get("feature"), "importanceMean", item.get("importanceMean")])
    for row in document.get("comparison") or []:
        for key, value in (row.get("metrics") or {}).items():
            writer.writerow(["comparison", row.get("name") or row.get("algorithm"), key, value])
    for note in document.get("conclusion") or []:
        writer.writerow(["conclusion", "", "", note])
    return buffer.getvalue()


def _html_table(header: list[Any], rows: list[list[Any]]) -> str:
    head = "".join(f"<th>{html.escape(str(cell))}</th>" for cell in header)
    body = "".join(
        "<tr>" + "".join(f"<td>{html.escape(_text(cell))}</td>" for cell in row) + "</tr>"
        for row in rows
    )
    return f"<table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"


def _matrix_rows(confusion: dict[str, Any]) -> tuple[list[str], list[list[str]]]:
    labels = [str(label) for label in (confusion.get("labels") or [])]
    matrix = confusion.get("matrix") or []
    header = ["实际 \\ 预测"] + labels
    rows = []
    for index, row in enumerate(matrix):
        rows.append([labels[index] if index < len(labels) else str(index)] + [str(value) for value in row])
    return header, rows


def _metric_label(key: str) -> str:
    return {
        "accuracy": "准确率",
        "precision": "精确率",
        "recall": "召回率",
        "f1": "F1",
        "rocAuc": "ROC-AUC",
        "prAuc": "PR-AUC",
        "macroF1": "宏平均 F1",
        "macroPrecision": "宏平均精确率",
        "macroRecall": "宏平均召回率",
        "mae": "MAE",
        "rmse": "RMSE",
        "r2": "R²",
        "mape": "MAPE",
    }.get(key, key)


def _number(value: Any) -> str:
    if value is None:
        return "N/A"
    try:
        number = float(value)
    except (TypeError, ValueError):
        return str(value)
    if number != number:
        return "N/A"
    if abs(number) >= 1000:
        return f"{number:,.2f}"
    return f"{number:.4f}"


def _text(value: Any) -> str:
    if value is None:
        return "-"
    return str(value)


def _escape(value: Any) -> str:
    return html.escape(str(value))
