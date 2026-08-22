package com.nammamedmate.api.config;

import com.nammamedmate.customer.application.LoyaltyService;
import com.nammamedmate.customer.application.ReferralService;
import com.nammamedmate.customer.application.port.out.LoyaltyCartPort;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.marketing.application.CouponService;
import com.nammamedmate.marketing.application.port.out.CustomerGeoPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyAdminPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyTierReadPort;
import com.nammamedmate.marketing.application.port.out.OrderSegmentMetricsPort;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort;
import com.nammamedmate.marketing.domain.CustomerMetrics;
import com.nammamedmate.order.application.port.out.PlatformCouponPort;
import com.nammamedmate.order.domain.CartPricing.CouponType;
import com.nammamedmate.security.MedmatePrincipal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EPIC-013: JDBC bridges for segment metrics/geo; coupon port bridge into order; referral/loyalty
 * admin façades → customer services (no domain→domain compile deps).
 */
@Configuration
public class MarketingBridgeConfig {

  @Bean
  @Primary
  ReferralAdminPort marketingReferralAdminPort(ReferralService referrals) {
    return new ReferralAdminPort() {
      @Override
      public OverviewResult overview(
          MedmatePrincipal principal, String status, Integer page, Integer limit) {
        ReferralService.AdminOverviewResult r =
            referrals.adminOverview(principal, status, page, limit);
        return new OverviewResult(r.data(), r.meta());
      }

      @Override
      public Map<String, Object> getProgram(MedmatePrincipal principal) {
        return referrals.getProgram(principal);
      }

      @Override
      public Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd) {
        return referrals.patchProgram(
            principal,
            new ReferralService.PatchProgramCommand(
                cmd.rewardForReferrerRs(),
                cmd.rewardForRefereeRs(),
                cmd.isActive(),
                cmd.rewardExpiryDays(),
                cmd.conditions()));
      }
    };
  }

  @Bean
  @Primary
  LoyaltyAdminPort marketingLoyaltyAdminPort(LoyaltyService loyalty) {
    return new LoyaltyAdminPort() {
      @Override
      public Map<String, Object> getProgram(MedmatePrincipal principal) {
        return loyalty.getProgram(principal);
      }

      @Override
      public Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd) {
        return loyalty.patchProgram(
            principal,
            new LoyaltyService.PatchProgramCommand(
                cmd.earnRateRsPerPoint(),
                cmd.redemptionRateRsPerPoint(),
                cmd.tierSilverPts(),
                cmd.tierGoldPts(),
                cmd.tierPlatinumPts(),
                cmd.maxRedemptionPctPerOrder(),
                cmd.minPointsPerRedemption(),
                cmd.pointsExpiryDays()));
      }

      @Override
      public Map<String, Object> overview(MedmatePrincipal principal) {
        return loyalty.adminOverview(principal);
      }

      @Override
      public Map<String, Object> adjust(
          MedmatePrincipal principal, UUID customerId, AdjustCommand cmd) {
        if (cmd.points() == null) {
          throw new AppException("VALIDATION_ERROR", "points is required", 400);
        }
        UUID orderRef = null;
        if (cmd.referenceOrderId() != null && !cmd.referenceOrderId().isBlank()) {
          try {
            orderRef = Ids.parse(cmd.referenceOrderId().trim());
          } catch (RuntimeException ex) {
            throw new AppException("VALIDATION_ERROR", "reference_order_id must be a UUID", 400);
          }
        }
        return loyalty.adminAdjust(principal, customerId, cmd.points(), cmd.reason(), orderRef);
      }
    };
  }

  @Bean
  @Primary
  LoyaltyTierReadPort jdbcLoyaltyTierReadPort(JdbcTemplate jdbc) {
    return (Collection<UUID> customerIds) -> {
      if (customerIds == null || customerIds.isEmpty()) {
        return Map.of();
      }
      List<UUID> ids = List.copyOf(customerIds);
      String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
      String sql =
          "SELECT customer_id, tier FROM customer_loyalty WHERE customer_id IN ("
              + placeholders
              + ")";
      Map<UUID, String> out = new HashMap<>();
      jdbc.query(
          sql,
          rs -> {
            out.put((UUID) rs.getObject("customer_id"), rs.getString("tier"));
          },
          ids.toArray());
      return Map.copyOf(out);
    };
  }

  @Bean
  @Primary
  LoyaltyCartPort jdbcLoyaltyCartPort(JdbcTemplate jdbc) {
    return (UUID customerId, UUID cartId) -> {
      List<Long> rows =
          jdbc.query(
              """
              SELECT COALESCE(SUM(
                       (elem->>'unit_price_paise')::bigint * (elem->>'quantity')::int
                     ), 0) AS item_total_paise
              FROM carts c
              LEFT JOIN LATERAL jsonb_array_elements(c.items) AS elem ON TRUE
              WHERE c.id = ? AND c.customer_id = ?
              GROUP BY c.id
              """,
              (rs, i) -> rs.getLong("item_total_paise"),
              cartId,
              customerId);
      if (rows.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(rows.getFirst());
    };
  }

  @Bean
  @Primary
  PlatformCouponPort marketingPlatformCouponPort(CouponService coupons) {
    return new PlatformCouponPort() {
      @Override
      public Quote apply(String couponCode, long itemTotalPaise) {
        CouponService.CartQuote q = coupons.applyForCart(couponCode, itemTotalPaise);
        CouponType type =
            switch (q.discountType()) {
              case "PERCENT" -> CouponType.PERCENT;
              case "FLAT" -> CouponType.FLAT;
              default -> CouponType.FREE_DELIVERY;
            };
        return new Quote(q.code(), type, q.discountPaise(), q.freeDelivery(), q.message());
      }

      @Override
      public void record(
          String couponCode,
          java.util.UUID orderId,
          java.util.UUID customerId,
          long discountPaise,
          long orderTotalPaise) {
        coupons.recordRedemption(couponCode, orderId, customerId, discountPaise, orderTotalPaise);
      }
    };
  }

  @Bean
  @Primary
  OrderSegmentMetricsPort jdbcOrderSegmentMetricsPort(JdbcTemplate jdbc, Clock clock) {
    return () -> {
      Instant now = clock.instant();
      Instant since30 = now.minus(Duration.ofDays(30));
      return jdbc.query(
          """
          SELECT c.id, c.name, c.phone, c.total_orders, c.total_ltv_paise, c.last_order_at,
                 c.city, c.created_at,
                 COALESCE((
                   SELECT AVG(o.total_payable_paise)::bigint
                   FROM orders o
                   WHERE o.customer_id = c.id
                     AND o.deleted_at IS NULL
                     AND o.status <> 'CANCELLED'
                 ), 0) AS avg_aov_paise,
                 EXISTS (
                   SELECT 1 FROM orders o
                   WHERE o.customer_id = c.id
                     AND o.deleted_at IS NULL
                     AND o.prescription_id IS NOT NULL
                 ) AS has_rx_orders,
                 COALESCE((
                   SELECT COUNT(*)::int FROM orders o
                   WHERE o.customer_id = c.id
                     AND o.deleted_at IS NULL
                     AND o.created_at >= ?
                 ), 0) AS orders_last_30_days
          FROM customers c
          WHERE c.deleted_at IS NULL
          """,
          (rs, i) -> {
            Instant created = rs.getTimestamp("created_at").toInstant();
            int ageDays = (int) Math.max(0, Duration.between(created, now).toDays());
            Timestamp lastTs = rs.getTimestamp("last_order_at");
            return new CustomerMetrics(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getInt("total_orders"),
                rs.getLong("total_ltv_paise"),
                lastTs == null ? null : lastTs.toInstant(),
                rs.getLong("avg_aov_paise"),
                rs.getBoolean("has_rx_orders"),
                ageDays,
                rs.getInt("orders_last_30_days"),
                rs.getString("city"),
                null,
                "NONE");
          },
          Timestamp.from(since30));
    };
  }

  @Bean
  @Primary
  CustomerGeoPort jdbcCustomerGeoPort(JdbcTemplate jdbc) {
    return (Collection<UUID> customerIds) -> {
      if (customerIds == null || customerIds.isEmpty()) {
        return Map.of();
      }
      List<UUID> ids = List.copyOf(customerIds);
      String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
      String sql =
          "SELECT DISTINCT ON (a.customer_id) a.customer_id, a.city, a.pincode"
              + " FROM customer_addresses a"
              + " WHERE a.deleted_at IS NULL AND a.customer_id IN ("
              + placeholders
              + ")"
              + " ORDER BY a.customer_id, a.is_default DESC, a.updated_at DESC";
      Map<UUID, CustomerGeoPort.Geo> out = new HashMap<>();
      jdbc.query(
          sql,
          rs -> {
            out.put(
                (UUID) rs.getObject("customer_id"),
                new CustomerGeoPort.Geo(rs.getString("city"), rs.getString("pincode")));
          },
          ids.toArray());
      return Map.copyOf(out);
    };
  }
}
