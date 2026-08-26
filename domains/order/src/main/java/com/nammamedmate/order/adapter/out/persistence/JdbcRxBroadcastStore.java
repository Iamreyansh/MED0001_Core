package com.nammamedmate.order.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.RxBroadcastStore;
import com.nammamedmate.order.domain.QuotedMedicine;
import com.nammamedmate.order.domain.RxBroadcast;
import com.nammamedmate.order.domain.RxBroadcast.RequestedMedicine;
import com.nammamedmate.order.domain.RxBroadcastPharmacy;
import com.nammamedmate.order.domain.RxBroadcastStatus;
import com.nammamedmate.order.domain.RxPharmacySlotStatus;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcRxBroadcastStore implements RxBroadcastStore {

  private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcRxBroadcastStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(RxBroadcast broadcast, List<RxBroadcastPharmacy> pharmacies) {
    jdbc.update(
        """
        INSERT INTO rx_broadcasts (
          id, customer_id, prescription_id, delivery_address_id, patient_name, notes,
          medicines_requested, status, pharmacies_notified, broadcast_at, expires_at,
          selected_pharmacy_id, resulting_cart_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
        """,
        broadcast.id(),
        broadcast.customerId(),
        broadcast.prescriptionId(),
        broadcast.deliveryAddressId(),
        broadcast.patientName(),
        broadcast.notes(),
        toRequestedJson(broadcast.medicinesRequested()),
        broadcast.status().name(),
        broadcast.pharmaciesNotified(),
        Timestamp.from(broadcast.broadcastAt()),
        Timestamp.from(broadcast.expiresAt()),
        broadcast.selectedPharmacyId(),
        broadcast.resultingCartId(),
        Timestamp.from(broadcast.createdAt()));
    for (RxBroadcastPharmacy p : pharmacies) {
      jdbc.update(
          """
          INSERT INTO rx_broadcast_pharmacies (
            id, broadcast_id, pharmacy_id, distance_km, status, medicines_available,
            delivery_eta_minutes, total_payable_paise, received_at, response_deadline,
            quoted_at, quote_expires_at, tags
          ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
          """,
          p.id(),
          p.broadcastId(),
          p.pharmacyId(),
          BigDecimal.valueOf(p.distanceKm()),
          p.status().name(),
          toQuotedJson(p.medicinesAvailable()),
          p.deliveryEtaMinutes(),
          p.totalPayablePaise(),
          Timestamp.from(p.receivedAt()),
          Timestamp.from(p.responseDeadline()),
          p.quotedAt() == null ? null : Timestamp.from(p.quotedAt()),
          p.quoteExpiresAt() == null ? null : Timestamp.from(p.quoteExpiresAt()),
          toTagsArray(p.tags()));
    }
  }

  @Override
  public Optional<RxBroadcast> findById(UUID broadcastId) {
    List<RxBroadcast> rows =
        jdbc.query("SELECT * FROM rx_broadcasts WHERE id = ?", this::mapBroadcast, broadcastId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public Optional<RxBroadcast> findByIdForCustomer(UUID broadcastId, UUID customerId) {
    List<RxBroadcast> rows =
        jdbc.query(
            "SELECT * FROM rx_broadcasts WHERE id = ? AND customer_id = ?",
            this::mapBroadcast,
            broadcastId,
            customerId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public List<RxBroadcastPharmacy> listPharmacies(UUID broadcastId) {
    return jdbc.query(
        """
        SELECT * FROM rx_broadcast_pharmacies
        WHERE broadcast_id = ?
        ORDER BY distance_km ASC
        """,
        this::mapPharmacy,
        broadcastId);
  }

  @Override
  public Optional<RxBroadcastPharmacy> findPharmacySlot(UUID broadcastId, UUID pharmacyId) {
    List<RxBroadcastPharmacy> rows =
        jdbc.query(
            """
            SELECT * FROM rx_broadcast_pharmacies
            WHERE broadcast_id = ? AND pharmacy_id = ?
            LIMIT 1
            """,
            this::mapPharmacy,
            broadcastId,
            pharmacyId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  @Override
  public void updatePharmacySlot(RxBroadcastPharmacy slot) {
    int n =
        jdbc.update(
            """
            UPDATE rx_broadcast_pharmacies SET
              status = ?,
              medicines_available = ?::jsonb,
              delivery_eta_minutes = ?,
              total_payable_paise = ?,
              quoted_at = ?,
              quote_expires_at = ?,
              tags = ?
            WHERE id = ?
            """,
            slot.status().name(),
            toQuotedJson(slot.medicinesAvailable()),
            slot.deliveryEtaMinutes(),
            slot.totalPayablePaise(),
            slot.quotedAt() == null ? null : Timestamp.from(slot.quotedAt()),
            slot.quoteExpiresAt() == null ? null : Timestamp.from(slot.quoteExpiresAt()),
            toTagsArray(slot.tags()),
            slot.id());
    if (n == 0) {
      throw new IllegalStateException("rx pharmacy slot not found: " + slot.id());
    }
  }

  @Override
  public void markSelected(UUID broadcastId, UUID pharmacyId, UUID cartId) {
    jdbc.update(
        """
        UPDATE rx_broadcasts SET
          status = 'SELECTED',
          selected_pharmacy_id = ?,
          resulting_cart_id = ?
        WHERE id = ?
        """,
        pharmacyId,
        cartId,
        broadcastId);
  }

  @Override
  public int expirePharmacySlots(Instant now) {
    return jdbc.update(
        """
        UPDATE rx_broadcast_pharmacies
        SET status = 'EXPIRED'
        WHERE status IN ('NOTIFIED', 'REVIEWING')
          AND response_deadline < ?
        """,
        Timestamp.from(now));
  }

  @Override
  public List<RxBroadcast> expireBroadcasts(Instant now) {
    List<RxBroadcast> expired =
        jdbc.query(
            """
            SELECT * FROM rx_broadcasts
            WHERE status = 'ACTIVE' AND expires_at < ?
            """,
            this::mapBroadcast,
            Timestamp.from(now));
    if (expired.isEmpty()) {
      return List.of();
    }
    jdbc.update(
        """
        UPDATE rx_broadcasts
        SET status = 'EXPIRED'
        WHERE status = 'ACTIVE' AND expires_at < ?
        """,
        Timestamp.from(now));
    return expired.stream()
        .map(
            b ->
                new RxBroadcast(
                    b.id(),
                    b.customerId(),
                    b.prescriptionId(),
                    b.deliveryAddressId(),
                    b.patientName(),
                    b.notes(),
                    b.medicinesRequested(),
                    RxBroadcastStatus.EXPIRED,
                    b.pharmaciesNotified(),
                    b.broadcastAt(),
                    b.expiresAt(),
                    b.selectedPharmacyId(),
                    b.resultingCartId(),
                    b.createdAt()))
        .toList();
  }

  @Override
  public List<RxBroadcastPharmacy> listPendingForPharmacy(UUID pharmacyId) {
    return jdbc.query(
        """
        SELECT bp.* FROM rx_broadcast_pharmacies bp
        INNER JOIN rx_broadcasts b ON b.id = bp.broadcast_id
        WHERE bp.pharmacy_id = ?
          AND b.status = 'ACTIVE'
          AND bp.status IN ('NOTIFIED', 'REVIEWING')
        ORDER BY bp.received_at DESC
        """,
        this::mapPharmacy,
        pharmacyId);
  }

  @Override
  public int countQuoted(UUID broadcastId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM rx_broadcast_pharmacies
            WHERE broadcast_id = ? AND status = 'QUOTED'
            """,
            Integer.class,
            broadcastId);
    return n == null ? 0 : n;
  }

  @Override
  public void updateBroadcastStatus(UUID broadcastId, RxBroadcastStatus status) {
    jdbc.update("UPDATE rx_broadcasts SET status = ? WHERE id = ?", status.name(), broadcastId);
  }

  @Override
  public void updatePharmacyStatus(UUID slotId, RxPharmacySlotStatus status) {
    jdbc.update(
        "UPDATE rx_broadcast_pharmacies SET status = ? WHERE id = ?", status.name(), slotId);
  }

  private RxBroadcast mapBroadcast(ResultSet rs, int rowNum) throws SQLException {
    return new RxBroadcast(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("prescription_id"),
        (UUID) rs.getObject("delivery_address_id"),
        rs.getString("patient_name"),
        rs.getString("notes"),
        fromRequestedJson(rs.getString("medicines_requested")),
        RxBroadcastStatus.valueOf(rs.getString("status")),
        rs.getInt("pharmacies_notified"),
        rs.getTimestamp("broadcast_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant(),
        (UUID) rs.getObject("selected_pharmacy_id"),
        (UUID) rs.getObject("resulting_cart_id"),
        rs.getTimestamp("created_at").toInstant());
  }

  private RxBroadcastPharmacy mapPharmacy(ResultSet rs, int rowNum) throws SQLException {
    return new RxBroadcastPharmacy(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("broadcast_id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getBigDecimal("distance_km").doubleValue(),
        RxPharmacySlotStatus.valueOf(rs.getString("status")),
        fromQuotedJson(rs.getString("medicines_available")),
        (Integer) rs.getObject("delivery_eta_minutes"),
        (Long) rs.getObject("total_payable_paise"),
        rs.getTimestamp("received_at").toInstant(),
        rs.getTimestamp("response_deadline").toInstant(),
        rs.getTimestamp("quoted_at") == null ? null : rs.getTimestamp("quoted_at").toInstant(),
        rs.getTimestamp("quote_expires_at") == null
            ? null
            : rs.getTimestamp("quote_expires_at").toInstant(),
        fromTags(rs.getArray("tags")));
  }

  private String toRequestedJson(List<RequestedMedicine> meds) {
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (RequestedMedicine m : meds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", m.name());
        row.put("quantity", m.quantity());
        rows.add(row);
      }
      return objectMapper.writeValueAsString(rows);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("medicines_requested serialize failed", e);
    }
  }

  private List<RequestedMedicine> fromRequestedJson(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(json, MAP_LIST);
      List<RequestedMedicine> out = new ArrayList<>();
      for (Map<String, Object> m : rows) {
        out.add(
            new RequestedMedicine(
                String.valueOf(m.get("name")), ((Number) m.get("quantity")).intValue()));
      }
      return out;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("medicines_requested deserialize failed", e);
    }
  }

  private String toQuotedJson(List<QuotedMedicine> meds) {
    if (meds == null) {
      return null;
    }
    try {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (QuotedMedicine m : meds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", m.name());
        row.put("quantity", m.quantity());
        row.put("price_paise", m.pricePaise());
        if (m.productId() != null) {
          row.put("product_id", m.productId().toString());
        }
        rows.add(row);
      }
      return objectMapper.writeValueAsString(rows);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("medicines_available serialize failed", e);
    }
  }

  private List<QuotedMedicine> fromQuotedJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      List<Map<String, Object>> rows = objectMapper.readValue(json, MAP_LIST);
      List<QuotedMedicine> out = new ArrayList<>();
      for (Map<String, Object> m : rows) {
        UUID productId = null;
        Object rawProduct = m.get("product_id");
        if (rawProduct != null && !String.valueOf(rawProduct).isBlank()) {
          try {
            productId = UUID.fromString(String.valueOf(rawProduct));
          } catch (IllegalArgumentException ignored) {
            productId = null;
          }
        }
        out.add(
            new QuotedMedicine(
                String.valueOf(m.get("name")),
                ((Number) m.get("quantity")).intValue(),
                ((Number) m.get("price_paise")).longValue(),
                productId));
      }
      return out;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("medicines_available deserialize failed", e);
    }
  }

  private Object toTagsArray(List<String> tags) {
    // RxBroadcastPharmacy normalises null → List.of()
    if (tags.isEmpty()) {
      return null;
    }
    return tags.toArray(String[]::new);
  }

  private List<String> fromTags(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] s) {
      return Arrays.asList(s);
    }
    if (raw instanceof Object[] objs) {
      List<String> out = new ArrayList<>();
      for (Object o : objs) {
        if (o != null) {
          out.add(String.valueOf(o));
        }
      }
      return out;
    }
    return List.of();
  }
}
