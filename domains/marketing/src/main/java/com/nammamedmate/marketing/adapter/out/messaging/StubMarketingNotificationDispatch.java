package com.nammamedmate.marketing.adapter.out.messaging;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ponytail: outbox ids-only until EPIC-017 notification worker delivers. */
public final class StubMarketingNotificationDispatch implements NotificationDispatchPort {

  private final OutboxPublisher outbox;

  public StubMarketingNotificationDispatch(OutboxPublisher outbox) {
    this.outbox = outbox;
  }

  @Override
  public void notifyCouponBudgetExhausted(String couponCode, UUID couponId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("coupon_id", couponId.toString());
    payload.put("coupon_code", couponCode);
    payload.put("template", "COUPON_BUDGET_EXHAUSTED");
    payload.put("channels", List.of("IN_APP", "EMAIL"));
    outbox.publish(
        DomainEvent.of(
            "marketing.notification.coupon_budget_exhausted", "coupon", couponId, payload));
  }

  @Override
  public void notifyDailyBudgetBurnDigest(List<BudgetBurnItem> items) {
    UUID aggregateId = Ids.newId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", aggregateId.toString());
    payload.put("template", "COUPON_BUDGET_BURN_DIGEST");
    payload.put("channels", List.of("IN_APP", "EMAIL"));
    List<Map<String, Object>> rows = new ArrayList<>(items.size());
    for (BudgetBurnItem item : items) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", item.code());
      row.put("budget_total_paise", item.budgetTotalPaise());
      row.put("budget_used_paise", item.budgetUsedPaise());
      row.put("pct_used", item.pctUsed());
      rows.add(row);
    }
    payload.put("coupons", rows);
    outbox.publish(
        DomainEvent.of(
            "marketing.notification.coupon_budget_burn_digest", "coupon", aggregateId, payload));
  }

  @Override
  public void notifyCampaignBudgetPaused(String campaignName, UUID campaignId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notification_id", Ids.newId().toString());
    payload.put("campaign_id", campaignId.toString());
    payload.put("campaign_name", campaignName);
    payload.put("template", "CAMPAIGN_BUDGET_PAUSED");
    payload.put("channels", List.of("IN_APP", "EMAIL"));
    outbox.publish(
        DomainEvent.of(
            "marketing.notification.campaign_budget_paused", "campaign", campaignId, payload));
  }
}
