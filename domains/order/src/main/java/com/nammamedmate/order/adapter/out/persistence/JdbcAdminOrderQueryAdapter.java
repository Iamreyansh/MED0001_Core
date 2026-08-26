package com.nammamedmate.order.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort;
import com.nammamedmate.order.domain.AdminOrderSegment;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAdminOrderQueryAdapter implements AdminOrderQueryPort {

  private static final TypeReference<List<Map<String, Object>>> ITEMS_TYPE =
      new TypeReference<>() {};
  private static final String LIVE_IN =
      "'PENDING_ACCEPTANCE','ACCEPTED','PACKING','READY_FOR_PICKUP','OUT_FOR_DELIVERY'";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcAdminOrderQueryAdapter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<AdminOrderListRow> list(AdminListFilter filter) {
    StringBuilder sql = new StringBuilder(baseSelect());
    List<Object> args = new ArrayList<>();
    appendWhere(sql, args, filter);
    sql.append(" ORDER BY o.created_at DESC LIMIT ? OFFSET ?");
    args.add(filter.limit());
    args.add(Math.max(0, (filter.page() - 1) * filter.limit()));
    return jdbc.query(sql.toString(), this::mapRow, args.toArray());
  }

  @Override
  public long count(AdminListFilter filter) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT COUNT(*) FROM orders o
            JOIN customers c ON c.id = o.customer_id
            JOIN pharmacies p ON p.id = o.pharmacy_id
            LEFT JOIN customer_addresses a ON a.id = o.delivery_address_id
            """);
    List<Object> args = new ArrayList<>();
    appendWhere(sql, args, filter);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public SummaryAgg summary(AdminListFilter filter) {
    // Summary chips use the same filters but GMV/commission exclude CANCELLED
    StringBuilder base =
        new StringBuilder(
            """
            SELECT
              COUNT(*) FILTER (WHERE o.status <> 'CANCELLED') AS total_orders,
              COUNT(*) FILTER (WHERE o.status IN (%s)) AS live_now,
              COUNT(*) FILTER (
                WHERE o.status IN (%s)
                  AND o.sla_deadline IS NOT NULL
                  AND o.sla_deadline > ?
                  AND o.sla_deadline < ?
              ) AS sla_risk,
              COALESCE(SUM(o.total_payable_paise) FILTER (WHERE o.status <> 'CANCELLED'), 0)
                AS gmv_paise,
              COALESCE(
                SUM(
                  ROUND(o.total_payable_paise * COALESCE(p.commission_pct, 0) / 100.0)
                ) FILTER (WHERE o.status <> 'CANCELLED'),
                0
              ) AS commission_paise
            FROM orders o
            JOIN customers c ON c.id = o.customer_id
            JOIN pharmacies p ON p.id = o.pharmacy_id
            LEFT JOIN customer_addresses a ON a.id = o.delivery_address_id
            """
                .replace("%s", LIVE_IN));
    List<Object> args = new ArrayList<>();
    args.add(Timestamp.from(filter.now()));
    args.add(Timestamp.from(filter.now().plusSeconds(Order.SLA_RISK_THRESHOLD.getSeconds())));
    // Re-apply filter without segment (summary is fleet-wide for date/search filters)
    AdminListFilter unsegmented =
        new AdminListFilter(
            AdminOrderSegment.ALL,
            filter.search(),
            filter.pharmacyId(),
            filter.riderId(),
            filter.zoneId(),
            filter.paymentMethod(),
            filter.isRxOnly(),
            filter.fromDate(),
            filter.toDate(),
            filter.now(),
            filter.page(),
            filter.limit());
    appendWhere(base, args, unsegmented);
    return jdbc.query(
        base.toString(),
        rs -> {
          rs.next();
          return new SummaryAgg(
              rs.getLong("total_orders"),
              rs.getLong("live_now"),
              rs.getLong("sla_risk"),
              rs.getLong("gmv_paise"),
              rs.getLong("commission_paise"));
        },
        args.toArray());
  }

  @Override
  public List<AdminOrderListRow> listAllForExport(AdminListFilter filter, int maxRows) {
    StringBuilder sql = new StringBuilder(baseSelect());
    List<Object> args = new ArrayList<>();
    appendWhere(sql, args, filter);
    sql.append(" ORDER BY o.created_at DESC LIMIT ?");
    args.add(maxRows);
    return jdbc.query(sql.toString(), this::mapRow, args.toArray());
  }

  @Override
  public List<AdminOrderListRow> liveFeed(Instant now, int limit) {
    String sql =
        baseSelect()
            + """
            WHERE o.deleted_at IS NULL
              AND o.status IN (%s)
            ORDER BY
              CASE
                WHEN o.sla_deadline IS NOT NULL AND o.sla_deadline <= ? THEN 0
                WHEN o.sla_deadline IS NOT NULL
                  AND o.sla_deadline > ?
                  AND o.sla_deadline < ? THEN 1
                ELSE 2
              END ASC,
              o.sla_deadline ASC NULLS LAST
            LIMIT ?
            """
                .replace("%s", LIVE_IN);
    Timestamp nowTs = Timestamp.from(now);
    Timestamp riskEnd = Timestamp.from(now.plusSeconds(Order.SLA_RISK_THRESHOLD.getSeconds()));
    return jdbc.query(sql, this::mapRow, nowTs, nowTs, riskEnd, limit);
  }

  @Override
  public Optional<PharmacyAdminView> findPharmacy(UUID pharmacyId) {
    List<PharmacyAdminView> rows =
        jdbc.query(
            """
            SELECT id, COALESCE(business_name, name) AS name,
                   COALESCE(address->>'area', address->>'locality', '') AS area,
                   commission_pct
            FROM pharmacies
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) ->
                new PharmacyAdminView(
                    (UUID) rs.getObject("id"),
                    rs.getString("name"),
                    blankToNull(rs.getString("area")),
                    rs.getBigDecimal("commission_pct")),
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<CustomerAdminView> findCustomer(UUID customerId) {
    List<CustomerAdminView> rows =
        jdbc.query(
            """
            SELECT id, name, phone, total_orders, total_ltv_paise
            FROM customers
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) ->
                new CustomerAdminView(
                    (UUID) rs.getObject("id"),
                    rs.getString("name"),
                    rs.getString("phone"),
                    rs.getInt("total_orders"),
                    rs.getLong("total_ltv_paise")),
            customerId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<AdminStaffName> findAdminName(UUID adminId) {
    List<AdminStaffName> rows =
        jdbc.query(
            """
            SELECT id, name FROM admin_staff
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> new AdminStaffName((UUID) rs.getObject("id"), rs.getString("name")),
            adminId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<String> findAddressArea(UUID addressId) {
    List<String> rows =
        jdbc.query(
            """
            SELECT area_locality FROM customer_addresses
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> rs.getString("area_locality"),
            addressId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(blankToNull(rows.getFirst()));
  }

  private static String baseSelect() {
    return """
        SELECT o.*,
               c.name AS customer_name,
               c.phone AS customer_phone,
               COALESCE(p.business_name, p.name) AS pharmacy_name,
               COALESCE(a.area_locality, p.address->>'area', '') AS area,
               p.commission_pct AS commission_pct,
               EXISTS(
                 SELECT 1 FROM order_dispute d
                 WHERE d.order_id = o.id AND d.resolved = FALSE
               ) AS is_disputed
        FROM orders o
        JOIN customers c ON c.id = o.customer_id
        JOIN pharmacies p ON p.id = o.pharmacy_id
        LEFT JOIN customer_addresses a ON a.id = o.delivery_address_id
        """;
  }

  private void appendWhere(StringBuilder sql, List<Object> args, AdminListFilter filter) {
    sql.append(" WHERE o.deleted_at IS NULL");
    AdminOrderSegment segment = filter.segment() == null ? AdminOrderSegment.ALL : filter.segment();
    switch (segment) {
      case LIVE -> sql.append(" AND o.status IN (").append(LIVE_IN).append(')');
      case SLA_RISK -> {
        sql.append(" AND o.status IN (").append(LIVE_IN).append(')');
        sql.append(" AND o.sla_deadline IS NOT NULL");
        sql.append(" AND o.sla_deadline > ? AND o.sla_deadline < ?");
        args.add(Timestamp.from(filter.now()));
        args.add(Timestamp.from(filter.now().plusSeconds(Order.SLA_RISK_THRESHOLD.getSeconds())));
      }
      case DISPUTES ->
          sql.append(
              """
               AND EXISTS (
                 SELECT 1 FROM order_dispute d
                 WHERE d.order_id = o.id AND d.resolved = FALSE
               )
              """);
      case DELIVERED -> sql.append(" AND o.status = 'DELIVERED'");
      case CANCELLED -> sql.append(" AND o.status = 'CANCELLED'");
      case ALL -> {
        /* no status filter */
      }
    }
    String search = filter.search() == null ? null : filter.search().trim();
    if (search != null && !search.isEmpty()) {
      String q = "%" + search.toLowerCase() + "%";
      sql.append(
          """
           AND (
             LOWER(o.order_number) LIKE ?
             OR LOWER(CAST(o.id AS TEXT)) LIKE ?
             OR LOWER(COALESCE(c.name, '')) LIKE ?
             OR LOWER(COALESCE(c.phone, '')) LIKE ?
             OR LOWER(COALESCE(a.area_locality, '')) LIKE ?
             OR LOWER(COALESCE(p.business_name, p.name, '')) LIKE ?
           )
          """);
      args.add(q);
      args.add(q);
      args.add(q);
      args.add(q);
      args.add(q);
      args.add(q);
    }
    if (filter.pharmacyId() != null) {
      sql.append(" AND o.pharmacy_id = ?");
      args.add(filter.pharmacyId());
    }
    if (filter.riderId() != null) {
      sql.append(" AND o.rider_id = ?");
      args.add(filter.riderId());
    }
    if (filter.zoneId() != null) {
      sql.append(" AND p.zone_id = ?");
      args.add(filter.zoneId());
    }
    if (filter.paymentMethod() != null) {
      sql.append(" AND o.payment_method = ?");
      args.add(filter.paymentMethod().name());
    }
    if (Boolean.TRUE.equals(filter.isRxOnly())) {
      sql.append(" AND o.prescription_id IS NOT NULL");
    } else if (Boolean.FALSE.equals(filter.isRxOnly())) {
      sql.append(" AND o.prescription_id IS NULL");
    }
    if (filter.fromDate() != null) {
      sql.append(" AND o.created_at >= ?");
      args.add(
          Timestamp.from(filter.fromDate().atStartOfDay().toInstant(java.time.ZoneOffset.UTC)));
    }
    if (filter.toDate() != null) {
      sql.append(" AND o.created_at < ?");
      args.add(
          Timestamp.from(
              filter.toDate().plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)));
    }
  }

  private AdminOrderListRow mapRow(ResultSet rs, int i) throws SQLException {
    Order order = mapOrder(rs);
    BigDecimal pct = rs.getBigDecimal("commission_pct");
    return new AdminOrderListRow(
        order,
        rs.getString("customer_name"),
        rs.getString("customer_phone"),
        rs.getString("pharmacy_name"),
        blankToNull(rs.getString("area")),
        pct == null ? BigDecimal.ZERO : pct,
        rs.getBoolean("is_disputed"));
  }

  private Order mapOrder(ResultSet rs) throws SQLException {
    return new Order(
        (UUID) rs.getObject("id"),
        rs.getString("order_number"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("cart_id"),
        parseItems(rs.getString("items")),
        rs.getLong("item_total_paise"),
        rs.getString("coupon_code"),
        rs.getLong("coupon_discount_paise"),
        rs.getLong("delivery_fee_paise"),
        rs.getLong("handling_fee_paise"),
        rs.getLong("wallet_applied_paise"),
        rs.getLong("total_payable_paise"),
        PaymentMethod.valueOf(rs.getString("payment_method")),
        PaymentStatus.valueOf(rs.getString("payment_status")),
        rs.getString("gateway_order_id"),
        rs.getString("gateway_payment_id"),
        (UUID) rs.getObject("prescription_id"),
        (UUID) rs.getObject("delivery_address_id"),
        rs.getString("delivery_instructions"),
        OrderStatus.valueOf(rs.getString("status")),
        (UUID) rs.getObject("rider_id"),
        rs.getString("delivery_otp_hash"),
        rs.getString("placement_idempotency_key"),
        instant(rs.getTimestamp("confirmed_at")),
        instant(rs.getTimestamp("estimated_delivery_at")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instant(rs.getTimestamp("accepted_at")),
        instant(rs.getTimestamp("delivered_at")),
        instant(rs.getTimestamp("sla_deadline")),
        rs.getBoolean("sla_breached"),
        instant(rs.getTimestamp("rider_assigned_at")),
        instant(rs.getTimestamp("otp_verified_at")),
        instant(rs.getTimestamp("ready_for_pickup_at")),
        instant(rs.getTimestamp("rider_escalation_at")),
        rs.getString("cancel_reason"));
  }

  private List<OrderItemSnapshot> parseItems(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> raw = objectMapper.readValue(json, ITEMS_TYPE);
      List<OrderItemSnapshot> out = new ArrayList<>();
      for (Map<String, Object> m : raw) {
        boolean rxRequired = Boolean.parseBoolean(String.valueOf(m.get("rx_required")));
        out.add(
            new OrderItemSnapshot(
                UUID.fromString(String.valueOf(m.get("product_id"))),
                String.valueOf(m.get("name")),
                ((Number) m.get("quantity")).intValue(),
                ((Number) m.get("unit_price_paise")).longValue(),
                ((Number) m.get("line_total_paise")).longValue(),
                rxRequired));
      }
      return out;
    } catch (JsonProcessingException | RuntimeException e) {
      return List.of();
    }
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
