package com.nammamedmate.rider.adapter.out.persistence;

import com.nammamedmate.rider.application.port.out.CodCollectionStore;
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
public class JdbcCodCollectionStore implements CodCollectionStore {

  private final JdbcTemplate jdbc;

  public JdbcCodCollectionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(CollectionRecord row) {
    jdbc.update(
        """
        INSERT INTO cod_collections (
          id, rider_id, order_id, cod_amount_paise, collected_at, deposit_id, is_deposited, created_at
        ) VALUES (?,?,?,?,?,?,?,?)
        """,
        row.id(),
        row.riderId(),
        row.orderId(),
        row.codAmountPaise(),
        Timestamp.from(row.collectedAt()),
        row.depositId(),
        row.deposited(),
        Timestamp.from(row.createdAt()));
  }

  @Override
  public Optional<CollectionRecord> findByOrderId(UUID orderId) {
    List<CollectionRecord> rows =
        jdbc.query("SELECT * FROM cod_collections WHERE order_id = ?", this::map, orderId);
    return rows.stream().findFirst();
  }

  @Override
  public List<CollectionView> recentForRider(UUID riderId, int limit) {
    return jdbc.query(
        """
        SELECT c.order_id, o.order_number, c.cod_amount_paise, c.collected_at, c.is_deposited
        FROM cod_collections c
        LEFT JOIN orders o ON o.id = c.order_id
        WHERE c.rider_id = ?
        ORDER BY c.collected_at DESC
        LIMIT ?
        """,
        (rs, i) ->
            new CollectionView(
                (UUID) rs.getObject("order_id"),
                rs.getString("order_number"),
                rs.getLong("cod_amount_paise"),
                rs.getTimestamp("collected_at").toInstant(),
                rs.getBoolean("is_deposited")),
        riderId,
        limit);
  }

  @Override
  public long sumCollectedToday(UUID riderId, Instant dayStart, Instant dayEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(cod_amount_paise), 0) FROM cod_collections
            WHERE rider_id = ? AND collected_at >= ? AND collected_at < ?
            """,
            Long.class,
            riderId,
            Timestamp.from(dayStart),
            Timestamp.from(dayEnd));
    return java.util.Objects.requireNonNullElse(n, 0L);
  }

  @Override
  public long sumCollectedTodayAll(Instant dayStart, Instant dayEnd) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(cod_amount_paise), 0) FROM cod_collections
            WHERE collected_at >= ? AND collected_at < ?
            """,
            Long.class,
            Timestamp.from(dayStart),
            Timestamp.from(dayEnd));
    return java.util.Objects.requireNonNullElse(n, 0L);
  }

  @Override
  public long markDepositedFifo(UUID riderId, UUID depositId, long amountPaise) {
    if (amountPaise <= 0) {
      return 0L;
    }
    List<CollectionRecord> open =
        jdbc.query(
            """
            SELECT * FROM cod_collections
            WHERE rider_id = ? AND is_deposited = FALSE
            ORDER BY collected_at ASC
            """,
            this::map,
            riderId);
    long remaining = amountPaise;
    long applied = 0L;
    for (CollectionRecord c : open) {
      if (remaining <= 0) {
        break;
      }
      // ponytail: ceiling = whole-collection FIFO (no partial split); upgrade: split lines.
      if (c.codAmountPaise() > remaining) {
        break;
      }
      jdbc.update(
          """
          UPDATE cod_collections
          SET is_deposited = TRUE, deposit_id = ?
          WHERE id = ? AND is_deposited = FALSE
          """,
          depositId,
          c.id());
      remaining -= c.codAmountPaise();
      applied += c.codAmountPaise();
    }
    return applied;
  }

  private CollectionRecord map(ResultSet rs, int rowNum) throws SQLException {
    Timestamp collected = rs.getTimestamp("collected_at");
    Timestamp created = rs.getTimestamp("created_at");
    return new CollectionRecord(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("rider_id"),
        (UUID) rs.getObject("order_id"),
        rs.getLong("cod_amount_paise"),
        collected.toInstant(),
        (UUID) rs.getObject("deposit_id"),
        rs.getBoolean("is_deposited"),
        created.toInstant());
  }
}
