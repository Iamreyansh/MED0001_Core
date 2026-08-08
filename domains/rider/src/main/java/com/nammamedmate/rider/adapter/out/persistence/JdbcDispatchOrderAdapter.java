package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import com.nammamedmate.rider.domain.AssignmentOtps;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * JDBC orders/pharmacies/customers lookup (composition-root bean). No Gradle dep on domains/order.
 */
public class JdbcDispatchOrderAdapter implements DispatchOrderPort {

  private static final String ORDER_OTP_KEY = "order:delivery-otp:";
  private static final PasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);

  private final JdbcTemplate jdbc;
  private final StringRedisTemplate redis;

  public JdbcDispatchOrderAdapter(JdbcTemplate jdbc, StringRedisTemplate redis) {
    this.jdbc = jdbc;
    this.redis = redis;
  }

  public JdbcDispatchOrderAdapter(JdbcTemplate jdbc) {
    this(jdbc, null);
  }

  @Override
  public QueuePage listUnassignedReady(UUID zoneId, int page, int limit) {
    int offset = Math.max(0, (page - 1) * limit);
    String zoneClause = zoneId == null ? "" : " AND p.zone_id = ? ";
    String countSql =
        """
        SELECT COUNT(1)
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        WHERE o.deleted_at IS NULL
          AND o.status = 'READY_FOR_PICKUP'
          AND o.rider_id IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM order_assignments oa
            WHERE oa.order_id = o.id
              AND oa.status IN ('PENDING_ACCEPTANCE','ACCEPTED','PICKED_UP')
          )
        """
            + zoneClause;
    Integer total =
        zoneId == null
            ? jdbc.queryForObject(countSql, Integer.class)
            : jdbc.queryForObject(countSql, Integer.class, zoneId);
    String sql =
        """
        SELECT o.id, o.order_number, o.pharmacy_id, p.business_name, p.name AS pharmacy_fallback,
               p.zone_id, z.name AS zone_name,
               COALESCE(jsonb_array_length(o.items), 0) AS items_count,
               o.total_payable_paise, o.payment_method, o.created_at, o.ready_for_pickup_at,
               p.latitude, p.longitude
        FROM orders o
        JOIN pharmacies p ON p.id = o.pharmacy_id
        LEFT JOIN zones z ON z.id = p.zone_id
        WHERE o.deleted_at IS NULL
          AND o.status = 'READY_FOR_PICKUP'
          AND o.rider_id IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM order_assignments oa
            WHERE oa.order_id = o.id
              AND oa.status IN ('PENDING_ACCEPTANCE','ACCEPTED','PICKED_UP')
          )
        """
            + zoneClause
            + """
              ORDER BY COALESCE(o.ready_for_pickup_at, o.created_at) ASC
              LIMIT ? OFFSET ?
              """;
    List<QueueOrder> rows =
        zoneId == null
            ? jdbc.query(sql, (rs, i) -> mapQueue(rs), limit, offset)
            : jdbc.query(sql, (rs, i) -> mapQueue(rs), zoneId, limit, offset);
    return new QueuePage(rows, total == null ? 0 : total);
  }

  @Override
  public Optional<OrderDetails> findOrder(UUID orderId) {
    List<OrderDetails> rows =
        jdbc.query(
            """
            SELECT o.id, o.order_number, o.status, o.rider_id, o.pharmacy_id,
                   COALESCE(p.business_name, p.name) AS pharmacy_name,
                   CONCAT_WS(
                     ', ',
                     NULLIF(p.address->>'line1', ''),
                     NULLIF(p.address->>'area', ''),
                     NULLIF(p.address->>'city', ''),
                     p.city
                   ) AS pharmacy_address,
                   p.latitude, p.longitude, p.phone AS pharmacy_phone,
                   p.zone_id, z.name AS zone_name,
                   c.name AS customer_name, c.phone AS customer_phone,
                   CONCAT_WS(', ', a.flat_building, a.area_locality, a.city) AS delivery_address,
                   a.latitude AS delivery_lat, a.longitude AS delivery_lng,
                   COALESCE(jsonb_array_length(o.items), 0) AS items_count,
                   o.payment_method, o.total_payable_paise,
                   o.estimated_delivery_at, o.sla_deadline, o.delivery_otp_hash
            FROM orders o
            JOIN pharmacies p ON p.id = o.pharmacy_id
            LEFT JOIN zones z ON z.id = p.zone_id
            JOIN customers c ON c.id = o.customer_id
            JOIN customer_addresses a ON a.id = o.delivery_address_id
            WHERE o.id = ? AND o.deleted_at IS NULL
            """,
            (rs, i) -> mapDetails(rs),
            orderId);
    return rows.stream().findFirst();
  }

  @Override
  public void assignRiderOnOrder(UUID orderId, UUID riderId, Instant now) {
    jdbc.update(
        """
        UPDATE orders
        SET rider_id = ?, rider_assigned_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        riderId,
        Timestamp.from(now),
        Timestamp.from(now),
        orderId);
  }

  @Override
  public void clearRiderOnOrder(UUID orderId, Instant now) {
    jdbc.update(
        """
        UPDATE orders
        SET rider_id = NULL, rider_assigned_at = NULL, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(now),
        orderId);
  }

  @Override
  public void advanceStatus(
      UUID orderId,
      String fromStatus,
      String toStatus,
      String actorType,
      UUID actorId,
      String notes,
      Instant now) {
    if ("DELIVERED".equals(toStatus)) {
      jdbc.update(
          """
          UPDATE orders
          SET status = ?, delivered_at = ?, otp_verified_at = ?, updated_at = ?
          WHERE id = ? AND status = ? AND deleted_at IS NULL
          """,
          toStatus,
          Timestamp.from(now),
          Timestamp.from(now),
          Timestamp.from(now),
          orderId,
          fromStatus);
    } else {
      jdbc.update(
          """
          UPDATE orders
          SET status = ?, updated_at = ?
          WHERE id = ? AND status = ? AND deleted_at IS NULL
          """,
          toStatus,
          Timestamp.from(now),
          orderId,
          fromStatus);
    }
    jdbc.update(
        """
        INSERT INTO order_status_event (
          id, order_id, from_status, to_status, actor_type, actor_id, notes, created_at
        ) VALUES (?,?,?,?,?,?,?,?)
        """,
        Ids.newId(),
        orderId,
        fromStatus,
        toStatus,
        actorType,
        actorId,
        notes,
        Timestamp.from(now));
  }

  @Override
  public Optional<String> peekDeliveryOtp(UUID orderId) {
    if (redis == null || orderId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(redis.opsForValue().get(ORDER_OTP_KEY + orderId));
  }

  @Override
  public boolean verifyDeliveryOtp(UUID orderId, String otp) {
    if (otp == null) {
      return false;
    }
    if (otp.isBlank()) {
      return false;
    }
    Optional<String> cached = peekDeliveryOtp(orderId);
    if (cached.isPresent() && cached.get().equals(otp)) {
      return true;
    }
    List<String> hashes =
        jdbc.query(
            """
            SELECT delivery_otp_hash FROM orders
            WHERE id = ? AND deleted_at IS NULL
            """,
            (rs, i) -> rs.getString("delivery_otp_hash"),
            orderId);
    String hash = hashes.stream().findFirst().orElse(null);
    if (hash == null || hash.isBlank()) {
      return false;
    }
    if (hash.startsWith("$2")) {
      return BCRYPT.matches(otp, hash);
    }
    return AssignmentOtps.matches(otp, hash);
  }

  @Override
  public String ensureDeliveryOtp(UUID orderId, Instant now) {
    Optional<String> existing = peekDeliveryOtp(orderId);
    if (existing.isPresent()) {
      return existing.get();
    }
    String otp = AssignmentOtps.generate();
    String hash = BCRYPT.encode(otp);
    jdbc.update(
        """
        UPDATE orders
        SET delivery_otp_hash = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
          AND (delivery_otp_hash IS NULL OR delivery_otp_hash = '')
        """,
        hash,
        Timestamp.from(now),
        orderId);
    if (redis != null) {
      redis.opsForValue().set(ORDER_OTP_KEY + orderId, otp, java.time.Duration.ofHours(24));
    }
    return otp;
  }

  private static QueueOrder mapQueue(ResultSet rs) throws SQLException {
    String name = rs.getString("business_name");
    if (name == null || name.isBlank()) {
      name = rs.getString("pharmacy_fallback");
    }
    return new QueueOrder(
        (UUID) rs.getObject("id"),
        rs.getString("order_number"),
        (UUID) rs.getObject("pharmacy_id"),
        name,
        (UUID) rs.getObject("zone_id"),
        rs.getString("zone_name"),
        rs.getInt("items_count"),
        rs.getLong("total_payable_paise"),
        rs.getString("payment_method"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("ready_for_pickup_at") == null
            ? null
            : rs.getTimestamp("ready_for_pickup_at").toInstant(),
        (Double) rs.getObject("latitude"),
        (Double) rs.getObject("longitude"));
  }

  private static OrderDetails mapDetails(ResultSet rs) throws SQLException {
    return new OrderDetails(
        (UUID) rs.getObject("id"),
        rs.getString("order_number"),
        rs.getString("status"),
        (UUID) rs.getObject("rider_id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("pharmacy_name"),
        rs.getString("pharmacy_address"),
        rs.getObject("latitude") == null ? null : ((Number) rs.getObject("latitude")).doubleValue(),
        rs.getObject("longitude") == null
            ? null
            : ((Number) rs.getObject("longitude")).doubleValue(),
        rs.getString("pharmacy_phone"),
        (UUID) rs.getObject("zone_id"),
        rs.getString("zone_name"),
        rs.getString("customer_name"),
        rs.getString("customer_phone"),
        rs.getString("delivery_address"),
        rs.getObject("delivery_lat") == null
            ? null
            : ((Number) rs.getObject("delivery_lat")).doubleValue(),
        rs.getObject("delivery_lng") == null
            ? null
            : ((Number) rs.getObject("delivery_lng")).doubleValue(),
        rs.getInt("items_count"),
        rs.getString("payment_method"),
        rs.getLong("total_payable_paise"),
        rs.getTimestamp("estimated_delivery_at") == null
            ? null
            : rs.getTimestamp("estimated_delivery_at").toInstant(),
        rs.getTimestamp("sla_deadline") == null
            ? null
            : rs.getTimestamp("sla_deadline").toInstant(),
        rs.getString("delivery_otp_hash"));
  }
}
