package com.nammamedmate.api.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.PrescriptionPort.MedicineLine;
import com.nammamedmate.order.application.port.out.PrescriptionPort.PrescriptionDetail;
import com.nammamedmate.order.application.port.out.PrescriptionPort.PrescriptionRef;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionInUsePort;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition-root bridges: order {@link PrescriptionPort} ← prescription table; prescription
 * {@link OrderLinkPort} / {@link PrescriptionInUsePort} ← carts / orders.
 */
@Configuration
public class PrescriptionOrderBridgeConfig {

  @Bean
  @Primary
  PrescriptionPort jdbcPrescriptionPort(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
    return new JdbcPrescriptionPortBridge(jdbc, objectMapper, clock);
  }

  @Bean
  @Primary
  OrderLinkPort jdbcOrderLinkPort(JdbcTemplate jdbc, Clock clock) {
    return new JdbcOrderLinkBridge(jdbc, clock);
  }

  @Bean
  @Primary
  PrescriptionInUsePort jdbcPrescriptionInUsePort(JdbcTemplate jdbc) {
    return new JdbcPrescriptionInUseBridge(jdbc);
  }

  static final class JdbcPrescriptionPortBridge implements PrescriptionPort {

    private static final TypeReference<List<Map<String, Object>>> MEDS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    JdbcPrescriptionPortBridge(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
      this.jdbc = jdbc;
      this.objectMapper = objectMapper;
      this.clock = clock;
    }

    @Override
    public Optional<PrescriptionRef> findVerified(UUID prescriptionId, UUID customerId) {
      if (prescriptionId == null || customerId == null) {
        return Optional.empty();
      }
      List<String> rows =
          jdbc.query(
              """
              SELECT status FROM prescription
              WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
              """,
              (rs, i) -> rs.getString(1),
              prescriptionId,
              customerId);
      return rows.stream().findFirst().map(status -> new PrescriptionRef(prescriptionId, status));
    }

    @Override
    public Optional<PrescriptionDetail> findForBroadcast(UUID prescriptionId, UUID customerId) {
      if (prescriptionId == null || customerId == null) {
        return Optional.empty();
      }
      List<PrescriptionDetail> rows =
          jdbc.query(
              """
              SELECT status, expires_at, medicines_extracted
              FROM prescription
              WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
              """,
              (rs, i) -> {
                String status = rs.getString("status");
                Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
                boolean expired =
                    "EXPIRED".equalsIgnoreCase(status) || !expiresAt.isAfter(clock.instant());
                List<MedicineLine> meds = parseMeds(rs.getString("medicines_extracted"));
                return new PrescriptionDetail(prescriptionId, status, expired, meds);
              },
              prescriptionId,
              customerId);
      return rows.stream().findFirst();
    }

    private List<MedicineLine> parseMeds(String json) {
      if (json == null || json.isBlank()) {
        return List.of();
      }
      try {
        List<Map<String, Object>> raw = objectMapper.readValue(json, MEDS);
        List<MedicineLine> out = new ArrayList<>();
        for (Map<String, Object> row : raw) {
          String name = row.get("name") == null ? "" : row.get("name").toString();
          int qty = parseQty(row.get("quantity"));
          out.add(new MedicineLine(name, qty));
        }
        return out;
      } catch (JsonProcessingException e) {
        return List.of();
      }
    }

    private static int parseQty(Object raw) {
      if (raw == null) {
        return 1;
      }
      if (raw instanceof Number n) {
        return Math.max(1, n.intValue());
      }
      String s = raw.toString().trim();
      if (s.isEmpty()) {
        return 1;
      }
      StringBuilder digits = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (Character.isDigit(c)) {
          digits.append(c);
        } else if (digits.length() > 0) {
          break;
        }
      }
      if (digits.isEmpty()) {
        return 1;
      }
      try {
        return Math.max(1, Integer.parseInt(digits.toString()));
      } catch (NumberFormatException e) {
        return 1;
      }
    }
  }

  static final class JdbcOrderLinkBridge implements OrderLinkPort {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcOrderLinkBridge(JdbcTemplate jdbc, Clock clock) {
      this.jdbc = jdbc;
      this.clock = clock;
    }

    @Override
    public void attachToCart(UUID customerId, UUID cartId, UUID prescriptionId) {
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              """
              SELECT id, prescription_id, status
              FROM carts
              WHERE id = ? AND customer_id = ? AND status = 'ACTIVE'
              """,
              cartId,
              customerId);
      if (rows.isEmpty()) {
        throw new AppException("CART_NOT_FOUND", "Cart not found or not ACTIVE", 404);
      }
      Object existing = rows.get(0).get("prescription_id");
      if (existing != null) {
        UUID existingId = existing instanceof UUID u ? u : UUID.fromString(existing.toString());
        if (!existingId.equals(prescriptionId)) {
          throw new AppException(
              "CART_PRESCRIPTION_MISMATCH",
              "Cart already has a different prescription attached",
              422);
        }
        return;
      }
      int updated =
          jdbc.update(
              """
              UPDATE carts
              SET prescription_id = ?, updated_at = ?
              WHERE id = ? AND customer_id = ? AND status = 'ACTIVE'
              """,
              prescriptionId,
              Timestamp.from(clock.instant()),
              cartId,
              customerId);
      if (updated == 0) {
        throw new AppException("CART_NOT_FOUND", "Cart not found or not ACTIVE", 404);
      }
    }
  }

  static final class JdbcPrescriptionInUseBridge implements PrescriptionInUsePort {

    private final JdbcTemplate jdbc;

    JdbcPrescriptionInUseBridge(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public boolean isInUse(UUID prescriptionId) {
      Boolean viaOrders =
          jdbc.queryForObject(
              """
              SELECT EXISTS(
                SELECT 1 FROM orders
                WHERE prescription_id = ?
                  AND status <> 'CANCELLED'
                  AND deleted_at IS NULL
              )
              """,
              Boolean.class,
              prescriptionId);
      if (Boolean.TRUE.equals(viaOrders)) {
        return true;
      }
      Boolean viaAssociated =
          jdbc.queryForObject(
              """
              SELECT EXISTS(
                SELECT 1
                FROM prescription p
                JOIN orders o ON o.id = p.associated_order_id
                WHERE p.id = ?
                  AND p.deleted_at IS NULL
                  AND o.deleted_at IS NULL
                  AND o.status <> 'CANCELLED'
              )
              """,
              Boolean.class,
              prescriptionId);
      return Boolean.TRUE.equals(viaAssociated);
    }
  }
}
