package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.CodDepositStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCodDepositStore implements CodDepositStore {

  private final JdbcTemplate jdbc;

  public JdbcCodDepositStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(DepositRecord row) {
    jdbc.update(
        """
        INSERT INTO cod_deposits (
          id, rider_id, amount_paise, deposit_mode, reference_number, status,
          submitted_at, confirmed_at, confirmed_by, deposited_at, notes, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        row.id(),
        row.riderId(),
        row.amountPaise(),
        row.depositMode(),
        row.referenceNumber(),
        row.status(),
        ts(row.submittedAt()),
        ts(row.confirmedAt()),
        row.confirmedBy(),
        ts(row.depositedAt()),
        row.notes(),
        ts(row.createdAt()),
        ts(row.updatedAt()));
  }

  @Override
  public void update(DepositRecord row) {
    jdbc.update(
        """
        UPDATE cod_deposits SET
          amount_paise = ?, deposit_mode = ?, reference_number = ?, status = ?,
          submitted_at = ?, confirmed_at = ?, confirmed_by = ?, deposited_at = ?,
          notes = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        row.amountPaise(),
        row.depositMode(),
        row.referenceNumber(),
        row.status(),
        ts(row.submittedAt()),
        ts(row.confirmedAt()),
        row.confirmedBy(),
        ts(row.depositedAt()),
        row.notes(),
        ts(row.updatedAt()),
        row.id());
  }

  @Override
  public Optional<DepositRecord> findById(UUID id) {
    List<DepositRecord> rows =
        jdbc.query("SELECT * FROM cod_deposits WHERE id = ? AND deleted_at IS NULL", this::map, id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<DepositRecord> findByReference(String referenceNumber) {
    List<DepositRecord> rows =
        jdbc.query(
            "SELECT * FROM cod_deposits WHERE reference_number = ? AND deleted_at IS NULL",
            this::map,
            referenceNumber);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<DepositRecord> findPendingByReference(UUID riderId, String referenceNumber) {
    List<DepositRecord> rows =
        jdbc.query(
            """
            SELECT * FROM cod_deposits
            WHERE rider_id = ? AND reference_number = ?
              AND status = 'PENDING_CONFIRMATION' AND deleted_at IS NULL
            """,
            this::map,
            riderId,
            referenceNumber);
    return rows.stream().findFirst();
  }

  @Override
  public boolean referenceExists(String referenceNumber) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM cod_deposits
            WHERE reference_number = ? AND deleted_at IS NULL
            """,
            Long.class,
            referenceNumber);
    return java.util.Objects.requireNonNullElse(n, 0L) > 0;
  }

  @Override
  public long sumDepositedToday(UUID riderId, Instant dayStart, Instant dayEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0) FROM cod_deposits
            WHERE rider_id = ? AND status = 'CONFIRMED'
              AND deleted_at IS NULL
              AND COALESCE(confirmed_at, deposited_at, submitted_at) >= ?
              AND COALESCE(confirmed_at, deposited_at, submitted_at) < ?
            """,
            Long.class,
            riderId,
            Timestamp.from(dayStart),
            Timestamp.from(dayEnd));
    return java.util.Objects.requireNonNullElse(n, 0L);
  }

  @Override
  public long sumDepositedTodayAll(Instant dayStart, Instant dayEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0) FROM cod_deposits
            WHERE status = 'CONFIRMED' AND deleted_at IS NULL
              AND COALESCE(confirmed_at, deposited_at, submitted_at) >= ?
              AND COALESCE(confirmed_at, deposited_at, submitted_at) < ?
            """,
            Long.class,
            Timestamp.from(dayStart),
            Timestamp.from(dayEnd));
    return java.util.Objects.requireNonNullElse(n, 0L);
  }

  @Override
  public long sumPendingDepositRequests(Instant dayStart, Instant dayEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(amount_paise), 0) FROM cod_deposits
            WHERE status = 'PENDING_CONFIRMATION' AND deleted_at IS NULL
            """,
            Long.class);
    return java.util.Objects.requireNonNullElse(n, 0L);
  }

  @Override
  public int countFloatRiskRiders(long limitPaise) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(1) FROM riders
            WHERE deleted_at IS NULL AND cod_in_hand_paise > ?
            """,
            Long.class,
            limitPaise);
    return java.util.Objects.requireNonNullElse(n, 0L).intValue();
  }

  @Override
  public long sumCodInHandAll() {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(cod_in_hand_paise), 0) FROM riders
            WHERE deleted_at IS NULL
            """,
            Long.class);
    return java.util.Objects.requireNonNullElse(n, 0L);
  }

  @Override
  public Instant lastConfirmedDepositAt(UUID riderId) {
    List<Timestamp> rows =
        jdbc.query(
            """
            SELECT confirmed_at FROM cod_deposits
            WHERE rider_id = ? AND status = 'CONFIRMED' AND deleted_at IS NULL
              AND confirmed_at IS NOT NULL
            ORDER BY confirmed_at DESC LIMIT 1
            """,
            (rs, i) -> rs.getTimestamp("confirmed_at"),
            riderId);
    return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toInstant();
  }

  @Override
  public BoardPage listBoard(UUID zoneId, boolean riskOnly, long limitPaise, int page, int limit) {
    StringBuilder where =
        new StringBuilder(
            """
            WHERE r.deleted_at IS NULL
              AND r.status NOT IN ('PENDING_KYC')
              AND (r.cod_in_hand_paise > 0 OR EXISTS (
                SELECT 1 FROM cod_collections c WHERE c.rider_id = r.id
              ) OR EXISTS (
                SELECT 1 FROM cod_deposits d
                WHERE d.rider_id = r.id AND d.deleted_at IS NULL
              ))
            """);
    List<Object> args = new ArrayList<>();
    if (zoneId != null) {
      where.append(" AND COALESCE(r.current_zone_id, r.primary_zone_id) = ? ");
      args.add(zoneId);
    }
    if (riskOnly) {
      where.append(" AND r.cod_in_hand_paise > ? ");
      args.add(limitPaise);
    }

    Long total =
        java.util.Objects.requireNonNullElse(
            jdbc.queryForObject(
                "SELECT COUNT(1) FROM riders r " + where, Long.class, args.toArray()),
            0L);
    int offset = (page - 1) * limit;
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add(offset);

    String order =
        riskOnly
            ? " ORDER BY r.cod_in_hand_paise DESC "
            : " ORDER BY r.cod_in_hand_paise DESC, r.name ASC ";

    List<CodBoardRow> rows =
        jdbc.query(
            """
            SELECT r.id, r.name, COALESCE(r.current_zone_id, r.primary_zone_id) AS zone_id,
                   z.name AS zone_name, r.cod_in_hand_paise
            FROM riders r
            LEFT JOIN zones z ON z.id = COALESCE(r.current_zone_id, r.primary_zone_id)
            """
                + where
                + order
                + " LIMIT ? OFFSET ?",
            (rs, i) ->
                new CodBoardRow(
                    (UUID) rs.getObject("id"),
                    rs.getString("name"),
                    (UUID) rs.getObject("zone_id"),
                    rs.getString("zone_name"),
                    rs.getLong("cod_in_hand_paise"),
                    0L,
                    0L,
                    0,
                    null),
            pageArgs.toArray());
    return new BoardPage(rows, total);
  }

  @Override
  public List<CodBoardRow> allForReport(long limitPaise) {
    return jdbc.query(
        """
        SELECT r.id, r.name, COALESCE(r.current_zone_id, r.primary_zone_id) AS zone_id,
               z.name AS zone_name, r.cod_in_hand_paise
        FROM riders r
        LEFT JOIN zones z ON z.id = COALESCE(r.current_zone_id, r.primary_zone_id)
        WHERE r.deleted_at IS NULL AND r.cod_in_hand_paise > 0
        ORDER BY r.cod_in_hand_paise DESC
        """,
        (rs, i) ->
            new CodBoardRow(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                (UUID) rs.getObject("zone_id"),
                rs.getString("zone_name"),
                rs.getLong("cod_in_hand_paise"),
                0L,
                0L,
                0,
                null));
  }

  private DepositRecord map(ResultSet rs, int rowNum) throws SQLException {
    return new DepositRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rider_id"),
        rs.getLong("amount_paise"),
        rs.getString("deposit_mode"),
        rs.getString("reference_number"),
        rs.getString("status"),
        instant(rs.getTimestamp("submitted_at")),
        instant(rs.getTimestamp("confirmed_at")),
        (UUID) rs.getObject("confirmed_by"),
        instant(rs.getTimestamp("deposited_at")),
        rs.getString("notes"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
