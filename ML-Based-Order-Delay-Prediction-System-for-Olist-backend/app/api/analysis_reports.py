"""Internal rendering of the delay-analysis report (PDF / Word / HTML)."""
from __future__ import annotations

import html
import io
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from app.api.model_training import require_internal_training_token

router = APIRouter(prefix="/internal/v1/analysis-reports", include_in_schema=False)

CJK_FONT = "STSong-Light"
MEDIA_TYPES = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "html": "text/html; charset=utf-8",
}


class AnalysisReportRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    title: str = Field(default="Olist 订单延误分析报告", min_length=1, max_length=200)
    format: str = Field(default="pdf", pattern="^(pdf|docx|html)$")
    data: dict[str, Any]


@router.post("")
def create_analysis_report(
    request: AnalysisReportRequest,
    _: None = Depends(require_internal_training_token),
) -> StreamingResponse:
    document = _document(request.title, request.data)
    if request.format == "pdf":
        payload = _render_pdf(document)
    elif request.format == "docx":
        payload = _render_docx(document)
    else:
        payload = _render_html(document).encode("utf-8")
    extension = {"pdf": "pdf", "docx": "docx", "html": "html"}[request.format]
    download = f"{request.title}.{extension}"
    headers = {
        "Content-Disposition": (
            f'attachment; filename="analysis-report.{extension}"; '
            f"filename*=UTF-8''{quote(download)}"
        ),
        "Content-Length": str(len(payload)),
    }
    return StreamingResponse(io.BytesIO(payload), media_type=MEDIA_TYPES[request.format], headers=headers)


# --------------------------------------------------------------------------- document


def _pct(value: Any) -> str:
    try:
        return f"{float(value) * 100:.2f}%"
    except (TypeError, ValueError):
        return "—"


def _num(value: Any, digits: int = 2) -> str:
    try:
        return f"{float(value):.{digits}f}"
    except (TypeError, ValueError):
        return "—"


def _count(value: Any) -> str:
    try:
        return f"{int(value):,}"
    except (TypeError, ValueError):
        return "—"


def _document(title: str, data: dict[str, Any]) -> dict[str, Any]:
    totals = data.get("totals") or {}
    metrics = data.get("metrics") or {}
    outcomes = data.get("outcomes") or {}
    correlation = data.get("correlation") or {}
    columns = data.get("columns") or {}

    sections: list[dict[str, Any]] = []

    filter_lines = _filter_lines(data.get("filters") or {})

    sections.append({
        "heading": "一、数据来源",
        "paragraphs": [
            f"数据表：{data.get('tableName') or '—'}",
            f"数据行数：{_count(data.get('rowCount'))}",
            f"延误标签：{'由四个时间戳推导（签收时间晚于承诺日期）' if data.get('derivedFromTimestamps') else '使用数据表内置 is_delayed 字段'}",
            f"缺失字段：{'、'.join(name for name, value in columns.items() if not value and name != 'review_score') or '无'}",
        ] + filter_lines,
        "table": None,
    })

    sections.append({
        "heading": "二、订单与延误统计",
        "paragraphs": [],
        "table": [
            ["指标", "数值"],
            ["订单量", _count(totals.get("orders"))],
            ["已签收订单量", _count(totals.get("delivered"))],
            ["延误订单量", _count(totals.get("delayed"))],
            ["整体延误率", _pct(totals.get("delayRate"))],
        ],
    })

    sections.append({
        "heading": "三、物流延误指标",
        "paragraphs": [],
        "table": [
            ["指标", "数值"],
            ["平均配送耗时", f"{_num(metrics.get('avgDeliveryDays'))} 天"],
            ["延误率", _pct(metrics.get("delayRate"))],
            ["平均延误天数", f"{_num(metrics.get('avgDelayDays'))} 天"],
        ],
    })

    sections.append({
        "heading": "四、签收结构（提前 / 按时 / 延误）",
        "paragraphs": ["以已签收订单为分母，反映平台配送承诺的稳定程度。"],
        "table": [
            ["类型", "订单量", "占比"],
            ["提前送达", _count(outcomes.get("early")), _pct(outcomes.get("earlyRate"))],
            ["按时送达", _count(outcomes.get("onTime")), _pct(outcomes.get("onTimeRate"))],
            ["延误", _count(outcomes.get("delayed")), _pct(outcomes.get("delayedRate"))],
        ],
    })

    by_month = data.get("byMonth") or []
    if by_month:
        sections.append({
            "heading": "五、按月份延误率",
            "paragraphs": [],
            "table": [["月份", "订单量", "延误量", "延误率"]]
            + [[f"{item.get('month')} 月", _count(item.get("orders")), _count(item.get("delayed")), _pct(item.get("delayRate"))]
               for item in by_month],
        })

    by_weekday = data.get("byWeekday") or []
    if by_weekday:
        sections.append({
            "heading": "六、按星期延误率",
            "paragraphs": [],
            "table": [["星期", "订单量", "延误量", "延误率"]]
            + [[str(item.get("label") or item.get("weekday")), _count(item.get("orders")),
                _count(item.get("delayed")), _pct(item.get("delayRate"))]
               for item in by_weekday],
        })

    by_category = data.get("byCategory") or []
    if by_category:
        sections.append({
            "heading": "七、按商品类目延误率（订单量前 12）",
            "paragraphs": [],
            "table": [["商品类目", "订单量", "延误量", "延误率"]]
            + [[str(item.get("category")), _count(item.get("orders")),
                _count(item.get("delayed")), _pct(item.get("delayRate"))]
               for item in by_category],
        })

    if correlation.get("available"):
        rows = [["评分", "订单量", "平均配送耗时"]]
        for bucket in correlation.get("ratingBuckets") or []:
            rows.append([f"{bucket.get('score')} 星", _count(bucket.get("orders")),
                         f"{_num(bucket.get('avgDeliveryDays'))} 天"])
        sections.append({
            "heading": "八、配送时长与用户评分",
            "paragraphs": [
                f"皮尔逊相关系数 r = {_num(correlation.get('deliveryVsRating'), 3)}"
                f"（{_relation_text(correlation.get('deliveryVsRating'))}）。",
                ("评分越低、平均配送耗时越长，说明物流表现会反映在用户评价上。"
                 if (correlation.get("deliveryVsRating") or 0) < -0.1
                 else "相关系数较弱，配送时长对评分的影响有限。"),
            ],
            "table": rows,
        })
    else:
        sections.append({
            "heading": "八、配送时长与用户评分",
            "paragraphs": [correlation.get("reason") or "当前数据表缺少评分字段，未计算相关系数。"],
            "table": None,
        })

    sections.append({
        "heading": "九、口径与建议",
        "paragraphs": [
            "延误定义：客户签收时间晚于承诺送达日期（order_delivered_customer_date > order_estimated_delivery_date）。",
            "整体延误率 = 延误订单量 ÷ 订单量；签收结构占比 = 该类订单量 ÷ 已签收订单量。",
            "平均延误天数只统计延误订单；平均配送耗时为下单到签收的自然日天数。",
            "建议优先排查延误率明显高于整体的月份与商品类目，并结合配送距离、卖家州做进一步归因。",
        ],
        "table": None,
    })

    return {
        "title": title,
        "generatedAt": datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S"),
        "sections": sections,
        "tableName": data.get("tableName"),
        "rowCount": data.get("rowCount"),
    }


def _filter_lines(filters: dict[str, Any]) -> list[str]:
    parts: list[str] = []
    if filters.get("from") or filters.get("to"):
        parts.append(f"{filters.get('from') or '起点'} ~ {filters.get('to') or '终点'}")
    for key, label in (("categories", "商品类目"), ("customerStates", "客户州"), ("sellerStates", "卖家州")):
        values = filters.get(key) or []
        if values:
            parts.append(f"{label} {'、'.join(str(value) for value in values[:6])}"
                         + ("…" if len(values) > 6 else ""))
    return [f"筛选条件：{'；'.join(parts)}"] if parts else []


def _relation_text(value: Any) -> str:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return "样本不足"
    if number <= -0.3:
        return "中等负相关"
    if number < -0.1:
        return "弱负相关"
    if number < 0.1:
        return "几乎不相关"
    return "正相关"


# --------------------------------------------------------------------------- renderers


def _render_html(document: dict[str, Any]) -> str:
    parts = [
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">",
        f"<title>{html.escape(document['title'])}</title>",
        "<style>body{font-family:'Microsoft YaHei',sans-serif;color:#26352e;margin:28px;line-height:1.7}"
        "h1{font-size:20px;margin:0 0 4px}h2{font-size:14px;margin:22px 0 8px;border-left:3px solid #356b58;padding-left:8px}"
        "p{margin:4px 0;font-size:12px}table{border-collapse:collapse;width:100%;margin:6px 0;font-size:11px}"
        "th,td{border:1px solid #dfe5e1;padding:5px 8px;text-align:left}th{background:#f2f6f3}"
        ".meta{color:#859289;font-size:11px}</style></head><body>",
        f"<h1>{html.escape(document['title'])}</h1>",
        f"<p class=\"meta\">生成时间：{html.escape(document['generatedAt'])}</p>",
    ]
    for section in document["sections"]:
        parts.append(f"<h2>{html.escape(section['heading'])}</h2>")
        for line in section.get("paragraphs") or []:
            parts.append(f"<p>{html.escape(str(line))}</p>")
        table = section.get("table")
        if table:
            parts.append("<table>")
            header, *body = table
            parts.append("<tr>" + "".join(f"<th>{html.escape(str(cell))}</th>" for cell in header) + "</tr>")
            for row in body:
                parts.append("<tr>" + "".join(f"<td>{html.escape(str(cell))}</td>" for cell in row) + "</tr>")
            parts.append("</table>")
    parts.append("</body></html>")
    return "".join(parts)


def _render_pdf(document: dict[str, Any]) -> bytes:
    try:
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
        from reportlab.lib.units import mm
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.cidfonts import UnicodeCIDFont
        from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle
    except Exception as error:  # pragma: no cover
        raise HTTPException(status_code=503, detail="当前环境未安装 reportlab，无法生成 PDF") from error

    pdfmetrics.registerFont(UnicodeCIDFont(CJK_FONT))
    styles = getSampleStyleSheet()
    title_style = ParagraphStyle("AReportTitle", parent=styles["Title"], fontName=CJK_FONT,
                                 fontSize=18, leading=24, spaceAfter=6)
    heading_style = ParagraphStyle("AReportHeading", parent=styles["Heading2"], fontName=CJK_FONT,
                                   fontSize=12, leading=18, spaceBefore=12, textColor=colors.HexColor("#28503f"))
    body_style = ParagraphStyle("AReportBody", parent=styles["BodyText"], fontName=CJK_FONT,
                                fontSize=9, leading=14, textColor=colors.HexColor("#33433b"))
    meta_style = ParagraphStyle("AReportMeta", parent=body_style, textColor=colors.HexColor("#7b8a82"))

    buffer = io.BytesIO()
    doc = SimpleDocTemplate(buffer, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
                            topMargin=16 * mm, bottomMargin=16 * mm,
                            title=document["title"], author="Olist Analysis")
    story = [Paragraph(html.escape(document["title"]), title_style),
             Paragraph(f"生成时间：{document['generatedAt']}", meta_style), Spacer(1, 6)]
    for section in document["sections"]:
        story.append(Paragraph(html.escape(section["heading"]), heading_style))
        for line in section.get("paragraphs") or []:
            story.append(Paragraph(html.escape(str(line)), body_style))
        table = section.get("table")
        if table:
            data = [[Paragraph(html.escape(str(cell)), body_style) for cell in row] for row in table]
            flow = Table(data, repeatRows=1, colWidths=None)
            flow.setStyle(TableStyle([
                ("FONTNAME", (0, 0), (-1, -1), CJK_FONT),
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eef4f0")),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.HexColor("#28503f")),
                ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#d5e0d9")),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 5),
                ("RIGHTPADDING", (0, 0), (-1, -1), 5),
                ("TOPPADDING", (0, 0), (-1, -1), 3),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
            ]))
            story.append(flow)
        story.append(Spacer(1, 4))
    doc.build(story)
    return buffer.getvalue()


def _render_docx(document: dict[str, Any]) -> bytes:
    try:
        from docx import Document
        from docx.shared import Pt
    except Exception as error:  # pragma: no cover
        raise HTTPException(status_code=503, detail="当前环境未安装 python-docx，无法生成 Word") from error

    word = Document()
    normal = word.styles["Normal"]
    normal.font.name = "宋体"
    normal.font.size = Pt(10)
    word.add_heading(document["title"], level=0)
    word.add_paragraph(f"生成时间：{document['generatedAt']}")
    for section in document["sections"]:
        word.add_heading(section["heading"], level=1)
        for line in section.get("paragraphs") or []:
            word.add_paragraph(str(line))
        table = section.get("table")
        if table:
            grid = word.add_table(rows=0, cols=len(table[0]))
            grid.style = "Table Grid"
            for row in table:
                cells = grid.add_row().cells
                for index, cell in enumerate(row):
                    cells[index].text = str(cell)
    buffer = io.BytesIO()
    word.save(buffer)
    return buffer.getvalue()
