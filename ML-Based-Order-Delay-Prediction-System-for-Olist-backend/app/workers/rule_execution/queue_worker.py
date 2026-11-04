"""Claim/lease queue consumer for rule preview runs."""

from __future__ import annotations

import logging
import threading
import time
from typing import Callable, Optional

from app.workers.rule_execution.client import RuleRunApiClient
from app.workers.rule_execution.errors import ClaimLostError
from app.workers.rule_execution.models import RunOutcome


class LeaseHeartbeat:
    def __init__(
        self,
        client: RuleRunApiClient,
        lease_seconds: int,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        self._client = client
        self._lease_seconds = lease_seconds
        self._logger = logger or logging.getLogger(__name__)
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self.error: Optional[Exception] = None

    def __enter__(self) -> "LeaseHeartbeat":
        interval = max(5.0, self._lease_seconds / 3.0)
        self._thread = threading.Thread(
            target=self._run,
            args=(interval,),
            name="rule-run-lease-heartbeat",
            daemon=True,
        )
        self._thread.start()
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5.0)

    def _run(self, interval: float) -> None:
        while not self._stop.wait(interval):
            try:
                self._client.renew_lease(self._lease_seconds)
            except Exception as exception:
                self.error = exception
                self._logger.error("rule-run lease renewal failed: %s", exception)
                return


class RuleQueueWorker:
    def __init__(
        self,
        client: RuleRunApiClient,
        execute_claim: Callable[[RuleRunApiClient, str], RunOutcome],
        worker_id: str,
        lease_seconds: int = 60,
        poll_seconds: float = 2.0,
        mode: str = "ALL",
        logger: Optional[logging.Logger] = None,
    ) -> None:
        if not worker_id.strip():
            raise ValueError("worker_id must not be empty")
        if lease_seconds < 15 or lease_seconds > 300:
            raise ValueError("lease_seconds must be between 15 and 300")
        if poll_seconds < 0.1 or poll_seconds > 60:
            raise ValueError("poll_seconds must be between 0.1 and 60")
        self._client = client
        self._execute_claim = execute_claim
        self._worker_id = worker_id.strip()
        self._lease_seconds = lease_seconds
        self._poll_seconds = poll_seconds
        self._mode = mode.strip().upper()
        if self._mode not in {"ALL", "PREVIEW", "FULL"}:
            raise ValueError("mode must be ALL, PREVIEW or FULL")
        self._logger = logger or logging.getLogger(__name__)

    def consume_once(self) -> Optional[RunOutcome]:
        claim = self._client.claim_next(
            self._worker_id,
            lease_seconds=self._lease_seconds,
            mode=self._mode,
        )
        if claim.get("claimed") is not True:
            return None
        self._client.activate_claim(claim)
        run_id = str(claim["runId"])
        self._logger.info(
            "claimed rule run %s attempt %s",
            run_id,
            claim["executionAttempt"],
        )
        try:
            with LeaseHeartbeat(self._client, self._lease_seconds, self._logger) as heartbeat:
                try:
                    outcome = self._execute_claim(self._client, run_id)
                except ClaimLostError as exception:
                    self._logger.info(
                        "rule run %s stopped after its claim was lost: %s", run_id, exception
                    )
                    return RunOutcome(run_id, "CANCELLED", (), str(exception)[:2000])
                except Exception as exception:
                    message = str(exception).strip() or type(exception).__name__
                    try:
                        self._client.finish(run_id, "FAILED", message[:2000])
                    except Exception as finish_exception:
                        self._logger.warning(
                            "could not mark rule run %s failed after worker error: %s",
                            run_id,
                            finish_exception,
                        )
                        if isinstance(finish_exception, ClaimLostError):
                            return RunOutcome(
                                run_id, "CANCELLED", (), str(finish_exception)[:2000]
                            )
                    return RunOutcome(run_id, "FAILED", (), message[:2000])
            if heartbeat.error is not None:
                self._logger.warning(
                    "rule run %s completed after lease renewal reported an error: %s",
                    run_id,
                    heartbeat.error,
                )
            return outcome
        finally:
            self._client.clear_claim()

    def consume_forever(self, stop_event: threading.Event) -> None:
        while not stop_event.is_set():
            outcome = self.consume_once()
            if outcome is None:
                stop_event.wait(self._poll_seconds)


__all__ = ["LeaseHeartbeat", "RuleQueueWorker"]
