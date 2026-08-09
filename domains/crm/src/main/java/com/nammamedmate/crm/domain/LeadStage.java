package com.nammamedmate.crm.domain;

import com.nammamedmate.kernel.error.AppException;
import java.util.Locale;

/** CRM lead funnel stages with default win probabilities. */
public final class LeadStage {

  public static final String NEW = "NEW";
  public static final String CONTACTED = "CONTACTED";
  public static final String DEMO = "DEMO";
  public static final String TRIAL = "TRIAL";
  public static final String WON = "WON";
  public static final String LOST = "LOST";

  private static final String[] OPEN_ORDER = {NEW, CONTACTED, DEMO, TRIAL};

  private LeadStage() {}

  public static String requireValid(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "stage required", 400);
    }
    String s = raw.trim().toUpperCase(Locale.ROOT);
    return switch (s) {
      case NEW, CONTACTED, DEMO, TRIAL, WON, LOST -> s;
      default -> throw new AppException("VALIDATION_ERROR", "invalid stage", 400);
    };
  }

  public static boolean isOpen(String stage) {
    return NEW.equals(stage)
        || CONTACTED.equals(stage)
        || DEMO.equals(stage)
        || TRIAL.equals(stage);
  }

  public static int defaultWinProbability(String stage) {
    return switch (stage) {
      case NEW -> 0;
      case CONTACTED -> 10;
      case DEMO -> 30;
      case TRIAL -> 60;
      case WON -> 100;
      case LOST -> 0;
      default -> 0;
    };
  }

  /** Next open stage, or null when at TRIAL (use mark-won) / terminal. */
  public static String nextOpen(String stage) {
    for (int i = 0; i < OPEN_ORDER.length - 1; i++) {
      if (OPEN_ORDER[i].equals(stage)) {
        return OPEN_ORDER[i + 1];
      }
    }
    return null;
  }
}
