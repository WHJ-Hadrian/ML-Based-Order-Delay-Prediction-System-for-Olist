package com.olist.jobs.api;

import java.time.Instant;

public record WorkflowRunLogResponse(Instant timestamp, String level, String message) { }
