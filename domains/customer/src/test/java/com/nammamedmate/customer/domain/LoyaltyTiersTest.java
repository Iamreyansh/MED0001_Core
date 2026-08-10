package com.nammamedmate.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LoyaltyTiersTest {

  @Test
  void fromLifetimePoints_mapsThresholds() {
    assertThat(LoyaltyTiers.fromLifetimePoints(0)).isEqualTo("NONE");
    assertThat(LoyaltyTiers.fromLifetimePoints(11)).isEqualTo("NONE");
    assertThat(LoyaltyTiers.fromLifetimePoints(12)).isEqualTo("SILVER");
    assertThat(LoyaltyTiers.fromLifetimePoints(49)).isEqualTo("SILVER");
    assertThat(LoyaltyTiers.fromLifetimePoints(50)).isEqualTo("GOLD");
    assertThat(LoyaltyTiers.fromLifetimePoints(119)).isEqualTo("GOLD");
    assertThat(LoyaltyTiers.fromLifetimePoints(120)).isEqualTo("PLATINUM");
    assertThat(LoyaltyTiers.fromPoints(75)).isEqualTo("GOLD");
  }

  @Test
  void progress_forGoldTowardPlatinum() {
    Map<String, Object> progress = LoyaltyTiers.progress(50);
    assertThat(progress)
        .containsEntry("current_tier", "GOLD")
        .containsEntry("next_tier", "PLATINUM")
        .containsEntry("points_for_next_tier", 120)
        .containsEntry("points_needed", 70);
  }

  @Test
  void progress_atPlatinum_isComplete() {
    Map<String, Object> progress = LoyaltyTiers.progress(200);
    assertThat(progress)
        .containsEntry("current_tier", "PLATINUM")
        .containsEntry("next_tier", null)
        .containsEntry("points_needed", 0)
        .containsEntry("progress_pct", 100);
  }

  @Test
  void thresholds_matchStory() {
    Map<String, Object> t = LoyaltyTiers.thresholds();
    assertThat(t).containsKeys("NONE", "SILVER", "GOLD", "PLATINUM");
    @SuppressWarnings("unchecked")
    Map<String, Object> none = (Map<String, Object>) t.get("NONE");
    assertThat(none).containsEntry("min", 0).containsEntry("max", 11);
    @SuppressWarnings("unchecked")
    Map<String, Object> plat = (Map<String, Object>) t.get("PLATINUM");
    assertThat(plat).containsEntry("min", 120).containsEntry("max", null);
  }

  @Test
  void nextMinMaxAndNormalize_coverAllBranches() {
    assertThat(LoyaltyTiers.nextTier(null)).isEqualTo("SILVER");
    assertThat(LoyaltyTiers.nextTier("SILVER")).isEqualTo("GOLD");
    assertThat(LoyaltyTiers.nextTier("GOLD")).isEqualTo("PLATINUM");
    assertThat(LoyaltyTiers.nextTier("PLATINUM")).isNull();
    assertThat(LoyaltyTiers.nextTier("  ")).isEqualTo("SILVER");

    assertThat(LoyaltyTiers.minForTier("SILVER")).isEqualTo(12);
    assertThat(LoyaltyTiers.minForTier("GOLD")).isEqualTo(50);
    assertThat(LoyaltyTiers.minForTier("PLATINUM")).isEqualTo(120);
    assertThat(LoyaltyTiers.minForTier("NONE")).isEqualTo(0);
    assertThat(LoyaltyTiers.minForTier("weird")).isEqualTo(0);

    assertThat(LoyaltyTiers.maxForTier("NONE")).isEqualTo(11);
    assertThat(LoyaltyTiers.maxForTier("SILVER")).isEqualTo(49);
    assertThat(LoyaltyTiers.maxForTier("GOLD")).isEqualTo(119);
    assertThat(LoyaltyTiers.maxForTier("PLATINUM")).isNull();
    assertThat(LoyaltyTiers.maxForTier("weird")).isNull();
    assertThat(LoyaltyTiers.maxForTier(null)).isEqualTo(11);

    assertThat(LoyaltyTiers.progress(12).get("current_tier")).isEqualTo("SILVER");
    assertThat(LoyaltyTiers.progress(-5).get("current_tier")).isEqualTo("NONE");
    assertThat(LoyaltyTiers.pointsForOrderPaise(35_000L)).isEqualTo(3);
    assertThat(LoyaltyTiers.pointsForOrderPaise(9_999L)).isEqualTo(0);
    assertThat(LoyaltyTiers.pointsForOrderPaise(0L)).isEqualTo(0);
    assertThat(LoyaltyTiers.pointsForOrderPaise(-1L)).isEqualTo(0);
    assertThat(LoyaltyTiers.pointsForOrderPaise(58_000L, 100)).isEqualTo(5);
    assertThat(LoyaltyTiers.fromLifetimePoints(50, 12, 50, 120)).isEqualTo("GOLD");
    assertThat(LoyaltyTiers.thresholds(12, 50, 120))
        .containsKeys("NONE", "SILVER", "GOLD", "PLATINUM");
  }
}
