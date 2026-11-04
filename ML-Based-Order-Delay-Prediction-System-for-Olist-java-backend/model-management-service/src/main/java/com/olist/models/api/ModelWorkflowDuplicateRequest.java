package com.olist.models.api;

import jakarta.validation.constraints.Size;

public record ModelWorkflowDuplicateRequest(@Size(max = 255) String name) {
}
