package com.nammamedmate.crm.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Account health score helpers (EPIC-014 STORY-005). */
public final class HealthMath {

  public static final double W_USAGE = 0.30;
  public static final double W_BILLING = 0.25;
  public static final double W_SUPPORT = 0.25;
  public static final double W_BUSINESS = 0.20;

  public static final double BILLING_PAID = 100.0;
  public static final double BILLING_DUE = 70.0;
  public static final double BILLING_OVERDUE = 0.0;

  public static final double DEFAULT_SUPPORT = 100.0;
  public static final double DEFAULT_BUSINESS = 70.0;

  private HealthMath() {}

  public static double overall(
      double productUsage, double billing, double support, double business) {
    double raw =
        productUsage * W_USAGE + billing * W_BILLING + support * W_SUPPORT + business * W_BUSINESS;
    return round2(clamp(raw));
  }

  /** Billing: 100 PAID/no overdue; 70 DUE; 0 OVERDUE/DUNNING. */
  public static double billingHealth(Iterable<String> openStatuses) {
    boolean due = false;
    if (openStatuses != null) {
      for (String s : openStatuses) {
        if (InvoiceStatus.OVERDUE.equals(s) || InvoiceStatus.DUNNING.equals(s)) {
          return BILLING_OVERDUE;
        }
        if (InvoiceStatus.DUE.equals(s)) {
          due = true;
        }
      }
    }
    return due ? BILLING_DUE : BILLING_PAID;
  }

  /**
   * Map ERP invoice volume growth to 0–100. Missing prior period → {@link #DEFAULT_BUSINESS} when
   * current is also empty; 100 when only current has volume.
   */
  public static double businessFromInvoiceGrowth(long currentCount, long priorCount) {
    if (priorCount <= 0) {
      if (currentCount <= 0) {
        return DEFAULT_BUSINESS;
      }
      return 100.0;
    }
    double growthPct = ((currentCount - priorCount) * 100.0) / priorCount;
    return round2(clamp(50.0 + growthPct));
  }

  public static List<String> riskFactors(
      double usage,
      double billing,
      double support,
      double business,
      int modulesUsed,
      int modulesEligible) {
    List<String> factors = new ArrayList<>();
    if (usage < 30.0) {
      factors.add(
          "Low module adoption (only "
              + modulesUsed
              + " of "
              + modulesEligible
              + " modules used in last 30 days)");
    }
    if (billing <= BILLING_OVERDUE) {
      factors.add("Overdue or dunning SaaS invoice");
    } else if (billing <= BILLING_DUE) {
      factors.add("SaaS invoice due within grace period");
    }
    if (support < 50.0) {
      factors.add("Support satisfaction below threshold");
    }
    if (business < 50.0) {
      factors.add("Business volume declined vs. prior period");
    }
    return factors;
  }

  public static List<String> recommendedActions(
      double usage, double billing, double support, double business) {
    List<String> actions = new ArrayList<>();
    if (usage < 30.0) {
      actions.add("Schedule a training session on underused modules");
    }
    if (billing <= BILLING_OVERDUE) {
      actions.add("Collect overdue invoice or offer a payment plan");
    } else if (billing <= BILLING_DUE) {
      actions.add("Send payment reminder before grace ends");
    }
    if (support < 50.0) {
      actions.add("Escalate open support tickets to resolution");
    }
    if (business < 50.0) {
      actions.add("Offer a 1-month discount to retain account");
    }
    if (actions.isEmpty()) {
      actions.add("Maintain regular CSM check-in");
    }
    return actions;
  }

  public static boolean shouldTriggerSavePlay(Double previousOverall, double newOverall) {
    if (newOverall >= HealthBand.SAVE_PLAY_TRIGGER) {
      return false;
    }
    return previousOverall == null || previousOverall >= HealthBand.SAVE_PLAY_TRIGGER;
  }

  public static double round2(double v) {
    return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private static double clamp(double v) {
    if (v < 0.0) {
      return 0.0;
    }
    if (v > 100.0) {
      return 100.0;
    }
    return v;
  }
}
