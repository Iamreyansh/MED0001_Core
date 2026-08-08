package com.nammamedmate.settings.domain;

import java.util.Set;

public final class PlatformConfigDomains {

  public static final String ORDERS = "orders";
  public static final String PAYMENTS = "payments";
  public static final String COMMISSIONS = "commissions";
  public static final String KYC = "kyc";
  public static final String RIDER = "rider";
  public static final Set<String> ALL = Set.of(ORDERS, PAYMENTS, COMMISSIONS, KYC, RIDER);

  private PlatformConfigDomains() {}

  public static boolean isValid(String domain) {
    return domain != null && ALL.contains(domain);
  }
}
