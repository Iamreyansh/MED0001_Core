package com.nammamedmate.marketing.domain;

import java.util.List;

/** Rule-based recommended admin actions; always non-empty. */
public final class RecommendedActions {

  private RecommendedActions() {}

  public static List<String> forSegment(String name, SegmentType type) {
    if (type == SegmentType.CUSTOM) {
      return List.of(
          "Launch a targeted campaign for this custom audience",
          "Attach a scoped coupon to this segment");
    }
    return switch (name) {
      case "NEW" ->
          List.of(
              "Send a first-order welcome coupon",
              "Trigger onboarding push with pharmacy highlights");
      case "REGULAR" ->
          List.of(
              "Offer a reorder reminder campaign",
              "Upsell subscription / autoship where available");
      case "LOYAL" ->
          List.of("Invite to GOLD loyalty upgrade", "Early-access campaign for seasonal launches");
      case "VIP" ->
          List.of(
              "Target with exclusive PLATINUM loyalty invite",
              "Send early-access campaign for new product launches");
      case "DORMANT" ->
          List.of(
              "Run a win-back campaign with a time-bound coupon",
              "Send personalised reorder reminder for last purchased SKUs");
      case "RX_USERS" ->
          List.of(
              "Promote teleconsult refill reminders",
              "Highlight schedule-H compliance-friendly refill offers");
      case "HIGH_VALUE_AREA" ->
          List.of(
              "Prioritise express delivery slots in these pincodes",
              "Partner with local pharmacies for premium SKUs");
      case "ALL" ->
          List.of(
              "Use as a broad broadcast audience sparingly",
              "Prefer tighter segments for ROI-sensitive campaigns");
      default -> List.of("Review segment membership and launch a relevant campaign");
    };
  }
}
