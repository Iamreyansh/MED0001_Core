package com.nammamedmate.crm.domain;

import java.util.List;

/** Canonical SaaS plan tier names (EPIC-014). */
public final class PlanNames {

  public static final String FREE = "FREE";
  public static final String STARTER = "STARTER";
  public static final String RETAIL_PRO = "RETAIL_PRO";
  public static final String ENTERPRISE = "ENTERPRISE";

  public static final List<String> ORDERED = List.of(FREE, STARTER, RETAIL_PRO, ENTERPRISE);

  private PlanNames() {}

  public static int tierIndex(String name) {
    int i = ORDERED.indexOf(name);
    return i < 0 ? -1 : i;
  }

  public static String upgradePath(String name) {
    int i = tierIndex(name);
    if (i < 0 || i >= ORDERED.size() - 1) {
      return null;
    }
    return ORDERED.get(i + 1);
  }

  /** STARTER+ unlocks khata / starter POS features. */
  public static boolean starterFeaturesEnabled(String planName) {
    int i = tierIndex(planName);
    return i >= tierIndex(STARTER);
  }

  /** RETAIL_PRO+ unlocks growth inventory / offers features. */
  public static boolean growthFeaturesEnabled(String planName) {
    int i = tierIndex(planName);
    return i >= tierIndex(RETAIL_PRO);
  }
}
