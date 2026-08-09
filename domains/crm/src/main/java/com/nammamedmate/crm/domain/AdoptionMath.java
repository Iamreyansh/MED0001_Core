package com.nammamedmate.crm.domain;

/** Adoption percentage and score helpers (EPIC-014 STORY-006). */
public final class AdoptionMath {

  public static final double LOW_ADOPTION_THRESHOLD = 20.0;

  private AdoptionMath() {}

  /** {@code accounts_using / accounts_eligible × 100}, one decimal; 0 when eligible is 0. */
  public static double adoptionPct(long accountsUsing, long accountsEligible) {
    if (accountsEligible <= 0) {
      return 0.0;
    }
    return Math.round(accountsUsing * 1000.0 / accountsEligible) / 10.0;
  }

  public static boolean isLowAdoption(double adoptionPct) {
    return adoptionPct < LOW_ADOPTION_THRESHOLD;
  }

  /** Floor of modules_used / modules_eligible × 100; 0 when eligible is 0. */
  public static int adoptionScore(int modulesUsed, int modulesEligible) {
    if (modulesEligible <= 0) {
      return 0;
    }
    return (int) Math.floor(modulesUsed * 100.0 / modulesEligible);
  }
}
