package com.nammamedmate.marketing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Campaign(
    UUID id,
    String name,
    CampaignChannel channel,
    UUID segmentId,
    UUID messageTemplateId,
    String subject,
    String body,
    String ctaLabel,
    String ctaLink,
    Instant scheduledAt,
    Instant launchedAt,
    Instant completedAt,
    Instant pausedAt,
    Long estimatedCostPaise,
    Long budgetCapPaise,
    long actualSpendPaise,
    int sentCount,
    int deliveredCount,
    int openedCount,
    int clickedCount,
    int convertedCount,
    long revenueAttributedPaise,
    Integer audienceSnapshotCount,
    CampaignStatus status,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt) {

  public static final Duration ATTRIBUTION_WINDOW = Duration.ofHours(48);

  /** ROI% = ((revenue - cost) / cost) * 100; 0 when cost is 0. */
  public BigDecimal roiPct() {
    return roiPct(revenueAttributedPaise, actualSpendPaise);
  }

  public static BigDecimal roiPct(long revenuePaise, long costPaise) {
    if (costPaise <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(revenuePaise - costPaise)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(costPaise), 1, RoundingMode.HALF_UP);
  }

  public BigDecimal openRatePct() {
    if (deliveredCount <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(openedCount)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(deliveredCount), 1, RoundingMode.HALF_UP);
  }

  public BigDecimal ctrPct() {
    if (deliveredCount <= 0) {
      return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    }
    return BigDecimal.valueOf(clickedCount)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(deliveredCount), 1, RoundingMode.HALF_UP);
  }

  public boolean isImmutable() {
    return status == CampaignStatus.RUNNING || status == CampaignStatus.COMPLETED;
  }

  /** Order at T is attributed if interacted_at <= T <= interacted_at + 48h. */
  public static boolean isAttributable(Instant interactedAt, Instant orderTime) {
    if (interactedAt == null || orderTime == null || orderTime.isBefore(interactedAt)) {
      return false;
    }
    return !orderTime.isAfter(interactedAt.plus(ATTRIBUTION_WINDOW));
  }
}
