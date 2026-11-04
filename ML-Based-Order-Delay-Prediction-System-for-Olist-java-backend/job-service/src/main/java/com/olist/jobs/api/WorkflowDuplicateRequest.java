package com.olist.jobs.api;

import jakarta.validation.constraints.Size;

public record WorkflowDuplicateRequest(@Size(max = 255) String name) { }
