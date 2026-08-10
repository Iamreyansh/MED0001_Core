package com.nammamedmate.prescription.adapter.out.persistence;

import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcDoctorStore implements DoctorStore {

  private static final Set<String> SORTS =
      Set.of("name", "prescription_count", "scheduled_drug_count", "verified_at");

  private final JdbcTemplate jdbc;

  public JdbcDoctorStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(DoctorRecord d) {
    jdbc.update(
        """
        INSERT INTO doctor (
          id, registration_no, name, qualification, specialty, status, source,
          prescription_count, scheduled_drug_count, verification_method,
          verified_by, verified_at, verification_notes,
          blacklist_reason, blacklisted_by, blacklisted_at,
          created_at, updated_at, deleted_at
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?
        )
        """,
        d.id(),
        d.registrationNo(),
        d.name(),
        d.qualification(),
        d.specialty(),
        d.status(),
        d.source(),
        d.prescriptionCount(),
        d.scheduledDrugCount(),
        d.verificationMethod(),
        d.verifiedBy(),
        ts(d.verifiedAt()),
        d.verificationNotes(),
        d.blacklistReason(),
        d.blacklistedBy(),
        ts(d.blacklistedAt()),
        Timestamp.from(d.createdAt()),
        Timestamp.from(d.updatedAt()),
        ts(d.deletedAt()));
  }

  @Override
  public void update(DoctorRecord d) {
    jdbc.update(
        """
        UPDATE doctor SET
          registration_no = ?, name = ?, qualification = ?, specialty = ?,
          status = ?, source = ?, prescription_count = ?, scheduled_drug_count = ?,
          verification_method = ?, verified_by = ?, verified_at = ?, verification_notes = ?,
          blacklist_reason = ?, blacklisted_by = ?, blacklisted_at = ?,
          updated_at = ?, deleted_at = ?
        WHERE id = ?
        """,
        d.registrationNo(),
        d.name(),
        d.qualification(),
        d.specialty(),
        d.status(),
        d.source(),
        d.prescriptionCount(),
        d.scheduledDrugCount(),
        d.verificationMethod(),
        d.verifiedBy(),
        ts(d.verifiedAt()),
        d.verificationNotes(),
        d.blacklistReason(),
        d.blacklistedBy(),
        ts(d.blacklistedAt()),
        Timestamp.from(d.updatedAt()),
        ts(d.deletedAt()),
        d.id());
  }

  @Override
  public Optional<DoctorRecord> findById(UUID id) {
    List<DoctorRecord> rows =
        jdbc.query("SELECT * FROM doctor WHERE id = ? AND deleted_at IS NULL", this::mapRow, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<DoctorRecord> findByRegistrationNo(String registrationNo) {
    if (registrationNo == null || registrationNo.isBlank()) {
      return Optional.empty();
    }
    List<DoctorRecord> rows =
        jdbc.query(
            "SELECT * FROM doctor WHERE registration_no = ? AND deleted_at IS NULL",
            this::mapRow,
            registrationNo.trim());
    return rows.stream().findFirst();
  }

  @Override
  public Page list(ListFilter filter) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE deleted_at IS NULL ");
    if (filter.search() != null && !filter.search().isBlank()) {
      where.append(" AND (name ILIKE ? OR registration_no ILIKE ?) ");
      String q = "%" + filter.search().trim() + "%";
      args.add(q);
      args.add(q);
    }
    if (filter.specialty() != null && !filter.specialty().isBlank()) {
      where.append(" AND specialty ILIKE ? ");
      args.add("%" + filter.specialty().trim() + "%");
    }
    if (filter.status() != null
        && !"ALL".equalsIgnoreCase(filter.status())
        && !filter.status().isBlank()) {
      where.append(" AND status = ? ");
      args.add(filter.status().trim().toUpperCase(Locale.ROOT));
    }
    Long total =
        jdbc.queryForObject("SELECT COUNT(*) FROM doctor" + where, Long.class, args.toArray());
    long totalCount = total == null ? 0L : total;
    String sortCol = SORTS.contains(filter.sort()) ? filter.sort() : "prescription_count";
    String dir = "asc".equalsIgnoreCase(filter.order()) ? "ASC" : "DESC";
    // NULLs last for verified_at
    String orderBy =
        "verified_at".equals(sortCol) ? "verified_at " + dir + " NULLS LAST" : sortCol + " " + dir;
    int offset = (filter.page() - 1) * filter.limit();
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(filter.limit());
    pageArgs.add(offset);
    List<DoctorRecord> items =
        jdbc.query(
            "SELECT * FROM doctor" + where + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
            this::mapRow,
            pageArgs.toArray());
    return new Page(items, totalCount);
  }

  @Override
  public Page listUnverified(int page, int limit) {
    return list(
        new ListFilter(null, null, "UNVERIFIED", page, limit, "prescription_count", "desc"));
  }

  @Override
  public void linkPrescription(
      UUID rxId, UUID doctorId, boolean unrecognizedQualification, Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO prescription_doctor_link (
          rx_id, doctor_id, unrecognized_qualification, pending_blacklist_flag, created_at
        ) VALUES (?, ?, ?, FALSE, ?)
        ON CONFLICT (rx_id) DO UPDATE SET
          doctor_id = EXCLUDED.doctor_id,
          unrecognized_qualification =
            prescription_doctor_link.unrecognized_qualification OR EXCLUDED.unrecognized_qualification
        """,
        rxId,
        doctorId,
        unrecognizedQualification,
        Timestamp.from(createdAt));
  }

  @Override
  public Optional<Link> findLink(UUID rxId) {
    List<Link> rows =
        jdbc.query(
            """
            SELECT rx_id, doctor_id, unrecognized_qualification, pending_blacklist_flag
            FROM prescription_doctor_link WHERE rx_id = ?
            """,
            (rs, i) ->
                new Link(
                    (UUID) rs.getObject("rx_id"),
                    (UUID) rs.getObject("doctor_id"),
                    rs.getBoolean("unrecognized_qualification"),
                    rs.getBoolean("pending_blacklist_flag")),
            rxId);
    return rows.stream().findFirst();
  }

  @Override
  public void markPendingBlacklist(UUID doctorId) {
    jdbc.update(
        """
        UPDATE prescription_doctor_link SET pending_blacklist_flag = TRUE
        WHERE doctor_id = ?
        """,
        doctorId);
  }

  @Override
  public List<UUID> listRxIdsForDoctor(UUID doctorId) {
    return jdbc.query(
        "SELECT rx_id FROM prescription_doctor_link WHERE doctor_id = ?",
        (rs, i) -> (UUID) rs.getObject("rx_id"),
        doctorId);
  }

  @Override
  public int countRxForDoctor(UUID doctorId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM prescription_doctor_link WHERE doctor_id = ?",
            Integer.class,
            doctorId);
    return n == null ? 0 : n;
  }

  @Override
  public void incrementPrescriptionCount(UUID doctorId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE doctor SET prescription_count = prescription_count + 1, updated_at = ?
        WHERE id = ?
        """,
        Timestamp.from(updatedAt),
        doctorId);
  }

  @Override
  public void incrementScheduledDrugCount(UUID doctorId, Instant updatedAt) {
    jdbc.update(
        """
        UPDATE doctor SET scheduled_drug_count = scheduled_drug_count + 1, updated_at = ?
        WHERE id = ?
        """,
        Timestamp.from(updatedAt),
        doctorId);
  }

  @Override
  public void insertScheduleEvent(UUID eventId, UUID doctorId, UUID rxId, Instant createdAt) {
    jdbc.update(
        """
        INSERT INTO doctor_schedule_event (id, doctor_id, rx_id, created_at)
        VALUES (?, ?, ?, ?)
        """,
        eventId,
        doctorId,
        rxId,
        Timestamp.from(createdAt));
  }

  @Override
  public long countScheduleEventsSince(UUID doctorId, Instant since) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM doctor_schedule_event
            WHERE doctor_id = ? AND created_at >= ?
            """,
            Long.class,
            doctorId,
            Timestamp.from(since));
    return n == null ? 0L : n;
  }

  @Override
  public Map<String, Integer> prescriptionCategoryCounts(UUID doctorId) {
    // ponytail: bucket OCR medicine names until catalogue taxonomy exists
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT m->>'name' AS drug_name
            FROM prescription_doctor_link l
            JOIN prescription p ON p.id = l.rx_id AND p.deleted_at IS NULL
            CROSS JOIN LATERAL jsonb_array_elements(
              COALESCE(p.medicines_extracted, '[]'::jsonb)
            ) m
            WHERE l.doctor_id = ?
            """,
            doctorId);
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String name = row.get("drug_name") == null ? "" : String.valueOf(row.get("drug_name"));
      String cat = categorize(name);
      counts.merge(cat, 1, Integer::sum);
    }
    return counts;
  }

  @Override
  public ScheduleCounts scheduleCounts(UUID doctorId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT m->>'schedule' AS sch
            FROM prescription_doctor_link l
            JOIN prescription p ON p.id = l.rx_id AND p.deleted_at IS NULL
            CROSS JOIN LATERAL jsonb_array_elements(
              COALESCE(p.medicines_extracted, '[]'::jsonb)
            ) m
            WHERE l.doctor_id = ?
            """,
            doctorId);
    int h = 0;
    int h1 = 0;
    int x = 0;
    for (Map<String, Object> row : rows) {
      String sch = row.get("sch") == null ? "" : String.valueOf(row.get("sch"));
      if ("H".equalsIgnoreCase(sch)) {
        h++;
      } else if ("H1".equalsIgnoreCase(sch)) {
        h1++;
      } else if ("X".equalsIgnoreCase(sch)) {
        x++;
      }
    }
    return new ScheduleCounts(h, h1, x);
  }

  @Override
  public long associatedOrdersCount(UUID doctorId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT p.associated_order_id)
            FROM prescription_doctor_link l
            JOIN prescription p ON p.id = l.rx_id AND p.deleted_at IS NULL
            WHERE l.doctor_id = ? AND p.associated_order_id IS NOT NULL
            """,
            Long.class,
            doctorId);
    return n == null ? 0L : n;
  }

  private static String categorize(String name) {
    String n = name.toUpperCase(Locale.ROOT);
    if (n.contains("METFORMIN") || n.contains("GLIMEPIRIDE") || n.contains("INSULIN")) {
      return "Antidiabetics";
    }
    if (n.contains("AMOX") || n.contains("AZITHRO") || n.contains("CIPRO") || n.contains("CEFIX")) {
      return "Antibiotics";
    }
    if (n.contains("ALPRAZ") || n.contains("CLONAZEP") || n.contains("DIAZEPAM")) {
      return "Anxiolytics";
    }
    return "Other";
  }

  private DoctorRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new DoctorRecord(
        (UUID) rs.getObject("id"),
        rs.getString("registration_no"),
        rs.getString("name"),
        rs.getString("qualification"),
        rs.getString("specialty"),
        rs.getString("status"),
        rs.getString("source"),
        rs.getInt("prescription_count"),
        rs.getInt("scheduled_drug_count"),
        rs.getString("verification_method"),
        (UUID) rs.getObject("verified_by"),
        instant(rs, "verified_at"),
        rs.getString("verification_notes"),
        rs.getString("blacklist_reason"),
        (UUID) rs.getObject("blacklisted_by"),
        instant(rs, "blacklisted_at"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instant(rs, "deleted_at"));
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
