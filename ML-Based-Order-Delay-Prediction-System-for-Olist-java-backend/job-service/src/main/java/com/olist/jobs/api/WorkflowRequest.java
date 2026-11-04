package com.olist.jobs.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkflowRequest(@NotBlank @Size(max = 255) String name, @NotNull JsonNode document,
        Long expectedRevision) { }
