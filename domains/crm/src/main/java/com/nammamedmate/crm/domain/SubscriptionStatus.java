package com.nammamedmate.crm.domain;

public final class SubscriptionStatus {

  public static final String ACTIVE = "ACTIVE";
  public static final String TRIAL = "TRIAL";
  public static final String PAST_DUE = "PAST_DUE";
  public static final String CANCELLED = "CANCELLED";
  public static final String EXPIRED = "EXPIRED";

  private SubscriptionStatus() {}

  public static boolean hasModuleAccess(String status) {
    return ACTIVE.equals(status) || TRIAL.equals(status) || PAST_DUE.equals(status);
  }
}
