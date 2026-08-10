package com.nammamedmate.prescription.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcRxAuditStore implements RxAuditStore {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcRxAuditStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public void insert(RxAuditEntry e) {
    jdbc.update(
        """
        INSERT INTO rx_audit_entry (
          id, rx_id, order_id, pharmacy_id, schedule, audit_status, audit_deadline,
          possible_duplicate, possible_duplicate_rx_id, verified_by, verified_at,
          flag_reason, flag_severity, flagged_by, flagged_at, notes, created_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?,
          ?, ?, ?, ?, ?, ?
        )
        """,
        e.id(),
        e.rxId(),
        e.orderId(),
        e.pharmacyId(),
        e.schedule(),
        e.auditStatus(),
        Timestamp.from(e.auditDeadline()),
        e.possibleDuplicate(),
        e.possibleDuplicateRxId(),
        e.verifiedBy(),
        ts(e.verifiedAt()),
        e.flagReason(),
        e.flagSeverity(),
        e.flaggedBy(),
        ts(e.flaggedAt()),
        e.notes(),
        Timestamp.from(e.createdAt()));
  }

  @Override
  public Optional<RxAuditEntry> findByRxId(UUID rxId) {
    List<RxAuditEntry> rows =
        jdbc.query("SELECT * FROM rx_audit_entry WHERE rx_id = ?", this::mapEntry, rxId);
    return rows.stream().findFirst();
  }

  @Override
  public void update(RxAuditEntry e) {
    jdbc.update(
        """
        UPDATE rx_audit_entry SET
          audit_status = ?,
          possible_duplicate = ?,
          possible_duplicate_rx_id = ?,
          verified_by = ?,
          verified_at = ?,
          flag_reason = ?,
          flag_severity = ?,
          flagged_by = ?,
          flagged_at = ?,
          notes = ?
        WHERE id = ?
        """,
        e.auditStatus(),
        e.possibleDuplicate(),
        e.possibleDuplicateRxId(),
        e.verifiedBy(),
        ts(e.verifiedAt()),
        e.flagReason(),
        e.flagSeverity(),
        e.flaggedBy(),
        ts(e.flaggedAt()),
        e.notes(),
        e.id());
  }

  @Override
  public void appendActivity(
      UUID id,
      UUID rxId,
      String action,
      UUID actorId,
      String actorRole,
      String payloadJson,
      Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO compliance_activity_log (
          id, rx_id, action, actor_id, actor_role, payload, created_at
        ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
        """,
        id,
        rxId,
        action,
        actorId,
        actorRole,
        payloadJson == null ? "{}" : payloadJson,
        Timestamp.from(createdAt));
  }

  @Override
  public List<Map<String, Object>> listActivity(UUID rxId) {
    return jdbc.query(
        """
        SELECT id, action, actor_id, actor_role, payload::text, created_at
        FROM compliance_activity_log
        WHERE rx_id = ?
        ORDER BY created_at ASC
        """,
        (rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", rs.getObject("id"));
          m.put("action", rs.getString("action"));
          m.put("actor_id", rs.getObject("actor_id"));
          m.put("actor_role", rs.getString("actor_role"));
          m.put("payload", parseMap(rs.getString("payload")));
          m.put("created_at", rs.getTimestamp("created_at").toInstant());
          return m;
        },
        rxId);
  }

  @Override
  public ListPage list(ListFilter filter, Instant now) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    appendFilters(where, args, filter);
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rx_audit_entry a"
                + " JOIN prescription p ON p.id = a.rx_id AND p.deleted_at IS NULL"
                + where,
            Long.class,
            args.toArray());
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<ListRow> items =
        jdbc.query(
            """
            SELECT a.*, p.patient_name, p.doctor_name, p.type AS rx_type, p.source AS rx_source,
                   ph.name AS pharmacy_name,
                   q.dispensed_at,
                   (
                     SELECT string_agg(m->>'name', '; ')
                     FROM pharmacy_rx_queue q2
                     CROSS JOIN LATERAL jsonb_array_elements(COALESCE(q2.approved_medicines, '[]'::jsonb)) m
                     WHERE q2.rx_id = a.rx_id AND q2.pharmacy_id = a.pharmacy_id AND q2.deleted_at IS NULL
                   ) AS drug_summary
            FROM rx_audit_entry a
            JOIN prescription p ON p.id = a.rx_id AND p.deleted_at IS NULL
            JOIN pharmacies ph ON ph.id = a.pharmacy_id
            LEFT JOIN pharmacy_rx_queue q
              ON q.rx_id = a.rx_id AND q.pharmacy_id = a.pharmacy_id AND q.deleted_at IS NULL
            """
                + where
                + " ORDER BY a.audit_deadline ASC LIMIT ? OFFSET ?",
            this::mapListRow,
            pageArgs.toArray());
    return new ListPage(items, total == null ? 0L : total, computeKpis(now));
  }

  @Override
  public List<ListRow> listAllForExport(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE 1=1 ");
    List<Object> args = new ArrayList<>();
    appendFilters(where, args, filter);
    args.add(Math.min(filter.limit(), 10_000));
    return jdbc.query(
        """
        SELECT a.*, p.patient_name, p.doctor_name, p.type AS rx_type, p.source AS rx_source,
               ph.name AS pharmacy_name,
               q.dispensed_at,
               (
                 SELECT string_agg(m->>'name', '; ')
                 FROM pharmacy_rx_queue q2
                 CROSS JOIN LATERAL jsonb_array_elements(COALESCE(q2.approved_medicines, '[]'::jsonb)) m
                 WHERE q2.rx_id = a.rx_id AND q2.pharmacy_id = a.pharmacy_id AND q2.deleted_at IS NULL
               ) AS drug_summary
        FROM rx_audit_entry a
        JOIN prescription p ON p.id = a.rx_id AND p.deleted_at IS NULL
        JOIN pharmacies ph ON ph.id = a.pharmacy_id
        LEFT JOIN pharmacy_rx_queue q
          ON q.rx_id = a.rx_id AND q.pharmacy_id = a.pharmacy_id AND q.deleted_at IS NULL
        """
            + where
            + " ORDER BY a.created_at ASC LIMIT ?",
        this::mapListRow,
        args.toArray());
  }

  @Override
  public Optional<DuplicateMatch> findDuplicate(
      String patientName, String drugName, int quantity, Instant since, UUID excludeRxId) {
    List<DuplicateMatch> rows =
        jdbc.query(
            """
            SELECT a.rx_id, a.id AS audit_id
            FROM rx_audit_entry a
            JOIN prescription p ON p.id = a.rx_id AND p.deleted_at IS NULL
            JOIN pharmacy_rx_queue q
              ON q.rx_id = a.rx_id AND q.pharmacy_id = a.pharmacy_id AND q.deleted_at IS NULL
            WHERE p.patient_name = ?
              AND a.rx_id <> ?
              AND a.created_at >= ?
              AND EXISTS (
                SELECT 1
                FROM jsonb_array_elements(COALESCE(q.approved_medicines, '[]'::jsonb)) m
                WHERE LOWER(m->>'name') = LOWER(?)
                  AND COALESCE((m->>'quantity')::int, 1) = ?
              )
            ORDER BY a.created_at DESC
            LIMIT 1
            """,
            (rs, i) ->
                new DuplicateMatch((UUID) rs.getObject("rx_id"), (UUID) rs.getObject("audit_id")),
            patientName,
            excludeRxId,
            Timestamp.from(since),
            drugName,
            quantity);
    return rows.stream().findFirst();
  }

  @Override
  public List<RxAuditEntry> findAwaitingPastDeadline(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM rx_audit_entry
        WHERE audit_status = 'AWAITING_AUDIT' AND audit_deadline < ?
        ORDER BY audit_deadline ASC
        LIMIT ?
        """,
        this::mapEntry,
        Timestamp.from(now),
        limit);
  }

  @Override
  public int markOverdue(UUID id, Instant now) {
    return jdbc.update(
        """
        UPDATE rx_audit_entry
        SET audit_status = 'OVERDUE_AUDIT'
        WHERE id = ? AND audit_status = 'AWAITING_AUDIT'
        """,
        id);
  }

  @Override
  public Stats statistics(LocalDate from, LocalDate to) {
    Instant fromInclusive = from.atStartOfDay(IST).toInstant();
    Instant toExclusive = to.plusDays(1).atStartOfDay(IST).toInstant();
    Map<String, Double> rates = new LinkedHashMap<>();
    for (String schedule : List.of("H", "H1", "X")) {
      rates.put(schedule, complianceRate(schedule, fromInclusive, toExclusive));
    }
    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE schedule IN ('H','H1','X')
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    Long verified =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE schedule IN ('H','H1','X')
              AND audit_status = 'VERIFIED'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    Long flagged =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE schedule IN ('H','H1','X')
              AND audit_status = 'FLAGGED'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    Long overdue =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE audit_status = 'OVERDUE_AUDIT'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    long t = nz(total);
    long f = nz(flagged);
    double flaggedRate = 0d;
    if (t > 0) {
      flaggedRate =
          BigDecimal.valueOf(f * 100.0 / t).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
    List<Map<String, Object>> topPharm =
        jdbc.query(
            """
            SELECT a.pharmacy_id, ph.name, COUNT(*) AS flagged_count
            FROM rx_audit_entry a
            JOIN pharmacies ph ON ph.id = a.pharmacy_id
            WHERE a.audit_status = 'FLAGGED'
              AND a.created_at >= ? AND a.created_at < ?
            GROUP BY a.pharmacy_id, ph.name
            ORDER BY flagged_count DESC
            LIMIT 5
            """,
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("pharmacy_id", rs.getObject("pharmacy_id"));
              m.put("name", rs.getString("name"));
              m.put("flagged_count", rs.getLong("flagged_count"));
              return m;
            },
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    List<Map<String, Object>> topDrugs =
        jdbc.query(
            """
            SELECT m->>'name' AS drug_name,
                   COALESCE(m->>'schedule', a.schedule) AS schedule,
                   COUNT(*) AS flag_count
            FROM rx_audit_entry a
            JOIN pharmacy_rx_queue q
              ON q.rx_id = a.rx_id AND q.pharmacy_id = a.pharmacy_id AND q.deleted_at IS NULL
            CROSS JOIN LATERAL jsonb_array_elements(COALESCE(q.approved_medicines, '[]'::jsonb)) m
            WHERE a.audit_status = 'FLAGGED'
              AND a.created_at >= ? AND a.created_at < ?
            GROUP BY 1, 2
            ORDER BY flag_count DESC
            LIMIT 5
            """,
            (rs, i) -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("drug_name", rs.getString("drug_name"));
              m.put("schedule", rs.getString("schedule"));
              m.put("flag_count", rs.getLong("flag_count"));
              return m;
            },
            Timestamp.from(fromInclusive),
            Timestamp.from(toExclusive));
    return new Stats(rates, flaggedRate, topPharm, topDrugs, t, nz(verified), f, nz(overdue));
  }

  @Override
  public Optional<OrderContext> orderContext(UUID orderId) {
    if (orderId == null) {
      return Optional.empty();
    }
    List<OrderContext> rows =
        jdbc.query(
            """
            SELECT o.order_number, ph.name AS pharmacy_name
            FROM orders o
            LEFT JOIN pharmacies ph ON ph.id = o.pharmacy_id
            WHERE o.id = ? AND o.deleted_at IS NULL
            """,
            (rs, i) ->
                new OrderContext(rs.getString("order_number"), rs.getString("pharmacy_name")),
            orderId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<String> pharmacyName(UUID pharmacyId) {
    List<String> rows =
        jdbc.query(
            "SELECT name FROM pharmacies WHERE id = ?",
            (rs, i) -> rs.getString("name"),
            pharmacyId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<DispenseContext> dispenseContext(UUID rxId, UUID pharmacyId) {
    List<DispenseContext> rows =
        jdbc.query(
            """
            SELECT q.dispensed_at, q.approved_medicines::text, p.patient_name, p.doctor_name
            FROM pharmacy_rx_queue q
            JOIN prescription p ON p.id = q.rx_id
            WHERE q.rx_id = ? AND q.pharmacy_id = ? AND q.deleted_at IS NULL
            LIMIT 1
            """,
            (rs, i) ->
                new DispenseContext(
                    instant(rs.getTimestamp("dispensed_at")),
                    parseMeds(rs.getString("approved_medicines")),
                    rs.getString("patient_name"),
                    rs.getString("doctor_name")),
            rxId,
            pharmacyId);
    return rows.stream().findFirst();
  }

  private double complianceRate(String schedule, Instant from, Instant to) {
    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE schedule = ? AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            schedule,
            Timestamp.from(from),
            Timestamp.from(to));
    Long verified =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE schedule = ? AND audit_status = 'VERIFIED'
              AND created_at >= ? AND created_at < ?
            """,
            Long.class,
            schedule,
            Timestamp.from(from),
            Timestamp.from(to));
    long t = nz(total);
    if (t == 0) {
      return 0d;
    }
    long v = nz(verified);
    return BigDecimal.valueOf(v * 100.0 / t).setScale(1, RoundingMode.HALF_UP).doubleValue();
  }

  private static long nz(Long v) {
    return v == null ? 0L : v;
  }

  private Kpis computeKpis(Instant now) {
    Long awaiting =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rx_audit_entry WHERE audit_status = 'AWAITING_AUDIT'",
            Long.class);
    Long flagged =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rx_audit_entry WHERE audit_status = 'FLAGGED'", Long.class);
    Long h1x =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE schedule IN ('H1','X') AND audit_status IN ('AWAITING_AUDIT','OVERDUE_AUDIT')
            """,
            Long.class);
    LocalDate today = LocalDate.now(IST);
    Instant dayStart = today.atStartOfDay(IST).toInstant();
    Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
    Long verifiedToday =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE audit_status = 'VERIFIED'
              AND verified_at >= ? AND verified_at < ?
            """,
            Long.class,
            Timestamp.from(dayStart),
            Timestamp.from(dayEnd));
    Long auditable =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rx_audit_entry WHERE schedule IN ('H1','X')", Long.class);
    Long verifiedH1X =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM rx_audit_entry
            WHERE schedule IN ('H1','X') AND audit_status = 'VERIFIED'
            """,
            Long.class);
    long a = nz(auditable);
    double rate = 0d;
    if (a > 0) {
      rate =
          BigDecimal.valueOf(nz(verifiedH1X) * 100.0 / a)
              .setScale(1, RoundingMode.HALF_UP)
              .doubleValue();
    }
    return new Kpis(nz(awaiting), nz(flagged), nz(h1x), nz(verifiedToday), rate);
  }

  private void appendFilters(StringBuilder where, List<Object> args, ListFilter filter) {
    if (filter.schedule() != null) {
      if (!"ALL".equals(filter.schedule())) {
        where.append(" AND a.schedule = ? ");
        args.add(filter.schedule());
      }
    }
    if (filter.status() != null) {
      if (!"ALL".equals(filter.status())) {
        where.append(" AND a.audit_status = ? ");
        args.add(filter.status());
      }
    }
    if (filter.source() != null) {
      if ("DIGITAL".equals(filter.source())) {
        where.append(" AND p.type = 'E_PRESCRIPTION' ");
      } else {
        where.append(" AND p.type = 'UPLOADED' ");
      }
    }
    if (filter.fromDate() != null) {
      where.append(" AND a.created_at >= ? ");
      args.add(Timestamp.from(filter.fromDate().atStartOfDay(IST).toInstant()));
    }
    if (filter.toDate() != null) {
      where.append(" AND a.created_at < ? ");
      args.add(Timestamp.from(filter.toDate().plusDays(1).atStartOfDay(IST).toInstant()));
    }
    if (filter.pharmacyId() != null) {
      where.append(" AND a.pharmacy_id = ? ");
      args.add(filter.pharmacyId());
    }
    if (filter.search() != null) {
      where.append(
          """
           AND (
             CAST(a.rx_id AS text) ILIKE ?
             OR COALESCE(p.patient_name,'') ILIKE ?
             OR COALESCE(p.doctor_name,'') ILIKE ?
           )
          """);
      String q = "%" + filter.search() + "%";
      args.add(q);
      args.add(q);
      args.add(q);
    }
  }

  private ListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
    RxAuditEntry entry = mapEntry(rs, rowNum);
    String rxType = rs.getString("rx_type");
    String source = "E_PRESCRIPTION".equals(rxType) ? "DIGITAL" : "UPLOADED";
    return new ListRow(
        entry,
        rs.getString("patient_name"),
        rs.getString("doctor_name"),
        "E_PRESCRIPTION".equals(rs.getString("rx_type")),
        rs.getString("pharmacy_name"),
        instant(rs.getTimestamp("dispensed_at")),
        source,
        rs.getString("drug_summary"));
  }

  private RxAuditEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
    return new RxAuditEntry(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rx_id"),
        (UUID) rs.getObject("order_id"),
        (UUID) rs.getObject("pharmacy_id"),
        rs.getString("schedule"),
        rs.getString("audit_status"),
        rs.getTimestamp("audit_deadline").toInstant(),
        rs.getBoolean("possible_duplicate"),
        (UUID) rs.getObject("possible_duplicate_rx_id"),
        (UUID) rs.getObject("verified_by"),
        instant(rs.getTimestamp("verified_at")),
        rs.getString("flag_reason"),
        rs.getString("flag_severity"),
        (UUID) rs.getObject("flagged_by"),
        instant(rs.getTimestamp("flagged_at")),
        rs.getString("notes"),
        rs.getTimestamp("created_at").toInstant());
  }

  private List<Map<String, Object>> parseMeds(String json) {
    if (json == null) {
      return List.of();
    }
    if (json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> raw = objectMapper.readValue(json, LIST_MAP);
      List<Map<String, Object>> out = new ArrayList<>();
      for (Map<String, Object> row : raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", row.get("name"));
        m.put("quantity", row.get("quantity"));
        m.put("schedule", row.get("schedule"));
        out.add(m);
      }
      return out;
    } catch (JsonProcessingException e) {
      return List.of();
    }
  }

  private Map<String, Object> parseMap(String json) {
    if (json == null) {
      return Map.of();
    }
    if (json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private static Timestamp ts(Instant instant) {
    if (instant == null) {
      return null;
    }
    return Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    if (ts == null) {
      return null;
    }
    return ts.toInstant();
  }
}
