package com.nammamedmate.prescription.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPharmacyRxQueueStore implements PharmacyRxQueueStore {

  private static final TypeReference<List<Map<String, Object>>> MEDS_TYPE =
      new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcPharmacyRxQueueStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(PharmacyRxQueueEntry e) {
    jdbc.update(
        """
        INSERT INTO pharmacy_rx_queue (
          id, rx_id, pharmacy_id, order_id, received_at, status, approved_medicines,
          approved_by, approved_at, rejected_reason, rejected_custom_message, rejected_by,
          rejected_at, dispensed_by, dispensed_at, notes, duplicate_warning,
          overdue_notified_at, created_at, updated_at, deleted_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?::jsonb,
          ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?, ?, ?, ?
        )
        """,
        e.id(),
        e.rxId(),
        e.pharmacyId(),
        e.orderId(),
        Timestamp.from(e.receivedAt()),
        e.status(),
        toJson(e.approvedMedicines()),
        e.approvedBy(),
        ts(e.approvedAt()),
        e.rejectedReason(),
        e.rejectedCustomMessage(),
        e.rejectedBy(),
        ts(e.rejectedAt()),
        e.dispensedBy(),
        ts(e.dispensedAt()),
        e.notes(),
        e.duplicateWarning(),
        ts(e.overdueNotifiedAt()),
        Timestamp.from(e.createdAt()),
        Timestamp.from(e.updatedAt()),
        ts(e.deletedAt()));
  }

  @Override
  public Optional<PharmacyRxQueueEntry> findByRxAndPharmacy(UUID rxId, UUID pharmacyId) {
    List<PharmacyRxQueueEntry> rows =
        jdbc.query(
            """
            SELECT * FROM pharmacy_rx_queue
            WHERE rx_id = ? AND pharmacy_id = ? AND deleted_at IS NULL
            """,
            this::mapRow,
            rxId,
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<PharmacyRxQueueEntry> findLatestByRxId(UUID rxId) {
    List<PharmacyRxQueueEntry> rows =
        jdbc.query(
            """
            SELECT * FROM pharmacy_rx_queue
            WHERE rx_id = ? AND deleted_at IS NULL
            ORDER BY created_at DESC
            LIMIT 1
            """,
            this::mapRow,
            rxId);
    return rows.stream().findFirst();
  }

  @Override
  public Page list(
      UUID pharmacyId,
      String status,
      String source,
      String search,
      int page,
      int limit,
      String sort) {
    StringBuilder where =
        new StringBuilder(
            """
            FROM pharmacy_rx_queue q
            JOIN prescription p ON p.id = q.rx_id AND p.deleted_at IS NULL
            WHERE q.pharmacy_id = ? AND q.deleted_at IS NULL
            """);
    List<Object> args = new ArrayList<>();
    args.add(pharmacyId);
    if (status != null) {
      where.append(" AND q.status = ?");
      args.add(status);
    }
    if ("DIGITAL".equals(source)) {
      where.append(" AND p.type = 'E_PRESCRIPTION'");
    } else if ("UPLOADED".equals(source)) {
      where.append(" AND p.type = 'UPLOADED'");
    }
    if (search != null && !search.isBlank()) {
      where.append(
          """
           AND (
            p.patient_name ILIKE ? OR p.doctor_name ILIKE ?
            OR CAST(q.rx_id AS TEXT) ILIKE ?
          )
          """);
      String like = "%" + search.trim() + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    Long total = jdbc.queryForObject("SELECT COUNT(*) " + where, Long.class, args.toArray());
    long count = total == null ? 0L : total;
    String orderBy =
        switch (sort == null ? "urgency" : sort.toLowerCase(Locale.ROOT)) {
          case "received_at" -> "q.received_at DESC";
          case "patient_name" -> "p.patient_name ASC NULLS LAST";
          default ->
              """
              CASE WHEN q.status = 'PENDING_REVIEW'
                AND q.received_at < (NOW() AT TIME ZONE 'UTC' - INTERVAL '2 hours')
                THEN 0 ELSE 1 END,
              q.received_at ASC
              """;
        };
    int offset = Math.max(0, (page - 1) * limit);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);
    List<PharmacyRxQueueEntry> items =
        jdbc.query(
            "SELECT q.* " + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
            this::mapRow,
            pageArgs.toArray());
    return new Page(items, count);
  }

  @Override
  public Kpis computeKpis(UUID pharmacyId, Instant now) {
    Instant dayStart =
        LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant slaCutoff = now.minus(PharmacyRxQueueEntry.SLA);
    Instant day30 = now.minusSeconds(30L * 24 * 3600);
    Instant day7 = now.minusSeconds(7L * 24 * 3600);

    Integer pending =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND status = 'PENDING_REVIEW'
            """,
            Integer.class,
            pharmacyId);
    Integer overdue =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND status = 'PENDING_REVIEW'
              AND received_at < ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(slaCutoff));
    Integer awaiting =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND status = 'APPROVED'
            """,
            Integer.class,
            pharmacyId);
    Integer dispensedToday =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND status = 'DISPENSED'
              AND dispensed_at >= ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(dayStart));

    long valuePaise = sumDispensedValuePaise(pharmacyId, dayStart);
    Integer avgTurnaround =
        jdbc.queryForObject(
            """
            SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (
              COALESCE(approved_at, rejected_at) - received_at
            )) / 60)::int, 0)
            FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND COALESCE(approved_at, rejected_at) IS NOT NULL
              AND received_at >= ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(day7));

    Integer total30 =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue q
            JOIN prescription p ON p.id = q.rx_id
            WHERE q.pharmacy_id = ? AND q.deleted_at IS NULL AND q.received_at >= ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(day30));
    Integer digital30 =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue q
            JOIN prescription p ON p.id = q.rx_id
            WHERE q.pharmacy_id = ? AND q.deleted_at IS NULL AND q.received_at >= ?
              AND p.type = 'E_PRESCRIPTION'
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(day30));

    Integer reviewed7 =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND COALESCE(approved_at, rejected_at) IS NOT NULL
              AND COALESCE(approved_at, rejected_at) >= ?
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(day7));
    Integer onTime7 =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL
              AND COALESCE(approved_at, rejected_at) IS NOT NULL
              AND COALESCE(approved_at, rejected_at) >= ?
              AND COALESCE(approved_at, rejected_at) <= received_at + INTERVAL '2 hours'
            """,
            Integer.class,
            pharmacyId,
            Timestamp.from(day7));

    int tot = total30 == null ? 0 : total30;
    int dig = digital30 == null ? 0 : digital30;
    double digitalShare = tot == 0 ? 0.0 : round1((dig * 100.0) / tot);
    int rev = reviewed7 == null ? 0 : reviewed7;
    int ot = onTime7 == null ? 0 : onTime7;
    double slaPct = rev == 0 ? 0.0 : round1((ot * 100.0) / rev);

    return new Kpis(
        pending == null ? 0 : pending,
        overdue == null ? 0 : overdue,
        awaiting == null ? 0 : awaiting,
        dispensedToday == null ? 0 : dispensedToday,
        valuePaise,
        avgTurnaround == null ? 0 : avgTurnaround,
        digitalShare,
        slaPct);
  }

  private long sumDispensedValuePaise(UUID pharmacyId, Instant dayStart) {
    List<String> jsons =
        jdbc.query(
            """
            SELECT approved_medicines::text FROM pharmacy_rx_queue
            WHERE pharmacy_id = ? AND deleted_at IS NULL AND status = 'DISPENSED'
              AND dispensed_at >= ? AND approved_medicines IS NOT NULL
            """,
            (rs, i) -> rs.getString(1),
            pharmacyId,
            Timestamp.from(dayStart));
    long total = 0L;
    for (String json : jsons) {
      List<ApprovedMedicine> meds = parseMeds(json);
      if (meds == null) {
        continue;
      }
      for (ApprovedMedicine m : meds) {
        total +=
            (m.price() == null ? BigDecimal.ZERO : m.price())
                .multiply(BigDecimal.valueOf(m.quantity()))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
      }
    }
    return total;
  }

  @Override
  public void markApproved(
      UUID id,
      List<ApprovedMedicine> medicines,
      UUID approvedBy,
      Instant approvedAt,
      String notes,
      boolean duplicateWarning,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_rx_queue
        SET status = 'APPROVED',
            approved_medicines = ?::jsonb,
            approved_by = ?,
            approved_at = ?,
            notes = ?,
            duplicate_warning = ?,
            updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        toJson(medicines),
        approvedBy,
        Timestamp.from(approvedAt),
        notes,
        duplicateWarning,
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public void markRejected(
      UUID id,
      String reason,
      String customMessage,
      UUID rejectedBy,
      Instant rejectedAt,
      Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_rx_queue
        SET status = 'REJECTED',
            rejected_reason = ?,
            rejected_custom_message = ?,
            rejected_by = ?,
            rejected_at = ?,
            updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        reason,
        customMessage,
        rejectedBy,
        Timestamp.from(rejectedAt),
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public void markDispensed(UUID id, UUID dispensedBy, Instant dispensedAt, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_rx_queue
        SET status = 'DISPENSED',
            dispensed_by = ?,
            dispensed_at = ?,
            updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        dispensedBy,
        Timestamp.from(dispensedAt),
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public void markOverdueNotified(UUID id, Instant notifiedAt, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE pharmacy_rx_queue
        SET overdue_notified_at = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        Timestamp.from(notifiedAt),
        Timestamp.from(updatedAt),
        id);
  }

  @Override
  public List<PharmacyRxQueueEntry> findPendingOverdueUnnotified(Instant deadline, int limit) {
    return jdbc.query(
        """
        SELECT * FROM pharmacy_rx_queue
        WHERE deleted_at IS NULL
          AND status = 'PENDING_REVIEW'
          AND received_at < ?
          AND overdue_notified_at IS NULL
        ORDER BY received_at ASC
        LIMIT ?
        """,
        this::mapRow,
        Timestamp.from(deadline),
        limit);
  }

  @Override
  public boolean hasDuplicateDispense(
      UUID customerId, String medicineName, Instant since, UUID excludeRxId) {
    Boolean found =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1
              FROM pharmacy_rx_queue q
              JOIN prescription p ON p.id = q.rx_id AND p.deleted_at IS NULL
              WHERE q.deleted_at IS NULL
                AND q.status = 'DISPENSED'
                AND q.dispensed_at >= ?
                AND p.customer_id = ?
                AND q.rx_id <> ?
                AND (
                  EXISTS (
                    SELECT 1
                    FROM jsonb_array_elements(COALESCE(q.approved_medicines, '[]'::jsonb)) m
                    WHERE LOWER(m->>'name') = LOWER(?)
                  )
                  OR EXISTS (
                    SELECT 1
                    FROM jsonb_array_elements(COALESCE(p.medicines_extracted, '[]'::jsonb)) m
                    WHERE LOWER(m->>'name') = LOWER(?)
                  )
                )
            )
            """,
            Boolean.class,
            Timestamp.from(since),
            customerId,
            excludeRxId,
            medicineName,
            medicineName);
    return Boolean.TRUE.equals(found);
  }

  private PharmacyRxQueueEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new PharmacyRxQueueEntry(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rx_id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("order_id"),
        rs.getTimestamp("received_at").toInstant(),
        rs.getString("status"),
        parseMeds(rs.getString("approved_medicines")),
        (UUID) rs.getObject("approved_by"),
        instant(rs.getTimestamp("approved_at")),
        rs.getString("rejected_reason"),
        rs.getString("rejected_custom_message"),
        (UUID) rs.getObject("rejected_by"),
        instant(rs.getTimestamp("rejected_at")),
        (UUID) rs.getObject("dispensed_by"),
        instant(rs.getTimestamp("dispensed_at")),
        rs.getString("notes"),
        rs.getBoolean("duplicate_warning"),
        instant(rs.getTimestamp("overdue_notified_at")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instant(rs.getTimestamp("deleted_at")));
  }

  private List<ApprovedMedicine> parseMeds(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      List<Map<String, Object>> raw = objectMapper.readValue(json, MEDS_TYPE);
      List<ApprovedMedicine> out = new ArrayList<>();
      for (Map<String, Object> row : raw) {
        String name = row.get("name") == null ? "" : row.get("name").toString();
        int qty = 1;
        Object q = row.get("quantity");
        if (q instanceof Number n) {
          qty = Math.max(1, n.intValue());
        }
        BigDecimal price = null;
        Object p = row.get("price");
        if (p instanceof Number n) {
          price = BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        } else if (p != null) {
          price = new BigDecimal(p.toString()).setScale(2, RoundingMode.HALF_UP);
        }
        String schedule = row.get("schedule") == null ? null : row.get("schedule").toString();
        out.add(new ApprovedMedicine(name, qty, price, schedule));
      }
      return out;
    } catch (JsonProcessingException | NumberFormatException e) {
      return List.of();
    }
  }

  private String toJson(List<ApprovedMedicine> medicines) {
    if (medicines == null) {
      return null;
    }
    List<Map<String, Object>> raw = new ArrayList<>();
    for (ApprovedMedicine m : medicines) {
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("name", m.name() == null ? "" : m.name());
      row.put("quantity", m.quantity());
      row.put("price", m.price() == null ? BigDecimal.ZERO : m.price());
      if (m.schedule() != null && !m.schedule().isBlank()) {
        row.put("schedule", m.schedule());
      }
      raw.add(row);
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

  private static double round1(double v) {
    return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
  }
}
