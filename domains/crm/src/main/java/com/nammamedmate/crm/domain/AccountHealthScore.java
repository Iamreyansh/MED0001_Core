package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccountHealthScore(
    UUID id,
    UUID accountId,
    double overallScore,
    double productUsageScore,
    double billingHealthScore,
    double supportSatisfactionScore,
    double businessPerformanceScore,
    String healthBand,
    List<String> riskFactors,
    List<String> recommendedActions,
    Instant computedAt) {

  public AccountHealthScore {
    riskFactors = riskFactors == null ? List.of() : List.copyOf(riskFactors);
    recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
  }
}
