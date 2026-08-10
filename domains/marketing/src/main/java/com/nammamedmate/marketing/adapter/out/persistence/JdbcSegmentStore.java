package com.nammamedmate.marketing.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentCriterion;
import com.nammamedmate.marketing.domain.SegmentType;
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
public class JdbcSegmentStore implements SegmentStore {

  private static final TypeReference<List<SegmentCriterion>> CRITERIA_TYPE =
      new TypeReference<>() {};

  private static final String SELECT =
      """
      SELECT id, name, description, segment_type, criteria, status, customer_count,
             avg_aov_paise, total_ltv_paise, last_computed_at, created_by,
             created_at, updated_at, deleted_at
      FROM segments
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcSegmentStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public Segment insert(Segment segment) {
    jdbc.update(
        """
        INSERT INTO segments (
          id, name, description, segment_type, criteria, status, customer_count,
          avg_aov_paise, total_ltv_paise, last_computed_at, created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        segment.id(),
        segment.name(),
        segment.description(),
        segment.segmentType().name(),
        writeCriteria(segment.criteria()),
        segment.status(),
        segment.customerCount(),
        segment.avgAovPaise(),
        segment.totalLtvPaise(),
        ts(segment.lastComputedAt()),
        segment.createdBy(),
        Timestamp.from(segment.createdAt()),
        Timestamp.from(segment.updatedAt()));
    return segment;
  }

  @Override
  public Optional<Segment> findById(UUID id) {
    List<Segment> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", (rs, i) -> mapSegment(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Segment> findByNameIgnoreCase(String name) {
    List<Segment> rows =
        jdbc.query(
            SELECT + " WHERE LOWER(name) = LOWER(?) AND deleted_at IS NULL",
            (rs, i) -> mapSegment(rs),
            name);
    return rows.stream().findFirst();
  }

  @Override
  public List<Segment> list(SegmentType typeFilter, int offset, int limit) {
    if (typeFilter == null) {
      return jdbc.query(
          SELECT
              + " WHERE deleted_at IS NULL ORDER BY segment_type DESC, name ASC LIMIT ? OFFSET ?",
          (rs, i) -> mapSegment(rs),
          limit,
          offset);
    }
    return jdbc.query(
        SELECT
            + " WHERE deleted_at IS NULL AND segment_type = ? ORDER BY name ASC LIMIT ? OFFSET ?",
        (rs, i) -> mapSegment(rs),
        typeFilter.name(),
        limit,
        offset);
  }

  @Override
  public long count(SegmentType typeFilter) {
    if (typeFilter == null) {
      Long n =
          jdbc.queryForObject("SELECT COUNT(*) FROM segments WHERE deleted_at IS NULL", Long.class);
      return n == null ? 0 : n;
    }
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM segments WHERE deleted_at IS NULL AND segment_type = ?",
            Long.class,
            typeFilter.name());
    return n == null ? 0 : n;
  }

  @Override
  public void softDelete(UUID id, Instant deletedAt) {
    jdbc.update(
        "UPDATE segments SET deleted_at = ?, updated_at = ? WHERE id = ? AND deleted_at IS NULL",
        Timestamp.from(deletedAt),
        Timestamp.from(deletedAt),
        id);
  }

  @Override
  public void updateComputeResult(
      UUID id,
      int customerCount,
      Long avgAovPaise,
      Long totalLtvPaise,
      Instant computedAt,
      String status) {
    jdbc.update(
        """
        UPDATE segments
        SET customer_count = ?, avg_aov_paise = ?, total_ltv_paise = ?,
            last_computed_at = ?, status = ?, updated_at = ?
        WHERE id = ?
        """,
        customerCount,
        avgAovPaise,
        totalLtvPaise,
        Timestamp.from(computedAt),
        status,
        Timestamp.from(computedAt),
        id);
  }

  @Override
  public void replaceMemberships(UUID segmentId, List<UUID> customerIds, Instant addedAt) {
    jdbc.update("DELETE FROM segment_memberships WHERE segment_id = ?", segmentId);
    if (customerIds == null || customerIds.isEmpty()) {
      return;
    }
    Timestamp ts = Timestamp.from(addedAt);
    List<Object[]> batch = new ArrayList<>(customerIds.size());
    for (UUID customerId : customerIds) {
      batch.add(new Object[] {segmentId, customerId, ts});
    }
    jdbc.batchUpdate(
        "INSERT INTO segment_memberships (segment_id, customer_id, added_at) VALUES (?, ?, ?)",
        batch);
  }

  @Override
  public void upsertSnapshot(UUID segmentId, LocalDate snapshotDate, int customerCount) {
    jdbc.update(
        """
        INSERT INTO segment_snapshots (segment_id, snapshot_date, customer_count)
        VALUES (?, ?, ?)
        ON CONFLICT (segment_id, snapshot_date)
        DO UPDATE SET customer_count = EXCLUDED.customer_count
        """,
        segmentId,
        java.sql.Date.valueOf(snapshotDate),
        customerCount);
  }

  @Override
  public List<SnapshotPoint> growthChart(UUID segmentId, int limit) {
    return jdbc.query(
        """
        SELECT snapshot_date, customer_count
        FROM segment_snapshots
        WHERE segment_id = ?
        ORDER BY snapshot_date DESC
        LIMIT ?
        """,
        (rs, i) ->
            new SnapshotPoint(
                rs.getDate("snapshot_date").toLocalDate(), rs.getInt("customer_count")),
        segmentId,
        limit);
  }

  @Override
  public PagedMemberships listMembers(
      UUID segmentId, String sort, String order, int offset, int limit) {
    String sortCol =
        switch (sort == null ? "ltv_rs" : sort.toLowerCase(Locale.ROOT)) {
          case "total_orders" -> "c.total_orders";
          case "name" -> "c.name";
          default -> "c.total_ltv_paise";
        };
    String dir = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
    Long total =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM segment_memberships WHERE segment_id = ?", Long.class, segmentId);
    long t = total == null ? 0 : total;
    String sql =
        """
        SELECT c.id, c.name, c.phone, c.total_orders, c.total_ltv_paise, c.last_order_at
        FROM segment_memberships m
        JOIN customers c ON c.id = m.customer_id
        WHERE m.segment_id = ?
        ORDER BY """
            + sortCol
            + " "
            + dir
            + " NULLS LAST LIMIT ? OFFSET ?";
    List<MembershipCustomer> rows =
        jdbc.query(
            sql,
            (rs, i) ->
                new MembershipCustomer(
                    (UUID) rs.getObject("id"),
                    rs.getString("name"),
                    rs.getString("phone"),
                    rs.getInt("total_orders"),
                    rs.getLong("total_ltv_paise"),
                    rs.getTimestamp("last_order_at") == null
                        ? null
                        : rs.getTimestamp("last_order_at").toInstant()),
            segmentId,
            limit,
            offset);
    return new PagedMemberships(rows, t);
  }

  @Override
  public boolean isMember(UUID segmentId, UUID customerId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM segment_memberships
            WHERE segment_id = ? AND customer_id = ?
            """,
            Long.class,
            segmentId,
            customerId);
    return n != null && n > 0;
  }

  @Override
  public UUID enqueueComputeJob(UUID segmentId, Instant createdAt) {
    UUID id = com.nammamedmate.kernel.id.Ids.newId();
    jdbc.update(
        """
        INSERT INTO segment_compute_jobs (id, segment_id, status, created_at)
        VALUES (?, ?, 'QUEUED', ?)
        """,
        id,
        segmentId,
        Timestamp.from(createdAt));
    return id;
  }

  @Override
  public Optional<ComputeJob> findJob(UUID jobId) {
    List<ComputeJob> rows =
        jdbc.query(
            "SELECT id, segment_id, status FROM segment_compute_jobs WHERE id = ?",
            (rs, i) ->
                new ComputeJob(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("segment_id"),
                    rs.getString("status")),
            jobId);
    return rows.stream().findFirst();
  }

  @Override
  public List<ComputeJob> findQueuedJobs(int limit) {
    return jdbc.query(
        """
        SELECT id, segment_id, status FROM segment_compute_jobs
        WHERE status = 'QUEUED'
        ORDER BY created_at ASC
        LIMIT ?
        """,
        (rs, i) ->
            new ComputeJob(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("segment_id"),
                rs.getString("status")),
        limit);
  }

  @Override
  public void markJobRunning(UUID jobId, Instant startedAt) {
    jdbc.update(
        "UPDATE segment_compute_jobs SET status = 'RUNNING', started_at = ? WHERE id = ? AND status = 'QUEUED'",
        Timestamp.from(startedAt),
        jobId);
  }

  @Override
  public void markJobCompleted(UUID jobId, Instant completedAt) {
    jdbc.update(
        "UPDATE segment_compute_jobs SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
        Timestamp.from(completedAt),
        jobId);
  }

  @Override
  public void markJobFailed(UUID jobId, Instant completedAt, String error) {
    jdbc.update(
        "UPDATE segment_compute_jobs SET status = 'FAILED', completed_at = ?, error_message = ? WHERE id = ?",
        Timestamp.from(completedAt),
        error,
        jobId);
  }

  private Segment mapSegment(ResultSet rs) throws SQLException {
    return new Segment(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        rs.getString("description"),
        SegmentType.valueOf(rs.getString("segment_type")),
        readCriteria(rs.getString("criteria")),
        rs.getString("status"),
        rs.getInt("customer_count"),
        (Long) rs.getObject("avg_aov_paise"),
        (Long) rs.getObject("total_ltv_paise"),
        rs.getTimestamp("last_computed_at") == null
            ? null
            : rs.getTimestamp("last_computed_at").toInstant(),
        (UUID) rs.getObject("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
  }

  private String writeCriteria(List<SegmentCriterion> criteria) {
    try {
      return objectMapper.writeValueAsString(criteria == null ? List.of() : criteria);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("criteria serialize failed", e);
    }
  }

  private List<SegmentCriterion> readCriteria(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<SegmentCriterion> list = objectMapper.readValue(json, CRITERIA_TYPE);
      return list == null ? List.of() : list;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("criteria deserialize failed", e);
    }
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
