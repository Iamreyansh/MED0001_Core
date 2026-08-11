package com.nammamedmate.medicine_schedule.domain;

import java.util.Locale;

public enum CareCircleRelationship {
  SELF,
  SPOUSE,
  CHILD,
  PARENT,
  SIBLING,
  OTHER;

  /** Family relationships creatable via API (excludes SELF). */
  public static CareCircleRelationship parseFamily(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("relationship is required");
    }
    CareCircleRelationship value;
    try {
      value = CareCircleRelationship.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "relationship must be one of: SPOUSE, CHILD, PARENT, SIBLING, OTHER");
    }
    if (value == SELF) {
      throw new IllegalArgumentException(
          "relationship must be one of: SPOUSE, CHILD, PARENT, SIBLING, OTHER");
    }
    return value;
  }
}
