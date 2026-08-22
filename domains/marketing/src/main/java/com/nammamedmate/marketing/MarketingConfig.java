package com.nammamedmate.marketing;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.marketing.adapter.out.client.StubBannerImageValidator;
import com.nammamedmate.marketing.adapter.out.client.StubCampaignDispatch;
import com.nammamedmate.marketing.adapter.out.messaging.StubMarketingNotificationDispatch;
import com.nammamedmate.marketing.application.port.out.BannerImageValidatorPort;
import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.application.port.out.CampaignStore;
import com.nammamedmate.marketing.application.port.out.CouponStore;
import com.nammamedmate.marketing.application.port.out.CustomerGeoPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyAdminPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyTierReadPort;
import com.nammamedmate.marketing.application.port.out.NotificationDispatchPort;
import com.nammamedmate.marketing.application.port.out.OrderSegmentMetricsPort;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort;
import com.nammamedmate.marketing.application.port.out.SegmentUsagePort;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class MarketingConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock marketingClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(OrderSegmentMetricsPort.class)
  OrderSegmentMetricsPort stubOrderSegmentMetricsPort() {
    return List::of;
  }

  @Bean
  @ConditionalOnMissingBean(CustomerGeoPort.class)
  CustomerGeoPort stubCustomerGeoPort() {
    return customerIds -> Map.of();
  }

  @Bean
  @ConditionalOnMissingBean(LoyaltyTierReadPort.class)
  LoyaltyTierReadPort stubLoyaltyTierReadPort() {
    return customerIds -> Map.of();
  }

  @Bean
  @ConditionalOnMissingBean(SegmentUsagePort.class)
  SegmentUsagePort couponAndCampaignSegmentUsagePort(CouponStore coupons, CampaignStore campaigns) {
    return (UUID segmentId) ->
        coupons.isSegmentReferencedByActiveCoupon(segmentId)
            || campaigns.isSegmentReferencedByActiveCampaign(segmentId);
  }

  @Bean
  @ConditionalOnMissingBean(CampaignDispatchPort.class)
  CampaignDispatchPort stubCampaignDispatch() {
    return new StubCampaignDispatch();
  }

  @Bean
  @ConditionalOnMissingBean(NotificationDispatchPort.class)
  NotificationDispatchPort marketingNotificationDispatchPort(
      org.springframework.beans.factory.ObjectProvider<OutboxPublisher> outbox) {
    OutboxPublisher publisher = outbox.getIfAvailable();
    if (publisher != null) {
      return new StubMarketingNotificationDispatch(publisher);
    }
    return new NotificationDispatchPort() {
      @Override
      public void notifyCouponBudgetExhausted(String couponCode, UUID couponId) {}

      @Override
      public void notifyDailyBudgetBurnDigest(List<BudgetBurnItem> items) {}

      @Override
      public void notifyCampaignBudgetPaused(String campaignName, UUID campaignId) {}
    };
  }

  /** Default stub; overridden when medmate.marketing.banner.image-validation=http. */
  @Bean
  @ConditionalOnMissingBean(BannerImageValidatorPort.class)
  BannerImageValidatorPort stubBannerImageValidator() {
    return new StubBannerImageValidator();
  }

  /** Stub until apps/api MarketingBridgeConfig wires customer LoyaltyService. */
  @Bean
  @ConditionalOnMissingBean(LoyaltyAdminPort.class)
  LoyaltyAdminPort stubLoyaltyAdminPort(Clock clock) {
    return new LoyaltyAdminPort() {
      @Override
      public Map<String, Object> getProgram(MedmatePrincipal principal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("earn_rate_rs_per_point", 100);
        data.put("redemption_rate_rs_per_point", new BigDecimal("1.00"));
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("SILVER", 12);
        thresholds.put("GOLD", 50);
        thresholds.put("PLATINUM", 120);
        data.put("tier_thresholds", thresholds);
        data.put("max_redemption_pct_per_order", 20);
        data.put("min_points_per_redemption", 10);
        data.put("points_expiry_days", 365);
        return data;
      }

      @Override
      public Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated_at", Instant.now(clock).toString());
        data.put("updated_by", principal == null ? null : principal.subject());
        return data;
      }

      @Override
      public Map<String, Object> overview(MedmatePrincipal principal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total_points_outstanding", 0);
        data.put("points_liability_rs", 0);
        data.put("avg_points_per_customer", 0);
        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("NONE", 0);
        tiers.put("SILVER", 0);
        tiers.put("GOLD", 0);
        tiers.put("PLATINUM", 0);
        data.put("tier_distribution", tiers);
        data.put("points_earned_last_30d", 0);
        data.put("points_redeemed_last_30d", 0);
        data.put("points_expired_last_30d", 0);
        return data;
      }

      @Override
      public Map<String, Object> adjust(
          MedmatePrincipal principal, UUID customerId, AdjustCommand cmd) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customer_id", customerId);
        data.put("points_adjusted", cmd.points());
        data.put("points_balance_after", cmd.points() == null ? Integer.valueOf(0) : cmd.points());
        data.put("transaction_id", UUID.randomUUID());
        data.put("adjusted_by", principal == null ? null : principal.subject());
        data.put("adjusted_at", Instant.now(clock).toString());
        return data;
      }
    };
  }

  /**
   * Stub until apps/api MarketingBridgeConfig wires customer ReferralService. Returns empty
   * overview + default program settings for unit tests.
   */
  @Bean
  @ConditionalOnMissingBean(ReferralAdminPort.class)
  ReferralAdminPort stubReferralAdminPort(Clock clock) {
    return new ReferralAdminPort() {
      @Override
      public OverviewResult overview(
          MedmatePrincipal principal, String status, Integer page, Integer limit) {
        int p = page == null || page < 1 ? 1 : page;
        int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
        Map<String, Object> chips = new LinkedHashMap<>();
        chips.put("total_referrals", 0);
        chips.put("converted_referrals", 0);
        chips.put("pending_rewards_rs", BigDecimal.ZERO.setScale(2));
        chips.put("referral_cac_rs", 0);
        chips.put("referral_mrr_rs", 0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chips", chips);
        data.put("top_referrers", List.of());
        data.put("referrals", List.of());
        return new OverviewResult(data, PaginationMeta.of(p, lim, 0));
      }

      @Override
      public Map<String, Object> getProgram(MedmatePrincipal principal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reward_for_referrer_rs", new BigDecimal("100.00"));
        data.put("reward_for_referee_rs", new BigDecimal("100.00"));
        data.put("is_active", true);
        data.put("reward_expiry_days", 365);
        data.put(
            "conditions",
            "Reward credited after referee's first DELIVERED order. One code per customer.");
        return data;
      }

      @Override
      public Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated_at", Instant.now(clock).toString());
        data.put("updated_by", principal == null ? null : principal.subject());
        return data;
      }
    };
  }
}
