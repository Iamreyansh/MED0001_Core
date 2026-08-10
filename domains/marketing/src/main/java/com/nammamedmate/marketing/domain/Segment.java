package com.nammamedmate.marketing.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Segment(
    UUID id,
    String name,
    String description,
    SegmentType segmentType,
    List<SegmentCriterion> criteria,
    String status,
    int customerCount,
    Long avgAovPaise,
    Long totalLtvPaise,
    Instant lastComputedAt,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public boolean isSystem() {
    return segmentType == SegmentType.SYSTEM;
  }
}
