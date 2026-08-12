package com.nammamedmate.observability_ops.domain;

import java.time.Instant;

public record IncidentStatusEntry(
    IncidentStatus status, String updatedBy, String updateMessage, Instant updatedAt) {}
