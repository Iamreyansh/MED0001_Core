package com.nammamedmate.automation.domain;

import java.util.Locale;

public enum ApprovalCategory {
  FINANCE,
  ADMIN,
  CRM;

  public static ApprovalCategory parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return ADMIN;
    }
    try {
      return ApprovalCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return ADMIN;
    }
  }

  public static ApprovalCategory fromAction(String actionType, String registryCategory) {
    if (RollbackableActions.isFinancial(actionType)) {
      return FINANCE;
    }
    if (registryCategory != null && !registryCategory.isBlank()) {
      String u = registryCategory.trim().toUpperCase(Locale.ROOT);
      if ("FINANCE".equals(u)) {
        return FINANCE;
      }
      if ("CRM".equals(u)) {
        return CRM;
      }
    }
    if ("change_plan".equals(actionType) || "open_csm_task".equals(actionType)) {
      return CRM;
    }
    return ADMIN;
  }
}
