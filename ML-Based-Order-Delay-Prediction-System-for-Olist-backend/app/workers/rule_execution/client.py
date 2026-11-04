"""HTTP client for the dataset-service rule-run control plane."""

from __future__ import annotations

from typing import Any, Dict, Mapping, Optional, Sequence
from urllib.parse import quote
from contextlib import ExitStack
import json

import httpx

from app.workers.rule_execution.errors import ClaimLostError, RuleRunApiError

_MAX_OUTPUT_UPLOAD_BYTES = 256 * 1024 * 1024


class RuleRunApiClient:
    """Small, explicit client for the frozen execution and progress APIs."""

    def __init__(
        self,
        base_url: str,
        token: Optional[str] = None,
        timeout_seconds: float = 30.0,
        client: Optional[httpx.Client] = None,
    ) -> None:
        normalized_base_url = base_url.strip().rstrip("/")
        if not normalized_base_url:
            raise ValueError("rule-run API base URL must not be empty")

        self._base_url = normalized_base_url
        self._headers: Dict[str, str] = {"Accept": "application/json"}
        if token is not None and token.strip():
            self._headers["Authorization"] = "Bearer " + token.strip()

        self._owns_client = client is None
        self._client = client or httpx.Client(timeout=timeout_seconds)
        self._worker_token = token.strip() if token is not None and token.strip() else None
        self._claim: Optional[Mapping[str, Any]] = None

    def claim_next(
        self,
        worker_id: str,
        lease_seconds: int = 60,
        mode: str = "PREVIEW",
    ) -> Mapping[str, Any]:
        if not self._worker_token:
            raise RuleRunApiError("rule worker token is required to consume queued runs")
        return self._request(
            "POST",
            "/internal/rule-runs/claim",
            json_body={
                "workerId": worker_id,
                "leaseSeconds": lease_seconds,
                "mode": mode.strip().upper(),
            },
            worker_request=True,
        )

    def activate_claim(self, claim: Mapping[str, Any]) -> None:
        run_id = claim.get("runId")
        owner_id = claim.get("ownerId")
        claim_token = claim.get("claimToken")
        attempt = claim.get("executionAttempt")
        if (
            claim.get("claimed") is not True
            or not isinstance(run_id, str)
            or not run_id.strip()
            or not isinstance(owner_id, str)
            or not owner_id.strip()
            or not isinstance(claim_token, str)
            or not claim_token.strip()
            or not isinstance(attempt, int)
            or attempt < 1
        ):
            raise RuleRunApiError("rule-run API returned an invalid claim")
        self._claim = dict(claim)

    def clear_claim(self) -> None:
        self._claim = None

    def renew_lease(self, lease_seconds: int = 60) -> Mapping[str, Any]:
        claim = self._require_claim()
        return self._request(
            "POST",
            "/internal/rule-runs/{0}/lease".format(_segment(str(claim["runId"]))),
            json_body={"leaseSeconds": lease_seconds},
            worker_request=True,
            claim_request=True,
        )

    def get_execution(self, run_id: str) -> Mapping[str, Any]:
        if self._claim is not None:
            self._require_matching_run(run_id)
            return self._request(
                "GET",
                "/internal/rule-runs/{0}/execution".format(_segment(run_id)),
                worker_request=True,
                claim_request=True,
            )
        return self._request("GET", "/api/v1/rule-runs/{0}/execution".format(_segment(run_id)))

    def get_execution_snapshot(self, dataset_version_id: str) -> Mapping[str, Any]:
        if self._claim is not None:
            run_id = str(self._claim["runId"])
            return self._request(
                "GET",
                "/internal/rule-runs/{0}/execution-snapshot".format(_segment(run_id)),
                worker_request=True,
                claim_request=True,
            )
        return self._request(
            "GET",
            "/internal/dataset-versions/{0}/execution-snapshot".format(
                _segment(dataset_version_id)
            ),
        )

    def start(self, run_id: str) -> Mapping[str, Any]:
        if self._claim is not None:
            self._require_matching_run(run_id)
            return self._request(
                "POST",
                "/internal/rule-runs/{0}/start".format(_segment(run_id)),
                worker_request=True,
                claim_request=True,
            )
        return self._request("POST", "/api/v1/rule-runs/{0}/start".format(_segment(run_id)))

    def report_node(
        self,
        run_id: str,
        node_id: str,
        node_kind: str,
        status: str,
        metrics: Optional[Mapping[str, Any]] = None,
        sample_uri: Optional[str] = None,
        error_message: Optional[str] = None,
    ) -> Mapping[str, Any]:
        payload: Dict[str, Any] = {
            "nodeKind": node_kind.strip().upper(),
            "status": status.strip().upper(),
        }
        if metrics is not None:
            payload["metrics"] = dict(metrics)
        if sample_uri is not None:
            payload["sampleUri"] = sample_uri
        if error_message is not None:
            payload["errorMessage"] = error_message

        if self._claim is not None:
            self._require_matching_run(run_id)
            return self._request(
                "PUT",
                "/internal/rule-runs/{0}/nodes/{1}".format(
                    _segment(run_id), _segment(node_id)
                ),
                json_body=payload,
                worker_request=True,
                claim_request=True,
            )
        return self._request(
            "PUT",
            "/api/v1/rule-runs/{0}/nodes/{1}".format(_segment(run_id), _segment(node_id)),
            json_body=payload,
        )

    def finish(
        self,
        run_id: str,
        status: str,
        error_message: Optional[str] = None,
    ) -> Mapping[str, Any]:
        payload: Dict[str, Any] = {"status": status.strip().upper()}
        if error_message is not None:
            payload["errorMessage"] = error_message
        if self._claim is not None:
            self._require_matching_run(run_id)
            return self._request(
                "POST",
                "/internal/rule-runs/{0}/finish".format(_segment(run_id)),
                json_body=payload,
                worker_request=True,
                claim_request=True,
            )
        return self._request(
            "POST", "/api/v1/rule-runs/{0}/finish".format(_segment(run_id)), json_body=payload
        )

    def finish_with_outputs(self, run_id: str, outputs: Sequence[Any]) -> Mapping[str, Any]:
        if not self._worker_token or self._claim is None:
            raise RuleRunApiError("materialized outputs require an active worker claim")
        self._require_matching_run(run_id)
        if not outputs or len(outputs) > 20:
            raise RuleRunApiError("materialized output count must be between 1 and 20")
        if sum(int(output.size_bytes) for output in outputs) > _MAX_OUTPUT_UPLOAD_BYTES:
            raise RuleRunApiError("combined output size exceeds the 256 MiB upload limit")

        manifest: Dict[str, Any] = {"outputs": []}
        multipart = []
        with ExitStack() as stack:
            for index, output in enumerate(outputs):
                part_name = "file{0}".format(index)
                handle = stack.enter_context(open(output.file_path, "rb"))
                filename = output.name.strip().replace("/", "_").replace("\\", "_")
                manifest["outputs"].append(
                    {
                        "nodeId": output.node_id,
                        "name": output.name,
                        "filePart": part_name,
                        "sha256": output.sha256,
                        "sizeBytes": output.size_bytes,
                        "rowCount": output.row_count,
                        "columnCount": output.column_count,
                    }
                )
                multipart.append((part_name, (filename + ".csv", handle, "text/csv")))
            return self._request(
                "POST",
                "/internal/rule-runs/{0}/finish-with-outputs".format(_segment(run_id)),
                worker_request=True,
                claim_request=True,
                form_fields={"manifest": json.dumps(manifest, ensure_ascii=False)},
                files=multipart,
            )

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def __enter__(self) -> "RuleRunApiClient":
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self.close()

    def _request(
        self,
        method: str,
        path: str,
        json_body: Optional[Mapping[str, Any]] = None,
        worker_request: bool = False,
        claim_request: bool = False,
        form_fields: Optional[Mapping[str, str]] = None,
        files: Optional[Sequence[Any]] = None,
    ) -> Mapping[str, Any]:
        url = self._base_url + path
        try:
            headers = dict(self._headers)
            if worker_request:
                if not self._worker_token:
                    raise RuleRunApiError("rule worker token is required for internal APIs")
                headers["X-Rule-Worker-Token"] = self._worker_token
            if claim_request:
                claim = self._require_claim()
                headers["X-Rule-Claim-Token"] = str(claim["claimToken"])
                headers["X-Rule-Execution-Attempt"] = str(claim["executionAttempt"])
            response = self._client.request(
                method,
                url,
                headers=headers,
                json=dict(json_body) if json_body is not None else None,
                data=dict(form_fields) if form_fields is not None else None,
                files=files,
            )
        except httpx.HTTPError as exception:
            raise RuleRunApiError(
                "rule-run API request failed: {0} {1}: {2}".format(method, url, exception)
            ) from exception

        if response.status_code >= 400:
            detail = _response_detail(response)
            error_type = (
                ClaimLostError
                if claim_request and response.status_code == 409
                else RuleRunApiError
            )
            raise error_type(
                "rule-run API returned HTTP {0} for {1} {2}: {3}".format(
                    response.status_code, method, url, detail
                ),
                status_code=response.status_code,
                detail=detail,
            )

        try:
            payload = response.json()
        except ValueError as exception:
            raise RuleRunApiError(
                "rule-run API returned a non-JSON response for {0} {1}".format(method, url),
                status_code=response.status_code,
            ) from exception

        if not isinstance(payload, Mapping):
            raise RuleRunApiError(
                "rule-run API returned a non-object JSON response for {0} {1}".format(
                    method, url
                ),
                status_code=response.status_code,
                detail=payload,
            )
        return payload

    def _require_claim(self) -> Mapping[str, Any]:
        if self._claim is None:
            raise RuleRunApiError("no active rule-run claim")
        return self._claim

    def _require_matching_run(self, run_id: str) -> None:
        claim = self._require_claim()
        if str(claim.get("runId")) != run_id:
            raise RuleRunApiError("active rule-run claim does not match requested run")


def _segment(value: str) -> str:
    return quote(value, safe="")


def _response_detail(response: httpx.Response) -> Any:
    try:
        payload = response.json()
    except ValueError:
        return response.text[:1000]
    if isinstance(payload, Mapping):
        for key in ("message", "detail", "error"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return payload


__all__ = ["RuleRunApiClient"]
