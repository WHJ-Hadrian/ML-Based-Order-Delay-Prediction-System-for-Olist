package com.olist.jobs.api;

import jakarta.validation.constraints.NotNull;

public record WorkflowRunRequest(@NotNull Long expectedRevision) { }
