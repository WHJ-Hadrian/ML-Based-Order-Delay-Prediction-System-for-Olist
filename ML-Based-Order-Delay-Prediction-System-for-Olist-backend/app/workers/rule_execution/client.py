"""HTTP client for the dataset-service rule-run control plane."""

from __future__ import annotations

from typing import Any, Dict, Mapping, Optional
from urllib.parse import quote

import httpx

from app.workers.rule_execution.errors import RuleRunApiError


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

    def get_execution(self, run_id: str) -> Mapping[str, Any]:
        return self._request("GET", "/api/v1/rule-runs/{0}/execution".format(_segment(run_id)))

    def get_execution_snapshot(self, dataset_version_id: str) -> Mapping[str, Any]:
        return self._request(
            "GET",
            "/internal/dataset-versions/{0}/execution-snapshot".format(
                _segment(dataset_version_id)
            ),
        )

    def start(self, run_id: str) -> Mapping[str, Any]:
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
        return self._request(
            "POST",
            "/api/v1/rule-runs/{0}/finish".format(_segment(run_id)),
            json_body=payload,
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
    ) -> Mapping[str, Any]:
        url = self._base_url + path
        try:
            response = self._client.request(
                method,
                url,
                headers=self._headers,
                json=dict(json_body) if json_body is not None else None,
            )
        except httpx.HTTPError as exception:
            raise RuleRunApiError(
                "rule-run API request failed: {0} {1}: {2}".format(method, url, exception)
            ) from exception

        if response.status_code >= 400:
            detail = _response_detail(response)
            raise RuleRunApiError(
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
