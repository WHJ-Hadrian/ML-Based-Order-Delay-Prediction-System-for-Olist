import json

import httpx
import pytest

from app.workers.rule_execution.client import RuleRunApiClient
from app.workers.rule_execution.errors import RuleRunApiError
from tests.rule_execution_support import (
    DATASET_VERSION_ID,
    RUN_ID,
    make_execution_payload,
    make_snapshot_payload,
)


def test_client_calls_execution_start_node_and_finish_contracts() -> None:
    execution = make_execution_payload()
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path.endswith("/execution"):
            return httpx.Response(200, json=execution)
        if request.url.path.endswith("/start"):
            return httpx.Response(200, json={"status": "RUNNING"})
        if "/nodes/" in request.url.path:
            return httpx.Response(200, json={"status": "SUCCEEDED"})
        if request.url.path.endswith("/finish"):
            return httpx.Response(200, json={"status": "SUCCEEDED"})
        return httpx.Response(404, json={"message": "not found"})

    transport = httpx.MockTransport(handler)
    with httpx.Client(transport=transport) as http_client:
        client = RuleRunApiClient(
            "http://dataset-service:8082/",
            token="service-token",
            client=http_client,
        )
        assert client.get_execution(RUN_ID)["id"] == RUN_ID
        assert client.start(RUN_ID)["status"] == "RUNNING"
        client.report_node(
            RUN_ID,
            "field_checks:check",
            "check",
            "succeeded",
            metrics={"rowCount": 12},
            sample_uri="s3://olist/sample.jsonl",
            error_message=None,
        )
        assert client.finish(RUN_ID, "succeeded")["status"] == "SUCCEEDED"

    assert requests[0].headers["Authorization"] == "Bearer service-token"
    assert requests[1].method == "POST"
    assert requests[2].method == "PUT"
    assert "field_checks%3Acheck" in str(requests[2].url)
    node_payload = json.loads(requests[2].content)
    assert node_payload == {
        "nodeKind": "CHECK",
        "status": "SUCCEEDED",
        "metrics": {"rowCount": 12},
        "sampleUri": "s3://olist/sample.jsonl",
    }
    assert json.loads(requests[3].content) == {"status": "SUCCEEDED"}


def test_client_surfaces_server_error_detail() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            409,
            json={"message": "node state is terminal", "code": "RULE_RUN_CONFLICT"},
        )

    transport = httpx.MockTransport(handler)
    with httpx.Client(transport=transport) as http_client:
        client = RuleRunApiClient("http://dataset-service:8082", client=http_client)
        with pytest.raises(RuleRunApiError) as raised:
            client.finish(RUN_ID, "SUCCEEDED")

    assert raised.value.status_code == 409
    assert raised.value.detail == "node state is terminal"
    assert "node state is terminal" in str(raised.value)


def test_client_calls_execution_snapshot_contract() -> None:
    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, json=make_snapshot_payload())

    transport = httpx.MockTransport(handler)
    with httpx.Client(transport=transport) as http_client:
        client = RuleRunApiClient(
            "http://dataset-service:8082/",
            token="service-token",
            client=http_client,
        )
        payload = client.get_execution_snapshot(DATASET_VERSION_ID)

    assert payload["datasetVersionId"] == DATASET_VERSION_ID
    assert requests[0].method == "GET"
    assert (
        requests[0].url.path
        == "/internal/dataset-versions/{0}/execution-snapshot".format(
            DATASET_VERSION_ID
        )
    )
    assert requests[0].headers["Authorization"] == "Bearer service-token"
