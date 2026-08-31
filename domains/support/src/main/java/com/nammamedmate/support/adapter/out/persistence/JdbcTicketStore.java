package com.nammamedmate.support.adapter.out.persistence;

import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.domain.SenderType;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketMessage;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
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
public class JdbcTicketStore implements TicketStore {

  private static final String SELECT =
      """
      SELECT id, ticket_id, customer_id, pharmacy_id, order_id, category, subject, status,
             priority, sla_level, sla_due_at, first_response_due_at, resolution_due_at,
             assigned_agent_id, channel, first_response_at,
             resolved_at, resolution_summary, csat_score, csat_feedback,
             csat_survey_scheduled_at, csat_survey_sent_at, created_by_admin_id,
             sla_paused_at, sla_l4_notified_at,
             deleted_at, created_at, updated_at
      FROM support_tickets
      """;

  private final JdbcTemplate jdbc;

  public JdbcTicketStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int nextTicketSeq(LocalDate day) {
    jdbc.update(
        """
        INSERT INTO support_ticket_id_seq (day_key, last_seq)
        VALUES (?, 0)
        ON CONFLICT (day_key) DO NOTHING
        """,
        day);
    Integer seq =
        jdbc.queryForObject(
            """
            UPDATE support_ticket_id_seq
            SET last_seq = last_seq + 1
            WHERE day_key = ?
            RETURNING last_seq
            """,
            Integer.class,
            day);
    return seq == null ? 1 : seq;
  }

  @Override
  public Ticket insert(Ticket ticket) {
    jdbc.update(
        """
        INSERT INTO support_tickets (
          id, ticket_id, customer_id, pharmacy_id, order_id, category, subject, status,
          priority, sla_level, sla_due_at, first_response_due_at, resolution_due_at,
          assigned_agent_id, channel, first_response_at,
          resolved_at, resolution_summary, csat_score, csat_feedback,
          csat_survey_scheduled_at, csat_survey_sent_at, created_by_admin_id,
          sla_paused_at, sla_l4_notified_at,
          deleted_at, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        ticket.id(),
        ticket.ticketId(),
        ticket.customerId(),
        ticket.pharmacyId(),
        ticket.orderId(),
        ticket.category().name(),
        ticket.subject(),
        ticket.status().name(),
        ticket.priority().name(),
        ticket.slaLevel().name(),
        Timestamp.from(ticket.slaDueAt()),
        Timestamp.from(ticket.firstResponseDueAt()),
        Timestamp.from(ticket.resolutionDueAt()),
        ticket.assignedAgentId(),
        ticket.channel().name(),
        ts(ticket.firstResponseAt()),
        ts(ticket.resolvedAt()),
        ticket.resolutionSummary(),
        ticket.csatScore(),
        ticket.csatFeedback(),
        ts(ticket.csatSurveyScheduledAt()),
        ts(ticket.csatSurveySentAt()),
        ticket.createdByAdminId(),
        ts(ticket.slaPausedAt()),
        ts(ticket.slaL4NotifiedAt()),
        ts(ticket.deletedAt()),
        Timestamp.from(ticket.createdAt()),
        Timestamp.from(ticket.updatedAt()));
    return ticket;
  }

  @Override
  public void update(Ticket ticket) {
    jdbc.update(
        """
        UPDATE support_tickets SET
          status = ?, priority = ?, sla_level = ?, sla_due_at = ?,
          first_response_due_at = ?, resolution_due_at = ?,
          assigned_agent_id = ?,
          first_response_at = ?, resolved_at = ?, resolution_summary = ?,
          csat_score = ?, csat_feedback = ?, csat_survey_scheduled_at = ?,
          csat_survey_sent_at = ?, sla_paused_at = ?, sla_l4_notified_at = ?,
          updated_at = ?
        WHERE id = ? AND deleted_at IS NULL
        """,
        ticket.status().name(),
        ticket.priority().name(),
        ticket.slaLevel().name(),
        Timestamp.from(ticket.slaDueAt()),
        Timestamp.from(ticket.firstResponseDueAt()),
        Timestamp.from(ticket.resolutionDueAt()),
        ticket.assignedAgentId(),
        ts(ticket.firstResponseAt()),
        ts(ticket.resolvedAt()),
        ticket.resolutionSummary(),
        ticket.csatScore(),
        ticket.csatFeedback(),
        ts(ticket.csatSurveyScheduledAt()),
        ts(ticket.csatSurveySentAt()),
        ts(ticket.slaPausedAt()),
        ts(ticket.slaL4NotifiedAt()),
        Timestamp.from(ticket.updatedAt()),
        ticket.id());
  }

  @Override
  public Optional<Ticket> findById(UUID id) {
    List<Ticket> rows =
        jdbc.query(SELECT + " WHERE id = ? AND deleted_at IS NULL", (rs, i) -> mapTicket(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Optional<Ticket> findByTicketId(String ticketId) {
    List<Ticket> rows =
        jdbc.query(
            SELECT + " WHERE ticket_id = ? AND deleted_at IS NULL",
            (rs, i) -> mapTicket(rs),
            ticketId);
    return rows.stream().findFirst();
  }

  @Override
  public List<Ticket> list(ListFilter filter) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, filter);
    sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
    args.add(filter.limit());
    args.add(filter.offset());
    return jdbc.query(sql.toString(), (rs, i) -> mapTicket(rs), args.toArray());
  }

  @Override
  public long count(ListFilter filter) {
    StringBuilder sql =
        new StringBuilder("SELECT COUNT(*) FROM support_tickets WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    appendFilters(sql, args, filter);
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public List<Ticket> listForPharmacy(UUID pharmacyId, int offset, int limit) {
    return jdbc.query(
        SELECT
            + " WHERE deleted_at IS NULL AND pharmacy_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
        (rs, i) -> mapTicket(rs),
        pharmacyId,
        limit,
        offset);
  }

  @Override
  public long countForPharmacy(UUID pharmacyId) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM support_tickets WHERE deleted_at IS NULL AND pharmacy_id = ?",
            Long.class,
            pharmacyId);
    return n == null ? 0L : n;
  }

  @Override
  public Chips chips(Instant now) {
    Long open =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM support_tickets WHERE deleted_at IS NULL AND status = 'OPEN'",
            Long.class);
    Long inProgress =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_tickets
            WHERE deleted_at IS NULL AND status IN ('IN_PROGRESS', 'AWAITING_CUSTOMER')
            """,
            Long.class);
    Long breached =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_tickets
            WHERE deleted_at IS NULL
              AND first_response_at IS NULL
              AND status NOT IN ('AWAITING_CUSTOMER', 'RESOLVED', 'CLOSED')
              AND COALESCE(first_response_due_at, sla_due_at) < ?
            """,
            Long.class,
            Timestamp.from(now));
    Double csat =
        jdbc.queryForObject(
            """
            SELECT COALESCE(
              ROUND(100.0 * AVG(CASE WHEN csat_score >= 4 THEN 1.0 ELSE 0.0 END), 1),
              0
            )
            FROM support_tickets
            WHERE deleted_at IS NULL AND csat_score IS NOT NULL
            """,
            Double.class);
    return new Chips(
        open == null ? 0 : open,
        inProgress == null ? 0 : inProgress,
        breached == null ? 0 : breached,
        0,
        0,
        csat == null ? 0.0 : csat);
  }

  @Override
  public TicketMessage insertMessage(TicketMessage message) {
    jdbc.update(
        """
        INSERT INTO support_ticket_messages (
          id, ticket_id, sender_type, sender_id, sender_name, message,
          is_internal_note, canned_response_id, attachments, created_at
        ) VALUES (?,?,?,?,?,?,?,?,?::text[],?)
        """,
        message.id(),
        message.ticketId(),
        message.senderType().name(),
        message.senderId(),
        message.senderName(),
        message.message(),
        message.internalNote(),
        message.cannedResponseId(),
        toTextArrayLiteral(message.attachments()),
        Timestamp.from(message.createdAt()));
    return message;
  }

  @Override
  public List<TicketMessage> listMessages(UUID ticketId) {
    return jdbc.query(
        """
        SELECT id, ticket_id, sender_type, sender_id, sender_name, message,
               is_internal_note, canned_response_id, attachments, created_at
        FROM support_ticket_messages
        WHERE ticket_id = ?
        ORDER BY created_at ASC
        """,
        (rs, i) -> mapMessage(rs),
        ticketId);
  }

  @Override
  public int countOpenAssigned(UUID agentId) {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_tickets
            WHERE deleted_at IS NULL
              AND assigned_agent_id = ?
              AND status IN ('OPEN', 'IN_PROGRESS', 'AWAITING_CUSTOMER')
            """,
            Integer.class,
            agentId);
    return n == null ? 0 : n;
  }

  @Override
  public int countUnassignedOpen() {
    Integer n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_tickets
            WHERE deleted_at IS NULL
              AND assigned_agent_id IS NULL
              AND status IN ('OPEN', 'IN_PROGRESS')
            """,
            Integer.class);
    return n == null ? 0 : n;
  }

  @Override
  public List<Ticket> listAssignedOpen(UUID agentId) {
    return jdbc.query(
        SELECT
            + """
             WHERE deleted_at IS NULL
               AND assigned_agent_id = ?
               AND status IN ('OPEN', 'IN_PROGRESS', 'AWAITING_CUSTOMER')
             ORDER BY created_at ASC
            """,
        (rs, i) -> mapTicket(rs),
        agentId);
  }

  @Override
  public List<Ticket> listResolvedByAgent(
      UUID agentId, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        SELECT
            + """
             WHERE deleted_at IS NULL
               AND assigned_agent_id = ?
               AND status IN ('RESOLVED', 'CLOSED')
               AND resolved_at IS NOT NULL
               AND resolved_at >= ?
               AND resolved_at < ?
             ORDER BY resolved_at ASC
            """,
        (rs, i) -> mapTicket(rs),
        agentId,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public List<Ticket> findDueCsatSurveys(Instant now, int limit) {
    return jdbc.query(
        SELECT
            + """
             WHERE deleted_at IS NULL
               AND status = 'RESOLVED'
               AND csat_survey_scheduled_at IS NOT NULL
               AND csat_survey_scheduled_at <= ?
               AND csat_survey_sent_at IS NULL
             ORDER BY csat_survey_scheduled_at ASC
             LIMIT ?
            """,
        (rs, i) -> mapTicket(rs),
        Timestamp.from(now),
        limit);
  }

  @Override
  public List<Ticket> findSlaBreachedWithoutFirstResponse(Instant now, int limit) {
    return jdbc.query(
        SELECT
            + """
             WHERE deleted_at IS NULL
               AND first_response_at IS NULL
               AND status NOT IN ('AWAITING_CUSTOMER', 'RESOLVED', 'CLOSED')
               AND COALESCE(first_response_due_at, sla_due_at) < ?
             ORDER BY COALESCE(first_response_due_at, sla_due_at) ASC
             LIMIT ?
            """,
        (rs, i) -> mapTicket(rs),
        Timestamp.from(now),
        limit);
  }

  @Override
  public List<Ticket> findOpenForSlaScan(int limit) {
    return jdbc.query(
        SELECT
            + """
             WHERE deleted_at IS NULL
               AND status NOT IN ('AWAITING_CUSTOMER', 'RESOLVED', 'CLOSED')
               AND sla_paused_at IS NULL
             ORDER BY created_at ASC
             LIMIT ?
            """,
        (rs, i) -> mapTicket(rs),
        limit);
  }

  @Override
  public ResolvedSlaStats resolvedSlaStats() {
    Long total =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_tickets
            WHERE deleted_at IS NULL AND status IN ('RESOLVED', 'CLOSED') AND resolved_at IS NOT NULL
            """,
            Long.class);
    Long within =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM support_tickets
            WHERE deleted_at IS NULL
              AND status IN ('RESOLVED', 'CLOSED')
              AND resolved_at IS NOT NULL
              AND resolved_at <= resolution_due_at
              AND (first_response_at IS NULL OR first_response_at <= COALESCE(first_response_due_at, sla_due_at))
            """,
            Long.class);
    return new ResolvedSlaStats(within == null ? 0 : within, total == null ? 0 : total);
  }

  private static void appendFilters(StringBuilder sql, List<Object> args, ListFilter filter) {
    if (filter.status() != null) {
      sql.append(" AND status = ?");
      args.add(filter.status().name());
    }
    if (filter.priority() != null) {
      sql.append(" AND priority = ?");
      args.add(filter.priority().name());
    }
    if (filter.category() != null) {
      sql.append(" AND category = ?");
      args.add(filter.category().name());
    }
    if (filter.channel() != null) {
      sql.append(" AND channel = ?");
      args.add(filter.channel().name());
    }
    if (filter.assignedAgentId() != null) {
      sql.append(" AND assigned_agent_id = ?");
      args.add(filter.assignedAgentId());
    }
    if (filter.q() != null) {
      sql.append(" AND (ticket_id ILIKE ? OR subject ILIKE ?)");
      String like = "%" + filter.q() + "%";
      args.add(like);
      args.add(like);
    }
  }

  private static Ticket mapTicket(ResultSet rs) throws SQLException {
    Instant slaDue = rs.getTimestamp("sla_due_at").toInstant();
    Instant frDue = instant(rs, "first_response_due_at");
    Instant resDue = instant(rs, "resolution_due_at");
    return new Ticket(
        (UUID) rs.getObject("id"),
        rs.getString("ticket_id"),
        (UUID) rs.getObject("customer_id"),
        (UUID) rs.getObject("pharmacy_id"),
        (UUID) rs.getObject("order_id"),
        TicketCategory.valueOf(rs.getString("category")),
        rs.getString("subject"),
        TicketStatus.valueOf(rs.getString("status")),
        TicketPriority.valueOf(rs.getString("priority")),
        SlaLevel.valueOf(rs.getString("sla_level")),
        slaDue,
        frDue == null ? slaDue : frDue,
        resDue == null ? slaDue : resDue,
        (UUID) rs.getObject("assigned_agent_id"),
        TicketChannel.valueOf(rs.getString("channel")),
        instant(rs, "first_response_at"),
        instant(rs, "resolved_at"),
        rs.getString("resolution_summary"),
        (Integer) rs.getObject("csat_score"),
        rs.getString("csat_feedback"),
        instant(rs, "csat_survey_scheduled_at"),
        instant(rs, "csat_survey_sent_at"),
        (UUID) rs.getObject("created_by_admin_id"),
        instant(rs, "sla_paused_at"),
        instant(rs, "sla_l4_notified_at"),
        instant(rs, "deleted_at"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static TicketMessage mapMessage(ResultSet rs) throws SQLException {
    return new TicketMessage(
        (UUID) rs.getObject("id"),
        (UUID) rs.getObject("ticket_id"),
        SenderType.valueOf(rs.getString("sender_type")),
        (UUID) rs.getObject("sender_id"),
        rs.getString("sender_name"),
        rs.getString("message"),
        rs.getBoolean("is_internal_note"),
        (UUID) rs.getObject("canned_response_id"),
        readTextArray(rs.getArray("attachments")),
        rs.getTimestamp("created_at").toInstant());
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp ts = rs.getTimestamp(col);
    return ts == null ? null : ts.toInstant();
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static String toTextArrayLiteral(List<String> values) {
    if (values.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      String v = values.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
      sb.append('"').append(v).append('"');
    }
    sb.append('}');
    return sb.toString();
  }

  private static List<String> readTextArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] strings) {
      return List.of(strings);
    }
    if (raw instanceof Object[] objects) {
      List<String> out = new ArrayList<>(objects.length);
      for (Object o : objects) {
        if (o != null) {
          out.add(o.toString());
        }
      }
      return out;
    }
    return List.of();
  }
}
