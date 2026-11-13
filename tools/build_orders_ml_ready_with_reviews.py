"""生成"宽表 + 评分"数据集：读取 ClickHouse 里的订单宽表，关联 Olist 评价表
（按订单取平均分与评价条数），写出 CSV 并上传为新的数据集版本。

用法：
    python tools/build_orders_ml_ready_with_reviews.py \
        --dataset-id <宽表数据集ID> --version-id <版本ID> --table-id <表ID> \
        --name "olist_orders_ml_ready_v3（含评分）"

依赖：本地 ClickHouse 可访问（默认 olist/local-clickhouse-only），
以及 data/olist_order_reviews_dataset.csv。
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
API = "http://localhost:8080"
KEYCLOAK = "http://localhost:8180/realms/olist/protocol/openid-connect/token"
CLICKHOUSE = "http://127.0.0.1:8123/?user=olist&password=local-clickhouse-only"
USER = "2646765690@qq.com"
PASSWORD = "Aa517.527.537"


def login() -> str:
    body = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "olist-web",
        "username": USER, "password": PASSWORD,
    }).encode()
    request = urllib.request.Request(KEYCLOAK, data=body,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read())["access_token"]


def call(token: str, method: str, path: str, payload: dict | None = None):
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(f"{API}{path}", data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=300) as response:
        body = response.read()
        return json.loads(body) if body else None


def clickhouse(query: str) -> bytes:
    request = urllib.request.Request(CLICKHOUSE, data=f"{query} FORMAT CSVWithNames".encode(), method="POST")
    with urllib.request.urlopen(request, timeout=300) as response:
        return response.read()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset-id", required=True)
    parser.add_argument("--version-id", required=True)
    parser.add_argument("--table-id", required=True)
    parser.add_argument("--name", default="olist_orders_ml_ready_v3（含评分）")
    parser.add_argument("--reviews", default=str(ROOT / "data" / "olist_order_reviews_dataset.csv"))
    parser.add_argument("--output", default=str(ROOT / "data" / "orders_delay_ml_v3_with_reviews.csv"))
    args = parser.parse_args()

    token = login()
    catalog = call(token, "GET", f"/api/v1/datasets/{args.dataset_id}/catalog")
    columns = sorted(catalog["tables"][0]["columns"], key=lambda item: item["ordinal"])
    rename = {f"c{index:03d}": column["name"] for index, column in enumerate(columns)}

    physical = "olist_datasets.ds_" + args.version_id.replace("-", "") + "_" + args.table_id.replace("-", "")
    wide = pd.read_csv(pd.io.common.BytesIO(clickhouse(f"SELECT * FROM {physical}"))).rename(columns=rename)
    print("wide", wide.shape, flush=True)

    reviews = pd.read_csv(args.reviews, usecols=["order_id", "review_score"])
    scores = (reviews.groupby("order_id", as_index=False)
              .agg(review_score=("review_score", "mean"), review_count=("review_score", "size")))
    scores["review_score"] = scores["review_score"].round(3)

    merged = wide.merge(scores, on="order_id", how="left")
    output = Path(args.output)
    merged.to_csv(output, index=False)
    print("merged", merged.shape, "->", output, output.stat().st_size, flush=True)

    dataset = call(token, "POST", "/api/v1/datasets", {
        "name": args.name,
        "description": "订单宽表 + 按订单汇总的评价分数（review_score / review_count）",
    })
    boundary = "----olist" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{output.name}\"\r\n"
            "Content-Type: text/csv\r\n\r\n").encode() + output.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(f"{API}/api/v1/datasets/{dataset['id']}/files", data=body, method="POST", headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Accept": "application/json",
    })
    with urllib.request.urlopen(request, timeout=900) as response:
        response.read()
    call(token, "POST", f"/api/v1/datasets/{dataset['id']}/ready")

    for _ in range(120):
        versions = call(token, "GET", f"/api/v1/datasets/{dataset['id']}/versions")
        if versions and versions[0].get("status") in ("READY", "FAILED"):
            break
        time.sleep(2)
    print("dataset", dataset["id"], versions[0]["status"], flush=True)


if __name__ == "__main__":
    main()
