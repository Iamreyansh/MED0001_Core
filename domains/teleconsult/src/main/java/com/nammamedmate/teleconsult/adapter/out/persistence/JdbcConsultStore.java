package com.nammamedmate.teleconsult.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.teleconsult.application.port.out.ConsultStore;
import com.nammamedmate.teleconsult.domain.Consult;
import com.nammamedmate.teleconsult.domain.Consult.MedicineNeed;
import com.nammamedmate.teleconsult.domain.ConsultStatusEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcConsultStore implements ConsultStore {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> MED_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcConsultStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(Consult c) {
    jdbc.update(
        """
        INSERT INTO consults (
          id, customer_id, doctor_id, patient_name, patient_phone, slot_type, scheduled_at,
          symptoms, medicines_needing_rx, cart_id, is_cart_mode, reason, status,
          call_started_at, call_ended_at, duration_minutes, e_prescription_id, is_advice_only,
          clinical_notes, rating, feedback_text, rated_at, auto_cancelled_reason,
          created_at, updated_at, deleted_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?,
          ?::jsonb, ?::jsonb, ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?, ?, ?
        )
        """,
        c.id(),
        c.customerId(),
        c.doctorId(),
        c.patientName(),
        c.patientPhone(),
        c.slotType(),
        ts(c.scheduledAt()),
        toJson(c.symptoms()),
        toJsonMeds(c.medicinesNeedingRx()),
        c.cartId(),
        c.cartMode(),
        c.reason(),
        c.status(),
        ts(c.callStartedAt()),
        ts(c.callEndedAt()),
        c.durationMinutes(),
        c.ePrescriptionId(),
        c.adviceOnly(),
        c.clinicalNotes(),
        c.rating(),
        c.feedbackText(),
        ts(c.ratedAt()),
        c.autoCancelledReason(),
        Timestamp.from(c.createdAt()),
        Timestamp.from(c.updatedAt()),
        ts(c.deletedAt()));
  }

  @Override
  public void update(Consult c) {
    jdbc.update(
        """
        UPDATE consults SET
          doctor_id = ?, patient_name = ?, patient_phone = ?, slot_type = ?, scheduled_at = ?,
          symptoms = ?::jsonb, medicines_needing_rx = ?::jsonb, cart_id = ?, is_cart_mode = ?,
          reason = ?, status = ?, call_started_at = ?, call_ended_at = ?, duration_minutes = ?,
          e_prescription_id = ?, is_advice_only = ?, clinical_notes = ?, rating = ?,
          feedback_text = ?, rated_at = ?, auto_cancelled_reason = ?,
          updated_at = ?, deleted_at = ?
        WHERE id = ?
        """,
        c.doctorId(),
        c.patientName(),
        c.patientPhone(),
        c.slotType(),
        ts(c.scheduledAt()),
        toJson(c.symptoms()),
        toJsonMeds(c.medicinesNeedingRx()),
        c.cartId(),
        c.cartMode(),
        c.reason(),
        c.status(),
        ts(c.callStartedAt()),
        ts(c.callEndedAt()),
        c.durationMinutes(),
        c.ePrescriptionId(),
        c.adviceOnly(),
        c.clinicalNotes(),
        c.rating(),
        c.feedbackText(),
        ts(c.ratedAt()),
        c.autoCancelledReason(),
        Timestamp.from(c.updatedAt()),
        ts(c.deletedAt()),
        c.id());
  }

  @Override
  public void insertStatusEvent(ConsultStatusEvent event) {
    jdbc.update(
        """
        INSERT INTO consult_status_events (
          id, consult_id, from_status, to_status, actor_id, notes, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        event.id(),
        event.consultId(),
        event.fromStatus(),
        event.toStatus(),
        event.actorId(),
        event.notes(),
        Timestamp.from(event.createdAt()));
  }

  @Override
  public Optional<Consult> findById(UUID id) {
    List<Consult> rows =
        jdbc.query("SELECT * FROM consults WHERE id = ? AND deleted_at IS NULL", this::mapRow, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Consult> findByIdForCustomer(UUID id, UUID customerId) {
    List<Consult> rows =
        jdbc.query(
            """
            SELECT * FROM consults
            WHERE id = ? AND customer_id = ? AND deleted_at IS NULL
            """,
            this::mapRow,
            id,
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public long countActiveByCustomer(UUID customerId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM consults
            WHERE customer_id = ?
              AND deleted_at IS NULL
              AND status NOT IN ('COMPLETED', 'CANCELLED')
            """,
            Long.class,
            customerId);
    return n == null ? 0L : n;
  }

  @Override
  public boolean hasActiveCartModeConsult(UUID cartId) {
    if (cartId == null) {
      return false;
    }
    Boolean exists =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1 FROM consults
              WHERE cart_id = ?
                AND deleted_at IS NULL
                AND is_cart_mode = TRUE
                AND status NOT IN ('COMPLETED', 'CANCELLED')
            )
            """,
            Boolean.class,
            cartId);
    return Boolean.TRUE.equals(exists);
  }

  @Override
  public Page list(ListFilter filter) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE c.deleted_at IS NULL AND c.customer_id = ? ");
    args.add(filter.customerId());
    if (filter.status() != null
        && !filter.status().isBlank()
        && !"ALL".equalsIgnoreCase(filter.status())) {
      where.append(" AND c.status = ? ");
      args.add(filter.status().trim().toUpperCase());
    }
    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM consults c" + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;
    int page = Math.max(filter.page(), 1);
    int limit = Math.min(Math.max(filter.limit(), 1), 100);
    int offset = (page - 1) * limit;
    args.add(limit);
    args.add(offset);
    List<ListItem> items =
        jdbc.query(
            """
            SELECT c.id, c.created_at, c.status, c.e_prescription_id, c.cart_id,
                   c.is_cart_mode, c.rating, d.name AS doctor_name
            FROM consults c
            LEFT JOIN teleconsult_doctors d ON d.id = c.doctor_id
            """
                + where
                + """
            ORDER BY c.created_at DESC
            LIMIT ? OFFSET ?
            """,
            (rs, i) ->
                new ListItem(
                    (UUID) rs.getObject("id"),
                    instant(rs.getTimestamp("created_at")),
                    rs.getString("doctor_name"),
                    rs.getString("status"),
                    (UUID) rs.getObject("e_prescription_id"),
                    (UUID) rs.getObject("cart_id"),
                    rs.getBoolean("is_cart_mode"),
                    rs.getObject("rating") == null ? null : rs.getInt("rating")),
            args.toArray());
    return new Page(items, totalCount);
  }

  @Override
  public int countQueuedNowAheadOrEqual(Instant createdAt) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM consults
            WHERE deleted_at IS NULL
              AND slot_type = 'NOW'
              AND status = 'REQUESTED'
              AND doctor_id IS NULL
              AND created_at <= ?
            """,
            Long.class,
            Timestamp.from(createdAt));
    return n == null ? 0 : n.intValue();
  }

  @Override
  public Optional<Integer> rollingAvgCallDurationMinutes() {
    Integer avg =
        jdbc.query(
            """
                SELECT ROUND(
                  AVG(EXTRACT(EPOCH FROM (call_ended_at - call_started_at)) / 60.0)
                )::int AS avg_min
                FROM consults
                WHERE deleted_at IS NULL
                  AND status = 'COMPLETED'
                  AND call_started_at IS NOT NULL
                  AND call_ended_at IS NOT NULL
                  AND call_ended_at >= NOW() - INTERVAL '7 days'
                """,
            rs -> rs.next() ? (Integer) rs.getObject("avg_min") : null);
    return Optional.ofNullable(avg).filter(v -> v > 0);
  }

  @Override
  public List<Consult> findDueForAutoCancel(Instant deadlineBefore) {
    return jdbc.query(
        """
        SELECT * FROM consults
        WHERE deleted_at IS NULL
          AND slot_type = 'SCHEDULED'
          AND status IN ('REQUESTED', 'DOCTOR_REVIEWING')
          AND scheduled_at IS NOT NULL
          AND scheduled_at + INTERVAL '30 minutes' < ?
        """,
        this::mapRow,
        Timestamp.from(deadlineBefore));
  }

  @Override
  public List<Consult> findDueForScheduledAssign(Instant now) {
    return jdbc.query(
        """
        SELECT * FROM consults
        WHERE deleted_at IS NULL
          AND slot_type = 'SCHEDULED'
          AND status = 'REQUESTED'
          AND doctor_id IS NULL
          AND scheduled_at IS NOT NULL
          AND scheduled_at <= ?
        ORDER BY scheduled_at ASC
        """,
        this::mapRow,
        Timestamp.from(now));
  }

  @Override
  public List<Consult> findQueuedNowUnassigned() {
    return jdbc.query(
        """
        SELECT * FROM consults
        WHERE deleted_at IS NULL
          AND slot_type = 'NOW'
          AND status = 'REQUESTED'
          AND doctor_id IS NULL
        ORDER BY created_at ASC
        """,
        this::mapRow);
  }

  @Override
  public List<QueueItem> listActiveQueue() {
    return jdbc.query(
        """
        SELECT c.id, c.status, c.patient_name, c.patient_phone, c.medicines_needing_rx,
               c.call_started_at, c.created_at, c.is_cart_mode, d.name AS doctor_name
        FROM consults c
        LEFT JOIN teleconsult_doctors d ON d.id = c.doctor_id
        WHERE c.deleted_at IS NULL
          AND c.status IN ('REQUESTED', 'DOCTOR_REVIEWING', 'CALLING', 'IN_CALL')
        ORDER BY
          CASE c.status
            WHEN 'IN_CALL' THEN 1
            WHEN 'CALLING' THEN 2
            WHEN 'DOCTOR_REVIEWING' THEN 3
            WHEN 'REQUESTED' THEN 4
            ELSE 5
          END,
          c.created_at ASC
        """,
        (rs, i) ->
            new QueueItem(
                (UUID) rs.getObject("id"),
                rs.getString("status"),
                rs.getString("patient_name"),
                rs.getString("patient_phone"),
                rs.getString("doctor_name"),
                medicineNames(rs.getString("medicines_needing_rx")),
                instant(rs.getTimestamp("call_started_at")),
                instant(rs.getTimestamp("created_at")),
                rs.getBoolean("is_cart_mode")));
  }

  @Override
  public Map<String, Long> countActiveByStatus() {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put(Consult.STATUS_REQUESTED, 0L);
    counts.put(Consult.STATUS_DOCTOR_REVIEWING, 0L);
    counts.put(Consult.STATUS_CALLING, 0L);
    counts.put(Consult.STATUS_IN_CALL, 0L);
    jdbc.query(
        """
        SELECT status, COUNT(*) AS cnt
        FROM consults
        WHERE deleted_at IS NULL
          AND status IN ('REQUESTED', 'DOCTOR_REVIEWING', 'CALLING', 'IN_CALL')
        GROUP BY status
        """,
        rs -> {
          while (rs.next()) {
            counts.put(rs.getString("status"), rs.getLong("cnt"));
          }
          return null;
        });
    return counts;
  }

  @Override
  public AdminPage adminList(AdminListFilter filter) {
    List<Object> args = new ArrayList<>();
    StringBuilder where =
        new StringBuilder(
            """
            WHERE c.deleted_at IS NULL
              AND c.created_at >= ?
              AND c.created_at < ?
            """);
    args.add(Timestamp.from(filter.rangeStart()));
    args.add(Timestamp.from(filter.rangeEnd()));
    if (filter.doctorId() != null) {
      where.append(" AND c.doctor_id = ? ");
      args.add(filter.doctorId());
    }
    if (filter.status() != null
        && !filter.status().isBlank()
        && !"ALL".equalsIgnoreCase(filter.status())) {
      where.append(" AND c.status = ? ");
      args.add(filter.status().trim().toUpperCase());
    }
    if (filter.cartMode() != null) {
      where.append(" AND c.is_cart_mode = ? ");
      args.add(filter.cartMode());
    }
    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM consults c " + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;
    int page = Math.max(filter.page(), 1);
    int limit = Math.min(Math.max(filter.limit(), 1), 100);
    int offset = (page - 1) * limit;
    args.add(limit);
    args.add(offset);
    List<AdminListItem> items =
        jdbc.query(
            """
            SELECT c.id, c.patient_name, c.status, c.duration_minutes, c.e_prescription_id,
                   c.is_cart_mode, c.rating, c.created_at, c.call_ended_at,
                   d.name AS doctor_name
            FROM consults c
            LEFT JOIN teleconsult_doctors d ON d.id = c.doctor_id
            """
                + where
                + """
            ORDER BY c.created_at DESC
            LIMIT ? OFFSET ?
            """,
            (rs, i) ->
                new AdminListItem(
                    (UUID) rs.getObject("id"),
                    rs.getString("patient_name"),
                    rs.getString("doctor_name"),
                    rs.getString("status"),
                    rs.getBigDecimal("duration_minutes"),
                    rs.getObject("e_prescription_id") != null,
                    rs.getBoolean("is_cart_mode"),
                    rs.getObject("rating") == null ? null : rs.getInt("rating"),
                    instant(rs.getTimestamp("created_at")),
                    Consult.STATUS_COMPLETED.equals(rs.getString("status"))
                        ? instant(rs.getTimestamp("call_ended_at"))
                        : null),
            args.toArray());
    return new AdminPage(items, totalCount);
  }

  @Override
  public AdminDayStats adminDayStats(Instant rangeStart, Instant rangeEnd) {
    return jdbc.query(
        """
        SELECT
          COUNT(*) AS total_today,
          COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
          COUNT(*) FILTER (
            WHERE status IN ('REQUESTED', 'DOCTOR_REVIEWING', 'CALLING', 'IN_CALL')
          ) AS in_progress,
          COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
          ROUND(AVG(duration_minutes) FILTER (
            WHERE status = 'COMPLETED' AND duration_minutes IS NOT NULL
          ), 1) AS avg_duration,
          ROUND(AVG(rating) FILTER (WHERE rating IS NOT NULL), 1) AS avg_rating,
          COUNT(*) FILTER (WHERE status = 'COMPLETED' AND rating IS NULL) AS pending_rating
        FROM consults
        WHERE deleted_at IS NULL
          AND created_at >= ?
          AND created_at < ?
        """,
        rs -> {
          if (!rs.next()) {
            return new AdminDayStats(0, 0, 0, 0, null, null, 0);
          }
          return new AdminDayStats(
              rs.getLong("total_today"),
              rs.getLong("completed"),
              rs.getLong("in_progress"),
              rs.getLong("cancelled"),
              rs.getBigDecimal("avg_duration"),
              rs.getBigDecimal("avg_rating"),
              rs.getLong("pending_rating"));
        },
        Timestamp.from(rangeStart),
        Timestamp.from(rangeEnd));
  }

  @Override
  public long countRatingsByDoctor(UUID doctorId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM consults
            WHERE deleted_at IS NULL
              AND doctor_id = ?
              AND rating IS NOT NULL
            """,
            Long.class,
            doctorId);
    return n == null ? 0L : n;
  }

  @Override
  public DoctorPeriodStats doctorPeriodStats(UUID doctorId, Instant rangeStart, Instant rangeEnd) {
    DoctorPeriodStats base =
        jdbc.query(
            """
            SELECT
              COUNT(*) FILTER (WHERE status = 'COMPLETED') AS consults_period,
              ROUND(AVG(duration_minutes) FILTER (
                WHERE status = 'COMPLETED' AND duration_minutes IS NOT NULL
              ), 1) AS avg_duration,
              COUNT(*) FILTER (
                WHERE status = 'COMPLETED' AND e_prescription_id IS NOT NULL
              ) AS e_rx,
              COUNT(*) FILTER (
                WHERE status = 'COMPLETED' AND is_advice_only = TRUE
              ) AS advice_only,
              ROUND(AVG(rating) FILTER (WHERE rating IS NOT NULL), 2) AS satisfaction
            FROM consults
            WHERE deleted_at IS NULL
              AND doctor_id = ?
              AND call_ended_at IS NOT NULL
              AND call_ended_at >= ?
              AND call_ended_at < ?
            """,
            rs -> {
              if (!rs.next()) {
                return new DoctorPeriodStats(0, null, 0, 0, null, List.of());
              }
              return new DoctorPeriodStats(
                  rs.getLong("consults_period"),
                  rs.getBigDecimal("avg_duration"),
                  rs.getLong("e_rx"),
                  rs.getLong("advice_only"),
                  rs.getBigDecimal("satisfaction"),
                  List.of());
            },
            doctorId,
            Timestamp.from(rangeStart),
            Timestamp.from(rangeEnd));
    if (base == null) {
      base = new DoctorPeriodStats(0, null, 0, 0, null, List.of());
    }

    List<Map<String, Object>> byDay =
        jdbc.query(
            """
            SELECT (call_ended_at AT TIME ZONE 'Asia/Kolkata')::date AS day,
                   COUNT(*) AS cnt
            FROM consults
            WHERE deleted_at IS NULL
              AND doctor_id = ?
              AND status = 'COMPLETED'
              AND call_ended_at IS NOT NULL
              AND call_ended_at >= ?
              AND call_ended_at < ?
            GROUP BY day
            ORDER BY day ASC
            """,
            (rs, i) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("date", rs.getDate("day").toLocalDate().toString());
              row.put("count", rs.getLong("cnt"));
              return row;
            },
            doctorId,
            Timestamp.from(rangeStart),
            Timestamp.from(rangeEnd));

    return new DoctorPeriodStats(
        base.consultsPeriod(),
        base.avgCallDurationMinutes(),
        base.ePrescriptionsIssued(),
        base.adviceOnlyConsults(),
        base.patientSatisfactionRate(),
        byDay);
  }

  private Consult mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new Consult(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("doctor_id"),
        rs.getString("patient_name"),
        rs.getString("patient_phone"),
        rs.getString("slot_type"),
        instant(rs.getTimestamp("scheduled_at")),
        parseSymptoms(rs.getString("symptoms")),
        parseMeds(rs.getString("medicines_needing_rx")),
        (UUID) rs.getObject("cart_id"),
        rs.getBoolean("is_cart_mode"),
        rs.getString("reason"),
        rs.getString("status"),
        instant(rs.getTimestamp("call_started_at")),
        instant(rs.getTimestamp("call_ended_at")),
        rs.getBigDecimal("duration_minutes"),
        (UUID) rs.getObject("e_prescription_id"),
        rs.getBoolean("is_advice_only"),
        rs.getString("clinical_notes"),
        rs.getObject("rating") == null ? null : rs.getInt("rating"),
        rs.getString("feedback_text"),
        instant(rs.getTimestamp("rated_at")),
        rs.getString("auto_cancelled_reason"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        instant(rs.getTimestamp("deleted_at")));
  }

  private List<String> medicineNames(String json) {
    List<String> names = new ArrayList<>();
    for (MedicineNeed med : parseMeds(json)) {
      String n = med.name();
      if (n != null && !n.isBlank()) {
        names.add(n);
      }
    }
    return names;
  }

  private List<String> parseSymptoms(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<String> list = objectMapper.readValue(json, STRING_LIST);
      return list == null ? List.of() : list;
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Invalid symptoms JSON", ex);
    }
  }

  private List<MedicineNeed> parseMeds(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> raw = objectMapper.readValue(json, MED_LIST);
      if (raw == null) {
        return List.of();
      }
      List<MedicineNeed> out = new ArrayList<>();
      for (Map<String, Object> row : raw) {
        String name = row.get("name") == null ? null : String.valueOf(row.get("name"));
        String reason = row.get("reason") == null ? "" : String.valueOf(row.get("reason"));
        out.add(new MedicineNeed(name, reason));
      }
      return out;
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Invalid medicines_needing_rx JSON", ex);
    }
  }

  private String toJson(List<String> symptoms) {
    try {
      return objectMapper.writeValueAsString(symptoms);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize symptoms", ex);
    }
  }

  private String toJsonMeds(List<MedicineNeed> meds) {
    try {
      List<Map<String, String>> rows = new ArrayList<>();
      for (MedicineNeed m : meds) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("name", m.name());
        row.put("reason", m.reason());
        rows.add(row);
      }
      return objectMapper.writeValueAsString(rows);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize medicines_needing_rx", ex);
    }
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
