package com.nammamedmate.settings.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Server-side cohort helper: {@code sha256(user_id + flag_name) % 100 < rollout_percentage}. Public
 * GET /feature-flags/check does not use this (base enabled only).
 */
public final class FeatureFlagCohort {

  @FunctionalInterface
  interface DigestFactory {
    MessageDigest create() throws NoSuchAlgorithmException;
  }

  private FeatureFlagCohort() {}

  /**
   * Kill-switch aware evaluation. {@code enabled=false} or {@code rollout<=0} → off; {@code
   * rollout>=100} → on for all; 1–99 uses deterministic SHA-256 cohort.
   */
  public static boolean isEnabledForUser(
      boolean enabled, int rolloutPercentage, UUID userId, String flagName) {
    if (!enabled || rolloutPercentage <= 0 || userId == null || flagName == null) {
      return false;
    }
    if (rolloutPercentage >= 100) {
      return true;
    }
    return bucket(userId, flagName) < rolloutPercentage;
  }

  /** Bucket in {@code [0, 99]} from SHA-256 of {@code userId + flagName}. */
  public static int bucket(UUID userId, String flagName) {
    return bucket(userId, flagName, () -> MessageDigest.getInstance("SHA-256"));
  }

  static int bucket(UUID userId, String flagName, DigestFactory digestFactory) {
    if (userId == null || flagName == null) {
      return 0;
    }
    try {
      MessageDigest digest = digestFactory.create();
      byte[] hash = digest.digest((userId + flagName).getBytes(StandardCharsets.UTF_8));
      int value = ((hash[0] & 0xff) << 8) | (hash[1] & 0xff);
      return Math.floorMod(value, 100);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
