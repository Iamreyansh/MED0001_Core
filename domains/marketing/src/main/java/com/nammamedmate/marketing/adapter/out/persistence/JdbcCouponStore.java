package com.nammamedmate.marketing.adapter.out.persistence;

import com.nammamedmate.marketing.application.port.out.CouponStore;
import com.nammamedmate.marketing.domain.Coupon;
import com.nammamedmate.marketing.domain.CouponStatus;
import com.nammamedmate.marketing.domain.CouponType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCouponStore implements CouponStore {

  private static final String SELECT =
      """
      SELECT id, code, type, percent_value, value_paise, min_order_value_paise,
             max_discount_cap_paise, budget_total_paise, budget_used_paise,
             redemptions_count, max_redemptions_total, max_per_user, segment_ids,
             is_first_order_only, is_rx_orders_only, valid_from, valid_until,
             status, description, terms, created_by, created_at, updated_at
      FROM coupons
      """;

  private final JdbcTemplate jdbc;

  public JdbcCouponStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Coupon insert(Coupon coupon) {
    jdbc.update(
        """
        INSERT INTO coupons (
          id, code, type, percent_value, value_paise, min_order_value_paise,
          max_discount_cap_paise, budget_total_paise, budget_used_paise,
          redemptions_count, max_redemptions_total, max_per_user, segment_ids,
          is_first_order_only, is_rx_orders_only, valid_from, valid_until,
          status, description, terms, created_by, created_at, updated_at
        )         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        coupon.id(),
        coupon.code(),
        coupon.type().name(),
        coupon.percentValue(),
        coupon.valuePaise(),
        coupon.minOrderValuePaise(),
        coupon.maxDiscountCapPaise(),
        coupon.budgetTotalPaise(),
        coupon.budgetUsedPaise(),
        coupon.redemptionsCount(),
        coupon.maxRedemptionsTotal(),
        coupon.maxPerUser(),
        toUuidArrayLiteral(coupon.segmentIds()),
        coupon.firstOrderOnly(),
        coupon.rxOrdersOnly(),
        Timestamp.from(coupon.validFrom()),
        Timestamp.from(coupon.validUntil()),
        coupon.status().name(),
        coupon.description(),
        coupon.terms(),
        coupon.createdBy(),
        Timestamp.from(coupon.createdAt()),
        Timestamp.from(coupon.updatedAt()));
    return coupon;
  }

  @Override
  public Optional<Coupon> findByCode(String code) {
    List<Coupon> rows =
        jdbc.query(SELECT + " WHERE UPPER(code) = UPPER(?)", (rs, i) -> mapCoupon(rs), code);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Coupon> findByCodeForUpdate(String code) {
    List<Coupon> rows =
        jdbc.query(
            SELECT + " WHERE UPPER(code) = UPPER(?) FOR UPDATE", (rs, i) -> mapCoupon(rs), code);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Coupon> findById(UUID id) {
    List<Coupon> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> mapCoupon(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public List<Coupon> list(
      CouponStatus status, CouponType type, String sort, String order, int offset, int limit) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    if (type != null) {
      sql.append(" AND type = ?");
      args.add(type.name());
    }
    String key = sort == null ? "created_at" : sort.toLowerCase(Locale.ROOT);
    String sortCol =
        key.equals("redemptions") || key.equals("redemptions_count")
            ? "redemptions_count"
            : key.equals("budget_used") || key.equals("budget_used_paise")
                ? "budget_used_paise"
                : key.equals("code") ? "code" : "created_at";
    String dir = order != null && order.equalsIgnoreCase("asc") ? "ASC" : "DESC";
    sql.append(" ORDER BY ").append(sortCol).append(' ').append(dir);
    sql.append(" LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), (rs, i) -> mapCoupon(rs), args.toArray());
  }

  @Override
  public long count(CouponStatus status, CouponType type) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM coupons WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    if (type != null) {
      sql.append(" AND type = ?");
      args.add(type.name());
    }
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    long count = n == null ? 0L : n;
    return count;
  }

  @Override
  public Chips chips() {
    return jdbc.query(
            """
            SELECT
              COUNT(*) FILTER (WHERE status = 'ACTIVE')::int AS active_count,
              COALESCE(SUM(redemptions_count), 0)::bigint AS total_redemptions,
              COALESCE(SUM(budget_used_paise), 0)::bigint AS discount_spend,
              COALESCE(SUM(budget_total_paise), 0)::bigint AS marketing_spend
            FROM coupons
            """,
            (rs, i) ->
                new Chips(
                    rs.getInt("active_count"),
                    rs.getLong("total_redemptions"),
                    rs.getLong("discount_spend"),
                    rs.getLong("marketing_spend")))
        .getFirst();
  }

  @Override
  public void update(Coupon coupon) {
    jdbc.update(
        """
        UPDATE coupons SET
          percent_value = ?, value_paise = ?, min_order_value_paise = ?,
          max_discount_cap_paise = ?, budget_total_paise = ?, budget_used_paise = ?,
          redemptions_count = ?, max_redemptions_total = ?, max_per_user = ?,
          segment_ids = ?::uuid[], is_first_order_only = ?, is_rx_orders_only = ?,
          valid_from = ?, valid_until = ?, status = ?, description = ?, terms = ?,
          updated_at = ?
        WHERE id = ?
        """,
        coupon.percentValue(),
        coupon.valuePaise(),
        coupon.minOrderValuePaise(),
        coupon.maxDiscountCapPaise(),
        coupon.budgetTotalPaise(),
        coupon.budgetUsedPaise(),
        coupon.redemptionsCount(),
        coupon.maxRedemptionsTotal(),
        coupon.maxPerUser(),
        toUuidArrayLiteral(coupon.segmentIds()),
        coupon.firstOrderOnly(),
        coupon.rxOrdersOnly(),
        Timestamp.from(coupon.validFrom()),
        Timestamp.from(coupon.validUntil()),
        coupon.status().name(),
        coupon.description(),
        coupon.terms(),
        Timestamp.from(coupon.updatedAt()),
        coupon.id());
  }

  @Override
  public void hardDelete(UUID id) {
    jdbc.update("DELETE FROM coupons WHERE id = ?", id);
  }

  @Override
  public boolean isSegmentReferencedByActiveCoupon(UUID segmentId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM coupons
            WHERE status IN ('ACTIVE', 'PAUSED')
              AND ? = ANY(segment_ids)
            """,
            Long.class,
            segmentId);
    long count = n == null ? 0L : n;
    return count > 0L;
  }

  @Override
  public int countRedemptionsForCustomer(UUID couponId, UUID customerId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM coupon_redemptions
            WHERE coupon_id = ? AND customer_id = ?
            """,
            Integer.class,
            couponId,
            customerId);
    return n == null ? 0 : n;
  }

  @Override
  public void insertRedemption(
      UUID id,
      UUID couponId,
      UUID orderId,
      UUID customerId,
      long discountAppliedPaise,
      long orderTotalPaise,
      Instant redeemedAt) {
    jdbc.update(
        """
        INSERT INTO coupon_redemptions (
          id, coupon_id, order_id, customer_id, discount_applied_paise,
          order_total_paise, redeemed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        couponId,
        orderId,
        customerId,
        discountAppliedPaise,
        orderTotalPaise,
        Timestamp.from(redeemedAt));
  }

  @Override
  public List<RedemptionRow> listRedemptions(UUID couponId, int offset, int limit) {
    return jdbc.query(
        """
        SELECT r.id, r.customer_id, COALESCE(c.name, '') AS customer_name,
               r.order_id, r.discount_applied_paise, r.redeemed_at
        FROM coupon_redemptions r
        LEFT JOIN customers c ON c.id = r.customer_id
        WHERE r.coupon_id = ?
        ORDER BY r.redeemed_at DESC
        LIMIT ? OFFSET ?
        """,
        (rs, i) ->
            new RedemptionRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("customer_id"),
                rs.getString("customer_name"),
                (UUID) rs.getObject("order_id"),
                rs.getLong("discount_applied_paise"),
                rs.getTimestamp("redeemed_at").toInstant()),
        couponId,
        limit,
        offset);
  }

  @Override
  public long countRedemptions(UUID couponId) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM coupon_redemptions WHERE coupon_id = ?", Long.class, couponId);
    long count = n == null ? 0L : n;
    return count;
  }

  @Override
  public List<DailyRedemption> dailyRedemptions(UUID couponId, int limit) {
    return jdbc.query(
        """
        SELECT (redeemed_at AT TIME ZONE 'UTC')::date AS d, COUNT(*)::int AS c
        FROM coupon_redemptions
        WHERE coupon_id = ?
        GROUP BY d
        ORDER BY d DESC
        LIMIT ?
        """,
        (rs, i) -> new DailyRedemption(rs.getObject("d", LocalDate.class), rs.getInt("c")),
        couponId,
        limit);
  }

  @Override
  public Economics economics(UUID couponId) {
    return jdbc.query(
            """
            SELECT COALESCE(SUM(discount_applied_paise), 0)::bigint AS discount_spend,
                   COALESCE(SUM(order_total_paise), 0)::bigint AS revenue
            FROM coupon_redemptions
            WHERE coupon_id = ?
            """,
            (rs, i) -> new Economics(rs.getLong("discount_spend"), rs.getLong("revenue")),
            couponId)
        .getFirst();
  }

  @Override
  public List<BudgetBurnRow> highBurnCouponsForDay(LocalDate istDay) {
    return jdbc.query(
        """
        SELECT c.code, c.budget_total_paise, c.budget_used_paise
        FROM coupons c
        WHERE c.budget_total_paise > 0
          AND (c.budget_used_paise::numeric / c.budget_total_paise) > 0.7
          AND EXISTS (
            SELECT 1 FROM coupon_redemptions r
            WHERE r.coupon_id = c.id
              AND (r.redeemed_at AT TIME ZONE 'Asia/Kolkata')::date = ?
          )
        ORDER BY c.code
        """,
        (rs, i) ->
            new BudgetBurnRow(
                rs.getString("code"),
                rs.getLong("budget_total_paise"),
                rs.getLong("budget_used_paise")),
        istDay);
  }

  private static String toUuidArrayLiteral(List<UUID> ids) {
    if (ids.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(ids.get(i));
    }
    sb.append('}');
    return sb.toString();
  }

  private static Coupon mapCoupon(ResultSet rs) throws SQLException {
    return new Coupon(
        (UUID) rs.getObject("id"),
        rs.getString("code"),
        CouponType.valueOf(rs.getString("type")),
        (Integer) rs.getObject("percent_value"),
        (Long) rs.getObject("value_paise"),
        rs.getLong("min_order_value_paise"),
        (Long) rs.getObject("max_discount_cap_paise"),
        rs.getLong("budget_total_paise"),
        rs.getLong("budget_used_paise"),
        rs.getInt("redemptions_count"),
        (Integer) rs.getObject("max_redemptions_total"),
        rs.getInt("max_per_user"),
        readUuidArray(rs.getArray("segment_ids")),
        rs.getBoolean("is_first_order_only"),
        rs.getBoolean("is_rx_orders_only"),
        rs.getTimestamp("valid_from").toInstant(),
        rs.getTimestamp("valid_until").toInstant(),
        CouponStatus.valueOf(rs.getString("status")),
        rs.getString("description"),
        rs.getString("terms"),
        (UUID) rs.getObject("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static List<UUID> readUuidArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (!(raw instanceof Object[] objs)) {
      return List.of();
    }
    List<UUID> out = new ArrayList<>(objs.length);
    for (Object o : objs) {
      if (o instanceof UUID u) {
        out.add(u);
      } else if (o != null) {
        out.add(UUID.fromString(o.toString()));
      }
    }
    return out;
  }
}
