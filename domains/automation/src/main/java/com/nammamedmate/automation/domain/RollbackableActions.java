package com.nammamedmate.automation.domain;

import java.util.Set;

/** Actions that can be reversed via POST /activity/:id/rollback (BR-3). */
public final class RollbackableActions {

  public static final Set<String> ROLLBACKABLE = Set.of("suspend_entity", "apply_wallet_credit");

  public static final Set<String> FINANCIAL =
      Set.of("release_payout", "process_refund", "apply_wallet_credit", "mass_payout");

  private RollbackableActions() {}

  public static boolean isRollbackable(String actionType) {
    return actionType != null && ROLLBACKABLE.contains(actionType);
  }

  public static boolean isFinancial(String actionType) {
    if (actionType == null) {
      return false;
    }
    if ("ROLLBACK".equals(actionType)) {
      return false;
    }
    return FINANCIAL.contains(actionType);
  }

  public static String rollbackResult(String actionType) {
    if ("suspend_entity".equals(actionType)) {
      return "Entity reactivated successfully.";
    }
    if ("apply_wallet_credit".equals(actionType)) {
      return "Wallet credit debited back.";
    }
    return "Rolled back.";
  }
}
