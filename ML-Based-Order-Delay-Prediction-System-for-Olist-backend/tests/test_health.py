from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_liveness_probe_reports_a_running_process() -> None:
    response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "alive"}


def test_readiness_probe_reports_an_initialized_service() -> None:
    response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {"status": "ready"}


def test_metrics_endpoint_is_exposed_for_prometheus() -> None:
    response = client.get("/metrics")

    assert response.status_code == 200
    assert "http_requests_total" in response.text
