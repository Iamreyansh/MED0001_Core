package com.nammamedmate.customer.domain;

/** Stub loyalty tier until EPIC-002 STORY-005 owns real tiers. */
public final class LoyaltyTiers {

  private LoyaltyTiers() {}

  public static String fromPoints(int points) {
    if (points >= 200) {
      return "GOLD";
    }
    if (points >= 50) {
      return "SILVER";
    }
    return "BRONZE";
  }
}
