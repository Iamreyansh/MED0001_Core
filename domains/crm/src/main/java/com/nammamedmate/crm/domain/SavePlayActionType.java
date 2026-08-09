package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;
import java.util.Set;

public final class SavePlayActionType {

  public static final String CALL = "CALL";
  public static final String EMAIL = "EMAIL";
  public static final String TRAINING = "TRAINING";
  public static final String DISCOUNT_OFFERED = "DISCOUNT_OFFERED";
  public static final String PLAN_ADJUSTED = "PLAN_ADJUSTED";

  private static final Set<String> ALL =
      Set.of(CALL, EMAIL, TRAINING, DISCOUNT_OFFERED, PLAN_ADJUSTED);

  private SavePlayActionType() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("INVALID_ACTION_TYPE", "action_type is required", 422);
    }
    String v = raw.trim().toUpperCase(Locale.ROOT);
    if (!ALL.contains(v)) {
      throw new AppException("INVALID_ACTION_TYPE", "invalid action_type", 422);
    }
    return v;
  }
}
