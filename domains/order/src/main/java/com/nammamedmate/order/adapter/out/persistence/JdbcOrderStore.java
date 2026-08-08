package com.nammamedmate.order.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import com.nammamedmate.order.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcOrderStore implements OrderStore {

  private static final TypeReference<List<Map<String, Object>>> ITEMS_TYPE =
      new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcOrderStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public Order insert(Order order) {
    jdbc.update(
        """
        INSERT INTO orders (
          id, order_number, customer_id, pharmacy_id, cart_id, items,
          item_total_paise, coupon_code, coupon_discount_paise, delivery_fee_paise,
          handling_fee_paise, wallet_applied_paise, total_payable_paise,
          payment_method, payment_status, razorpay_order_id, razorpay_payment_id,
          prescription_id, delivery_address_id, delivery_instructions, status,
          rider_id, delivery_otp_hash, placement_idempotency_key, confirmed_at,
          estimated_delivery_at, created_at, updated_at,
          accepted_at, delivered_at, sla_deadline, sla_breached, rider_assigned_at,
          otp_verified_at, ready_for_pickup_at, rider_escalation_at, cancel_reason
        ) VALUES (
          ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?, ?, ?, ?, ?
        )
        """,
        order.id(),
        order.orderNumber(),
        order.customerId(),
        order.pharmacyId(),
        order.cartId(),
        toJson(order.items()),
        order.itemTotalPaise(),
        order.couponCode(),
        order.couponDiscountPaise(),
        order.deliveryFeePaise(),
        order.handlingFeePaise(),
        order.walletAppliedPaise(),
        order.totalPayablePaise(),
        order.paymentMethod().name(),
        order.paymentStatus().name(),
        order.razorpayOrderId(),
        order.razorpayPaymentId(),
        order.prescriptionId(),
        order.deliveryAddressId(),
        order.deliveryInstructions(),
        order.status().name(),
        order.riderId(),
        order.deliveryOtpHash(),
        order.placementIdempotencyKey(),
        ts(order.confirmedAt()),
        ts(order.estimatedDeliveryAt()),
        Timestamp.from(order.createdAt()),
        Timestamp.from(order.updatedAt()),
        ts(order.acceptedAt()),
        ts(order.deliveredAt()),
        ts(order.slaDeadline()),
        order.slaBreached(),
        ts(order.riderAssignedAt()),
        ts(order.otpVerifiedAt()),
        ts(order.readyForPickupAt()),
        ts(order.riderEscalationAt()),
        order.cancelReason());
    return order;
  }

  @Override
  public Order update(Order order) {
    int n =
        jdbc.update(
            """
            UPDATE orders SET
              payment_status = ?,
              razorpay_order_id = ?,
              razorpay_payment_id = ?,
              status = ?,
              rider_id = ?,
              delivery_otp_hash = ?,
              confirmed_at = ?,
              estimated_delivery_at = ?,
              updated_at = ?,
              accepted_at = ?,
              delivered_at = ?,
              sla_deadline = ?,
              sla_breached = ?,
              rider_assigned_at = ?,
              otp_verified_at = ?,
              ready_for_pickup_at = ?,
              rider_escalation_at = ?,
              cancel_reason = ?
            WHERE id = ? AND deleted_at IS NULL
            """,
            order.paymentStatus().name(),
            order.razorpayOrderId(),
            order.razorpayPaymentId(),
            order.status().name(),
            order.riderId(),
            order.deliveryOtpHash(),
            ts(order.confirmedAt()),
            ts(order.estimatedDeliveryAt()),
            Timestamp.from(order.updatedAt()),
            ts(order.acceptedAt()),
            ts(order.deliveredAt()),
            ts(order.slaDeadline()),
            order.slaBreached(),
            ts(order.riderAssignedAt()),
            ts(order.otpVerifiedAt()),
            ts(order.readyForPickupAt()),
            ts(order.riderEscalationAt()),
            order.cancelReason(),
            order.id());
    if (n == 0) {
      throw new IllegalStateException("order not found for update: " + order.id());
    }
    return order;
  }

  @Override
  public Optional<Order> findById(UUID orderId) {
    List<Order> rows =
        jdbc.query(
            "SELECT * FROM orders WHERE id = ? AND deleted_at IS NULL", this::mapOrder, orderId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<Order> findByCustomerAndId(UUID customerId, UUID orderId) {
    List<Order> rows =
        jdbc.query(
            """
            SELECT * FROM orders
            WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
            """,
            this::mapOrder,
            orderId,
            customerId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<Order> findByPharmacyAndId(UUID pharmacyId, UUID orderId) {
    List<Order> rows =
        jdbc.query(
            """
            SELECT * FROM orders
            WHERE id = ? AND pharmacy_id = ? AND deleted_at IS NULL
            """,
            this::mapOrder,
            orderId,
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<Order> findByPlacementIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    List<Order> rows =
        jdbc.query(
            """
            SELECT * FROM orders
            WHERE placement_idempotency_key = ? AND deleted_at IS NULL
            """,
            this::mapOrder,
            idempotencyKey);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<Order> findByRazorpayOrderId(String razorpayOrderId) {
    if (razorpayOrderId == null || razorpayOrderId.isBlank()) {
      return Optional.empty();
    }
    List<Order> rows =
        jdbc.query(
            """
            SELECT * FROM orders
            WHERE razorpay_order_id = ? AND deleted_at IS NULL
            LIMIT 1
            """,
            this::mapOrder,
            razorpayOrderId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public int nextSequence(LocalDate dateIst) {
    Integer seq =
        jdbc.query(
            """
            INSERT INTO order_number_sequence (date_ist, last_seq)
            VALUES (?, 1)
            ON CONFLICT (date_ist) DO UPDATE
              SET last_seq = order_number_sequence.last_seq + 1
            RETURNING last_seq
            """,
            rs -> rs.next() ? rs.getInt(1) : null,
            dateIst);
    if (seq == null) {
      throw new IllegalStateException("failed to allocate order number sequence");
    }
    return seq;
  }

  @Override
  public boolean hasActiveOrders(UUID customerId) {
    Boolean found =
        jdbc.query(
            """
            SELECT EXISTS(
              SELECT 1 FROM orders
              WHERE customer_id = ?
                AND deleted_at IS NULL
                AND status NOT IN ('DELIVERED', 'CANCELLED')
            )
            """,
            rs -> {
              if (!rs.next()) {
                return false;
              }
              return rs.getBoolean(1);
            },
            customerId);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public boolean hasPlacedAnyOrder(UUID customerId) {
    Boolean found =
        jdbc.query(
            """
            SELECT EXISTS(
              SELECT 1 FROM orders
              WHERE customer_id = ?
                AND deleted_at IS NULL
            )
            """,
            rs -> {
              if (!rs.next()) {
                return false;
              }
              return rs.getBoolean(1);
            },
            customerId);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public boolean isAddressInActiveOrder(UUID addressId) {
    Boolean found =
        jdbc.query(
            """
            SELECT EXISTS(
              SELECT 1 FROM orders
              WHERE delivery_address_id = ?
                AND deleted_at IS NULL
                AND status NOT IN ('DELIVERED', 'CANCELLED')
            )
            """,
            rs -> {
              if (!rs.next()) {
                return false;
              }
              return rs.getBoolean(1);
            },
            addressId);
    return Boolean.TRUE.equals(found);
  }

  @Override
  public Optional<String> findPharmacyPhone(UUID pharmacyId) {
    List<String> rows =
        jdbc.query(
            """
            SELECT phone FROM pharmacies
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> rs.getString("phone"),
            pharmacyId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    String phone = rows.getFirst();
    if (phone == null || phone.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(phone);
  }

  @Override
  public List<Order> findPendingAcceptanceTimedOut(Instant deadlineBefore, int limit) {
    return jdbc.query(
        """
        SELECT * FROM orders
        WHERE deleted_at IS NULL
          AND status = 'PENDING_ACCEPTANCE'
          AND confirmed_at IS NOT NULL
          AND confirmed_at <= ?
        ORDER BY confirmed_at ASC
        LIMIT ?
        """,
        this::mapOrder,
        Timestamp.from(deadlineBefore),
        limit);
  }

  @Override
  public List<Order> findReadyWithoutRiderEscalation(Instant readyBefore, int limit) {
    return jdbc.query(
        """
        SELECT * FROM orders
        WHERE deleted_at IS NULL
          AND status = 'READY_FOR_PICKUP'
          AND rider_id IS NULL
          AND rider_escalation_at IS NULL
          AND ready_for_pickup_at IS NOT NULL
          AND ready_for_pickup_at <= ?
        ORDER BY ready_for_pickup_at ASC
        LIMIT ?
        """,
        this::mapOrder,
        Timestamp.from(readyBefore),
        limit);
  }

  @Override
  public List<Order> findOpenPastSlaDeadline(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM orders
        WHERE deleted_at IS NULL
          AND sla_breached = FALSE
          AND sla_deadline IS NOT NULL
          AND sla_deadline < ?
          AND status NOT IN ('DELIVERED', 'CANCELLED')
        ORDER BY sla_deadline ASC
        LIMIT ?
        """,
        this::mapOrder,
        Timestamp.from(now),
        limit);
  }

  @Override
  public List<Order> listCustomerHistory(
      UUID customerId, String statusFilter, int offset, int limit) {
    if ("DELIVERED".equals(statusFilter) || "CANCELLED".equals(statusFilter)) {
      return jdbc.query(
          """
          SELECT * FROM orders
          WHERE customer_id = ?
            AND deleted_at IS NULL
            AND status = ?
          ORDER BY created_at DESC
          OFFSET ? LIMIT ?
          """,
          this::mapOrder,
          customerId,
          statusFilter,
          offset,
          limit);
    }
    return jdbc.query(
        """
        SELECT * FROM orders
        WHERE customer_id = ?
          AND deleted_at IS NULL
          AND status IN ('DELIVERED', 'CANCELLED')
        ORDER BY created_at DESC
        OFFSET ? LIMIT ?
        """,
        this::mapOrder,
        customerId,
        offset,
        limit);
  }

  @Override
  public long countCustomerHistory(UUID customerId, String statusFilter) {
    Long total;
    if ("DELIVERED".equals(statusFilter) || "CANCELLED".equals(statusFilter)) {
      total =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM orders
              WHERE customer_id = ?
                AND deleted_at IS NULL
                AND status = ?
              """,
              Long.class,
              customerId,
              statusFilter);
    } else {
      total =
          jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM orders
              WHERE customer_id = ?
                AND deleted_at IS NULL
                AND status IN ('DELIVERED', 'CANCELLED')
              """,
              Long.class,
              customerId);
    }
    return total == null ? 0L : total;
  }

  @Override
  public List<Order> listCustomerActive(UUID customerId) {
    return jdbc.query(
        """
        SELECT * FROM orders
        WHERE customer_id = ?
          AND deleted_at IS NULL
          AND status NOT IN ('DELIVERED', 'CANCELLED')
        ORDER BY created_at DESC
        """,
        this::mapOrder,
        customerId);
  }

  private Order mapOrder(ResultSet rs, int rowNum) throws SQLException {
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
        rs.getString("razorpay_order_id"),
        rs.getString("razorpay_payment_id"),
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
    if (json == null) {
      return List.of();
    }
    if (json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> raw = objectMapper.readValue(json, ITEMS_TYPE);
      List<OrderItemSnapshot> out = new ArrayList<>();
      for (Map<String, Object> m : raw) {
        boolean rxRequired = Boolean.parseBoolean(String.valueOf(m.get("rx_required")));
        out.add(
            new OrderItemSnapshot(
                uuid(m.get("product_id")),
                str(m.get("name")),
                ((Number) m.get("quantity")).intValue(),
                ((Number) m.get("unit_price_paise")).longValue(),
                ((Number) m.get("line_total_paise")).longValue(),
                rxRequired));
      }
      return out;
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private String toJson(List<OrderItemSnapshot> items) {
    List<Map<String, Object>> raw = new ArrayList<>();
    for (OrderItemSnapshot item : items) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("product_id", item.productId());
      m.put("name", item.name());
      m.put("quantity", item.quantity());
      m.put("unit_price_paise", item.unitPricePaise());
      m.put("line_total_paise", item.lineTotalPaise());
      m.put("rx_required", item.rxRequired());
      raw.add(m);
    }
    try {
      return objectMapper.writeValueAsString(raw);
    } catch (JsonProcessingException e) {
      return "[]";
    }
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static UUID uuid(Object v) {
    if (v == null) {
      return null;
    }
    return UUID.fromString(String.valueOf(v));
  }

  private static String str(Object v) {
    return v == null ? null : String.valueOf(v);
  }
}
