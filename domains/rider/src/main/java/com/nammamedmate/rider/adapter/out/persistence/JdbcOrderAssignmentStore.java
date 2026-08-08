package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.OrderAssignmentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderAssignmentStore implements OrderAssignmentStore {

  private static final String ACTIVE = "'PENDING_ACCEPTANCE','ACCEPTED','PICKED_UP'";

  private final JdbcTemplate jdbc;

  public JdbcOrderAssignmentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(AssignmentRecord row) {
    jdbc.update(
        """
        INSERT INTO order_assignments (
          id, order_id, rider_id, assignment_type, assigned_by, status, accept_deadline,
          accepted_at, pickup_confirmed_at, delivered_at, pickup_otp_hash, delivery_otp_hash,
          reassign_reason, composite_score, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        row.id(),
        row.orderId(),
        row.riderId(),
        row.assignmentType(),
        row.assignedBy(),
        row.status(),
        ts(row.acceptDeadline()),
        ts(row.acceptedAt()),
        ts(row.pickupConfirmedAt()),
        ts(row.deliveredAt()),
        row.pickupOtpHash(),
        row.deliveryOtpHash(),
        row.reassignReason(),
        row.compositeScore(),
        ts(row.createdAt()),
        ts(row.updatedAt()));
  }

  @Override
  public void update(AssignmentRecord row) {
    jdbc.update(
        """
        UPDATE order_assignments SET
          status = ?, accepted_at = ?, pickup_confirmed_at = ?, delivered_at = ?,
          reassign_reason = ?, updated_at = ?
        WHERE id = ?
        """,
        row.status(),
        ts(row.acceptedAt()),
        ts(row.pickupConfirmedAt()),
        ts(row.deliveredAt()),
        row.reassignReason(),
        ts(row.updatedAt()),
        row.id());
  }

  @Override
  public Optional<AssignmentRecord> findById(UUID id) {
    return one(
        """
        SELECT * FROM order_assignments WHERE id = ?
        """,
        id);
  }

  @Override
  public Optional<AssignmentRecord> findActiveByOrder(UUID orderId) {
    return one(
        """
        SELECT * FROM order_assignments
        WHERE order_id = ? AND status IN (
        """
            + ACTIVE
            + """
              )
        ORDER BY created_at DESC LIMIT 1
        """,
        orderId);
  }

  @Override
  public Optional<AssignmentRecord> findLatestByOrderAndRider(UUID orderId, UUID riderId) {
    return one(
        """
        SELECT * FROM order_assignments
        WHERE order_id = ? AND rider_id = ?
        ORDER BY created_at DESC
        LIMIT 1
        """,
        orderId,
        riderId);
  }

  @Override
  public Optional<AssignmentRecord> findCurrentForRider(UUID riderId) {
    return one(
        """
        SELECT * FROM order_assignments
        WHERE rider_id = ? AND status IN (
        """
            + ACTIVE
            + """
              )
        ORDER BY
          CASE status
            WHEN 'PICKED_UP' THEN 0
            WHEN 'ACCEPTED' THEN 1
            ELSE 2
          END,
          created_at DESC
        LIMIT 1
        """,
        riderId);
  }

  @Override
  public int countActiveForRider(UUID riderId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM order_assignments
            WHERE rider_id = ? AND status IN (
            """
                + ACTIVE
                + ")",
            Integer.class,
            riderId);
    return n == null ? 0 : n;
  }

  @Override
  public List<AssignmentRecord> findPendingPastDeadline(Instant now, int limit) {
    return jdbc.query(
        """
        SELECT * FROM order_assignments
        WHERE status = 'PENDING_ACCEPTANCE' AND accept_deadline <= ?
        ORDER BY accept_deadline ASC
        LIMIT ?
        """,
        (rs, i) -> map(rs),
        ts(now),
        limit);
  }

  @Override
  public boolean hasActiveForOrder(UUID orderId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM order_assignments
            WHERE order_id = ? AND status IN (
            """
                + ACTIVE
                + ")",
            Integer.class,
            orderId);
    int count = n == null ? 0 : n;
    return count > 0;
  }

  private Optional<AssignmentRecord> one(String sql, Object... args) {
    List<AssignmentRecord> rows = jdbc.query(sql, (rs, i) -> map(rs), args);
    return rows.stream().findFirst();
  }

  private static AssignmentRecord map(ResultSet rs) throws SQLException {
    return new AssignmentRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("order_id"),
        (UUID) rs.getObject("rider_id"),
        rs.getString("assignment_type"),
        (UUID) rs.getObject("assigned_by"),
        rs.getString("status"),
        instant(rs.getTimestamp("accept_deadline")),
        instant(rs.getTimestamp("accepted_at")),
        instant(rs.getTimestamp("pickup_confirmed_at")),
        instant(rs.getTimestamp("delivered_at")),
        rs.getString("pickup_otp_hash"),
        rs.getString("delivery_otp_hash"),
        rs.getString("reassign_reason"),
        rs.getBigDecimal("composite_score"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
