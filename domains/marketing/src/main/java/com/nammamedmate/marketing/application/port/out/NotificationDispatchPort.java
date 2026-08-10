package com.nammamedmate.marketing.application.port.out;

import java.util.List;
import java.util.UUID;

/** Async admin notification dispatch (outbox ids-only until notification worker). */
public interface NotificationDispatchPort {

  void notifyCouponBudgetExhausted(String couponCode, UUID couponId);

  void notifyDailyBudgetBurnDigest(List<BudgetBurnItem> items);

  void notifyCampaignBudgetPaused(String campaignName, UUID campaignId);

  record BudgetBurnItem(String code, long budgetTotalPaise, long budgetUsedPaise, double pctUsed) {}
}
