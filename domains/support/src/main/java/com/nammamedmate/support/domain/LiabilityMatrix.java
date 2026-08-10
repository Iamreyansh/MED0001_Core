package com.nammamedmate.support.domain;

/** Pure liability recommendation from dispute type (admin may override). */
public final class LiabilityMatrix {

  private LiabilityMatrix() {}

  public static LiableParty recommend(DisputeType type) {
    return switch (type) {
      case WRONG_ITEMS, MISSING_ITEMS, DAMAGED, EXPIRED_MEDICINE, QUALITY -> LiableParty.PHARMACY;
      case NOT_DELIVERED -> LiableParty.RIDER;
      case OVERCHARGED -> LiableParty.PLATFORM;
    };
  }

  public static String rationale(DisputeType type) {
    return switch (type) {
      case WRONG_ITEMS -> "WRONG_ITEMS disputes are attributed to pharmacy fulfilment error.";
      case MISSING_ITEMS -> "MISSING_ITEMS disputes are attributed to pharmacy packing error.";
      case DAMAGED -> "DAMAGED disputes are attributed to pharmacy packaging.";
      case EXPIRED_MEDICINE ->
          "EXPIRED_MEDICINE disputes are attributed to pharmacy stock control.";
      case QUALITY -> "QUALITY disputes are attributed to pharmacy quality control.";
      case NOT_DELIVERED ->
          "NOT_DELIVERED disputes are attributed to the rider when tracking shows delivered.";
      case OVERCHARGED -> "OVERCHARGED disputes are attributed to platform billing.";
    };
  }
}
