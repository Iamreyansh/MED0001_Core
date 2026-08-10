package com.nammamedmate.marketing.adapter.out.persistence;

import com.nammamedmate.marketing.application.port.out.CampaignStore;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import com.nammamedmate.marketing.domain.CampaignTimelineEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcCampaignStore implements CampaignStore {

  private static final String SELECT =
      """
      SELECT id, name, channel, segment_id, message_template_id, subject, body, cta_label, cta_link,
             scheduled_at, launched_at, completed_at, paused_at,
             estimated_cost_paise, budget_cap_paise, actual_spend_paise,
             sent_count, delivered_count, opened_count, clicked_count, converted_count,
             revenue_attributed_paise, audience_snapshot_count, status,
             created_by, created_at, updated_at
      FROM campaigns
      """;

  private final JdbcTemplate jdbc;

  public JdbcCampaignStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Campaign insert(Campaign c) {
    jdbc.update(
        """
        INSERT INTO campaigns (
          id, name, channel, segment_id, message_template_id, subject, body, cta_label, cta_link,
          scheduled_at, launched_at, completed_at, paused_at,
          estimated_cost_paise, budget_cap_paise, actual_spend_paise,
          sent_count, delivered_count, opened_count, clicked_count, converted_count,
          revenue_attributed_paise, audience_snapshot_count, status,
          created_by, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        c.id(),
        c.name(),
        c.channel().name(),
        c.segmentId(),
        c.messageTemplateId(),
        c.subject(),
        c.body(),
        c.ctaLabel(),
        c.ctaLink(),
        ts(c.scheduledAt()),
        ts(c.launchedAt()),
        ts(c.completedAt()),
        ts(c.pausedAt()),
        c.estimatedCostPaise(),
        c.budgetCapPaise(),
        c.actualSpendPaise(),
        c.sentCount(),
        c.deliveredCount(),
        c.openedCount(),
        c.clickedCount(),
        c.convertedCount(),
        c.revenueAttributedPaise(),
        c.audienceSnapshotCount(),
        c.status().name(),
        c.createdBy(),
        Timestamp.from(c.createdAt()),
        Timestamp.from(c.updatedAt()));
    return c;
  }

  @Override
  public Optional<Campaign> findById(UUID id) {
    List<Campaign> rows = jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> map(rs), id);
    return rows.stream().findFirst();
  }

  @Override
  public Campaign update(Campaign c) {
    jdbc.update(
        """
        UPDATE campaigns SET
          name = ?, channel = ?, segment_id = ?, message_template_id = ?, subject = ?, body = ?,
          cta_label = ?, cta_link = ?, scheduled_at = ?, launched_at = ?, completed_at = ?,
          paused_at = ?, estimated_cost_paise = ?, budget_cap_paise = ?, actual_spend_paise = ?,
          sent_count = ?, delivered_count = ?, opened_count = ?, clicked_count = ?,
          converted_count = ?, revenue_attributed_paise = ?, audience_snapshot_count = ?,
          status = ?, updated_at = ?
        WHERE id = ?
        """,
        c.name(),
        c.channel().name(),
        c.segmentId(),
        c.messageTemplateId(),
        c.subject(),
        c.body(),
        c.ctaLabel(),
        c.ctaLink(),
        ts(c.scheduledAt()),
        ts(c.launchedAt()),
        ts(c.completedAt()),
        ts(c.pausedAt()),
        c.estimatedCostPaise(),
        c.budgetCapPaise(),
        c.actualSpendPaise(),
        c.sentCount(),
        c.deliveredCount(),
        c.openedCount(),
        c.clickedCount(),
        c.convertedCount(),
        c.revenueAttributedPaise(),
        c.audienceSnapshotCount(),
        c.status().name(),
        Timestamp.from(c.updatedAt()),
        c.id());
    return c;
  }

  @Override
  public List<Campaign> list(
      CampaignStatus status,
      CampaignChannel channel,
      String sort,
      String order,
      int offset,
      int limit) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    if (channel != null) {
      sql.append(" AND channel = ?");
      args.add(channel.name());
    }
    sql.append(" ORDER BY ").append(sortColumn(sort)).append(' ').append(sortOrder(order));
    sql.append(" LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
  }

  @Override
  public long count(CampaignStatus status, CampaignChannel channel) {
    StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM campaigns WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" AND status = ?");
      args.add(status.name());
    }
    if (channel != null) {
      sql.append(" AND channel = ?");
      args.add(channel.name());
    }
    Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
    return n == null ? 0L : n;
  }

  @Override
  public void appendTimeline(CampaignTimelineEvent event) {
    jdbc.update(
        """
        INSERT INTO campaign_timeline (id, campaign_id, event, at, actor)
        VALUES (?, ?, ?, ?, ?)
        """,
        event.id(),
        event.campaignId(),
        event.event(),
        Timestamp.from(event.at()),
        event.actor());
  }

  @Override
  public List<CampaignTimelineEvent> timeline(UUID campaignId) {
    return jdbc.query(
        """
        SELECT id, campaign_id, event, at, actor
        FROM campaign_timeline
        WHERE campaign_id = ?
        ORDER BY at ASC
        """,
        (rs, i) ->
            new CampaignTimelineEvent(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("campaign_id"),
                rs.getString("event"),
                rs.getTimestamp("at").toInstant(),
                rs.getString("actor")),
        campaignId);
  }

  @Override
  public void insertInteraction(
      UUID id, UUID campaignId, UUID customerId, Instant interactedAt, String interaction) {
    jdbc.update(
        """
        INSERT INTO campaign_interactions (id, campaign_id, customer_id, interacted_at, interaction)
        VALUES (?, ?, ?, ?, ?)
        """,
        id,
        campaignId,
        customerId,
        Timestamp.from(interactedAt),
        interaction);
  }

  @Override
  public Optional<Interaction> findLatestInteraction(UUID customerId) {
    List<Interaction> rows =
        jdbc.query(
            """
            SELECT campaign_id, interacted_at
            FROM campaign_interactions
            WHERE customer_id = ?
            ORDER BY interacted_at DESC
            LIMIT 1
            """,
            (rs, i) ->
                new Interaction(
                    (UUID) rs.getObject("campaign_id"),
                    rs.getTimestamp("interacted_at").toInstant()),
            customerId);
    return rows.stream().findFirst();
  }

  @Override
  public boolean isSegmentReferencedByActiveCampaign(UUID segmentId) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM campaigns
            WHERE segment_id = ?
              AND status IN ('DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED')
            """,
            Long.class,
            segmentId);
    return n != null && n.longValue() > 0L;
  }

  @Override
  public List<UUID> listSegmentMemberIds(UUID segmentId) {
    return jdbc.query(
        "SELECT customer_id FROM segment_memberships WHERE segment_id = ?",
        (rs, i) -> (UUID) rs.getObject("customer_id"),
        segmentId);
  }

  @Override
  public int countSegmentMembers(UUID segmentId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM segment_memberships WHERE segment_id = ?",
            Integer.class,
            segmentId);
    return n == null ? 0 : n;
  }

  private static String sortColumn(String sort) {
    if (sort == null || sort.isBlank()) {
      return "created_at";
    }
    String key = sort.trim().toLowerCase(Locale.ROOT);
    if ("scheduled_at".equals(key)) {
      return "scheduled_at";
    }
    if ("conversions".equals(key) || "converted_count".equals(key)) {
      return "converted_count";
    }
    if ("name".equals(key)) {
      return "name";
    }
    if ("status".equals(key)) {
      return "status";
    }
    return "created_at";
  }

  private static String sortOrder(String order) {
    if (order != null && order.trim().equalsIgnoreCase("asc")) {
      return "ASC";
    }
    return "DESC";
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Long longOrNull(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  private static Campaign map(ResultSet rs) throws SQLException {
    return new Campaign(
        (UUID) rs.getObject("id"),
        rs.getString("name"),
        CampaignChannel.valueOf(rs.getString("channel")),
        (UUID) rs.getObject("segment_id"),
        (UUID) rs.getObject("message_template_id"),
        rs.getString("subject"),
        rs.getString("body"),
        rs.getString("cta_label"),
        rs.getString("cta_link"),
        instant(rs, "scheduled_at"),
        instant(rs, "launched_at"),
        instant(rs, "completed_at"),
        instant(rs, "paused_at"),
        longOrNull(rs, "estimated_cost_paise"),
        longOrNull(rs, "budget_cap_paise"),
        rs.getLong("actual_spend_paise"),
        rs.getInt("sent_count"),
        rs.getInt("delivered_count"),
        rs.getInt("opened_count"),
        rs.getInt("clicked_count"),
        rs.getInt("converted_count"),
        rs.getLong("revenue_attributed_paise"),
        intOrNull(rs, "audience_snapshot_count"),
        CampaignStatus.valueOf(rs.getString("status")),
        (UUID) rs.getObject("created_by"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
