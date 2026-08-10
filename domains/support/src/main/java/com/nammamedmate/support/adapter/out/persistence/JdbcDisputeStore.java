package com.nammamedmate.support.adapter.out.persistence;

import com.nammamedmate.support.application.port.out.DisputeStore;
import com.nammamedmate.support.domain.Dispute;
import com.nammamedmate.support.domain.DisputeEvent;
import com.nammamedmate.support.domain.DisputeStatus;
import com.nammamedmate.support.domain.DisputeType;
import com.nammamedmate.support.domain.LiableParty;
import com.nammamedmate.support.domain.RefundDestination;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcDisputeStore implements DisputeStore {

  private static final String SELECT =
      """
      SELECT id, dispute_id, order_id, customer_id, dispute_type, description, evidence_urls,
             status, liable_party, refund_amount_paise, refund_to, resolution_notes,
             rejection_reason, investigated_by, resolved_at, resolution_sla_at,
             recommended_liable_party, auto_processed, refund_txn_id,
             created_at, updated_at, deleted_at
      FROM support_disputes
      """;

  private final JdbcTemplate jdbc;

  public JdbcDisputeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int nextDisputeSeq(LocalDate day) {
    jdbc.update(
        """
        INSERT INTO support_dispute_id_seq (day_key, last_seq)
        VALUES (?, 0)
        ON CONFLICT (day_key) DO NOTHING
        """,
        day);
    Integer seq =
        jdbc.queryForObject(
            """
            UPDATE support_dispute_id_seq
            SET last_seq = last_seq + 1
            WHERE day_key = ?
            RETURNING last_seq
            """,
            Integer.class,
            day);
    return seq == null ? 1 : seq;
  }

  @Override
  public Dispute insert(Dispute dispute) {
    jdbc.update(
        """
        INSERT INTO support_disputes (
          id, dispute_id, order_id, customer_id, dispute_type, description, evidence_urls,
          status, liable_party, refund_amount_paise, refund_to, resolution_notes,
          rejection_reason, investigated_by, resolved_at, resolution_sla_at,
          recommended_liable_party, auto_processed, refund_txn_id,
          created_at, updated_at, deleted_at
        ) VALUES (?,?,?,?,?,?,?::text[],?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        dispute.id(),
        dispute.disputeId(),
        dispute.orderId(),
        dispute.customerId(),
        dispute.disputeType().name(),
        dispute.description(),
        toTextArrayLiteral(dispute.evidenceUrls()),
        dispute.status().name(),
        dispute.liableParty() == null ? null : dispute.liableParty().name(),
        dispute.refundAmountPaise(),
        dispute.refundTo() == null ? null : dispute.refundTo().name(),
        dispute.resolutionNotes(),
        dispute.rejectionReason(),
        dispute.investigatedBy(),
        ts(dispute.resolvedAt()),
        Timestamp.from(dispute.resolutionSlaAt()),
        dispute.recommendedLiableParty().name(),
        dispute.autoProcessed(),
        dispute.refundTxnId(),
        Timestamp.from(dispute.createdAt()),
        Timestamp.from(dispute.updatedAt()),
        ts(dispute.deletedAt()));
    return dispute;
  }

  @Override
  public void update(Dispute dispute) {
    jdbc.update(
        """
        UPDATE support_disputes SET
          status = ?, liable_party = ?, refund_amount_paise = ?, refund_to = ?,
          resolution_notes = ?, rejection_reason = ?, investigated_by = ?,
          resolved_at = ?, auto_processed = ?, refund_txn_id = ?, updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        dispute.status().name(),
        dispute.liableParty() == null ? null : dispute.liableParty().name(),
        dispute.refundAmountPaise(),
        dispute.refundTo() == null ? null : dispute.refundTo().name(),
        dispute.resolutionNotes(),
        dispute.rejectionReason(),
        dispute.investigatedBy(),
        ts(dispute.resolvedAt()),
        dispute.autoProcessed(),
        dispute.refundTxnId(),
        Timestamp.from(dispute.updatedAt()),
        dispute.id());
  }

  @Override
  public Optional<Dispute> findById(UUID id) {
    List<Dispute> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Dispute> findByOrderId(UUID orderId) {
    List<Dispute> rows =
        jdbc.query(
            SELECT + " WHERE order_id = ? AND deleted_at IS NULL", (rs, i) -> map(rs), orderId);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Dispute> findBannerDispute(UUID orderId) {
    List<Dispute> rows =
        jdbc.query(
            SELECT
                + """
                 WHERE order_id = ? AND deleted_at IS NULL
                   AND status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')
                 ORDER BY created_at DESC
                 LIMIT 1
                """,
            (rs, i) -> map(rs),
            orderId);
    return rows.stream().findFirst();
  }

  @Override
  public List<Dispute> list(ListFilter filter) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, filter);
    sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
    args.add(filter.limit());
    args.add(filter.offset());
    return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
  }

  @Override
  public long count(ListFilter filter) {
    StringBuilder sql =
        new StringBuilder("SELECT COUNT(*) FROM support_disputes WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, filter);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0 : n;
  }

  @Override
  public List<Dispute> listForCustomer(UUID customerId, int offset, int limit) {
    return jdbc.query(
        SELECT
            + " WHERE customer_id = ? AND deleted_at IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?",
        (rs, i) -> map(rs),
        customerId,
        limit,
        offset);
  }

  @Override
  public long countForCustomer(UUID customerId) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM support_disputes WHERE customer_id = ? AND deleted_at IS NULL",
            Long.class,
            customerId);
    return n == null ? 0 : n;
  }

  @Override
  public Chips chips(Instant now) {
    Long open =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_disputes
            WHERE deleted_at IS NULL AND status IN ('OPEN', 'INVESTIGATING')
            """,
            Long.class);
    Long exposurePaise =
        jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(o.total_payable_paise), 0)
            FROM support_disputes d
            JOIN orders o ON o.id = d.order_id
            WHERE d.deleted_at IS NULL AND d.status IN ('OPEN', 'INVESTIGATING')
            """,
            Long.class);
    Double avgHours =
        jdbc.queryForObject(
            """
            SELECT COALESCE(
              ROUND(AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600.0)::numeric, 1),
              0
            )
            FROM support_disputes
            WHERE deleted_at IS NULL AND resolved_at IS NOT NULL
            """,
            Double.class);
    Long resolvedToday =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_disputes
            WHERE deleted_at IS NULL AND status = 'RESOLVED'
              AND resolved_at >= date_trunc('day', ? AT TIME ZONE 'UTC')
            """,
            Long.class,
            Timestamp.from(now));
    long exposureRs = exposurePaise == null ? 0L : exposurePaise / 100L;
    return new Chips(
        open == null ? 0 : open,
        exposureRs,
        avgHours == null ? 0.0 : avgHours,
        resolvedToday == null ? 0 : resolvedToday);
  }

  @Override
  public DisputeEvent insertEvent(DisputeEvent event) {
    jdbc.update(
        """
        INSERT INTO support_dispute_events (
          id, dispute_id, event_type, actor_id, actor_name, notes, created_at
        ) VALUES (?,?,?,?,?,?,?)
        """,
        event.id(),
        event.disputeId(),
        event.eventType(),
        event.actorId(),
        event.actorName(),
        event.notes(),
        Timestamp.from(event.createdAt()));
    return event;
  }

  @Override
  public List<DisputeEvent> listEvents(UUID disputeId) {
    return jdbc.query(
        """
        SELECT id, dispute_id, event_type, actor_id, actor_name, notes, created_at
        FROM support_dispute_events
        WHERE dispute_id = ?
        ORDER BY created_at ASC
        """,
        (rs, i) ->
            new DisputeEvent(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("dispute_id"),
                rs.getString("event_type"),
                (UUID) rs.getObject("actor_id"),
                rs.getString("actor_name"),
                rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant()),
        disputeId);
  }

  @Override
  public List<Dispute> findSlaBreachedOpen(Instant now, int limit) {
    return jdbc.query(
        SELECT
            + """
             WHERE deleted_at IS NULL
               AND status IN ('OPEN', 'INVESTIGATING')
               AND resolution_sla_at < ?
             ORDER BY resolution_sla_at ASC
             LIMIT ?
            """,
        (rs, i) -> map(rs),
        Timestamp.from(now),
        limit);
  }

  private static void appendFilters(StringBuilder sql, List<Object> args, ListFilter filter) {
    if (filter.status() != null) {
      sql.append(" AND status = ?");
      args.add(filter.status().name());
    }
    if (filter.liableParty() != null) {
      sql.append(" AND liable_party = ?");
      args.add(filter.liableParty().name());
    }
    if (filter.disputeType() != null) {
      sql.append(" AND dispute_type = ?");
      args.add(filter.disputeType().name());
    }
  }

  private Dispute map(ResultSet rs) throws SQLException {
    return new Dispute(
        (UUID) rs.getObject("id"),
        rs.getString("dispute_id"),
        (UUID) rs.getObject("order_id"),
        (UUID) rs.getObject("customer_id"),
        DisputeType.valueOf(rs.getString("dispute_type")),
        rs.getString("description"),
        toStringList(rs.getArray("evidence_urls")),
        DisputeStatus.valueOf(rs.getString("status")),
        enumOrNull(rs.getString("liable_party"), LiableParty.class),
        (Long) rs.getObject("refund_amount_paise"),
        enumOrNull(rs.getString("refund_to"), RefundDestination.class),
        rs.getString("resolution_notes"),
        rs.getString("rejection_reason"),
        (UUID) rs.getObject("investigated_by"),
        instantOrNull(rs.getTimestamp("resolved_at")),
        rs.getTimestamp("resolution_sla_at").toInstant(),
        LiableParty.valueOf(rs.getString("recommended_liable_party")),
        rs.getBoolean("auto_processed"),
        rs.getString("refund_txn_id"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instantOrNull(rs.getTimestamp("deleted_at")));
  }

  private static <E extends Enum<E>> E enumOrNull(String raw, Class<E> type) {
    return raw == null ? null : Enum.valueOf(type, raw);
  }

  private static Instant instantOrNull(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static List<String> toStringList(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object[] objs = (Object[]) array.getArray();
    if (objs == null || objs.length == 0) {
      return List.of();
    }
    List<String> out = new ArrayList<>(objs.length);
    for (Object o : objs) {
      if (o != null) {
        out.add(o.toString());
      }
    }
    return out;
  }

  /** Postgres text[] literal for JDBC without Connection.createArrayOf. */
  static String toTextArrayLiteral(List<String> values) {
    if (values == null || values.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      String v =
          values.get(i) == null ? "" : values.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
      sb.append('"').append(v).append('"');
    }
    sb.append('}');
    return sb.toString();
  }
}
