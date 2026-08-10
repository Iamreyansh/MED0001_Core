package com.nammamedmate.marketing.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import com.nammamedmate.marketing.domain.CampaignTimelineEvent;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcCampaignStoreTest {

  @Mock JdbcTemplate jdbc;
  JdbcCampaignStore store;

  private final UUID id = UUID.fromString("c0130003-0000-4000-8000-000000000001");
  private final UUID seg = UUID.fromString("a0130004-0000-4000-8000-000000000002");
  private final Instant now = Instant.parse("2026-07-24T10:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcCampaignStore(jdbc);
  }

  @Test
  @SuppressWarnings("unchecked")
  void coversMutationsAndQueries() throws Exception {
    Campaign c = sample();
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    store.insert(c);
    store.update(c);
    store.appendTimeline(
        new CampaignTimelineEvent(UUID.randomUUID(), id, "CREATED", now, "SYSTEM"));
    store.insertInteraction(UUID.randomUUID(), id, UUID.randomUUID(), now, "CLICKED");
    verify(jdbc, org.mockito.Mockito.atLeast(4)).update(anyString(), any(Object[].class));

    ResultSet rs = mockRs(c);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.findById(id)).isPresent();
    assertThat(
            store.list(
                CampaignStatus.DRAFT, CampaignChannel.WHATSAPP, "scheduled_at", "asc", 0, 20))
        .hasSize(1);
    assertThat(store.list(null, null, "conversions", "desc", 0, 10)).hasSize(1);
    assertThat(store.list(null, null, "name", null, 0, 10)).hasSize(1);
    assertThat(store.list(null, null, "status", "ASC", 0, 10)).hasSize(1);
    assertThat(store.list(null, null, "bogus", "x", 0, 10)).hasSize(1);
    assertThat(store.list(null, null, "converted_count", "desc", 0, 10)).hasSize(1);
    assertThat(store.list(null, null, "", "asc", 0, 10)).hasSize(1);
    assertThat(store.list(null, null, "   ", "desc", 0, 10)).hasSize(1);

    // intOrNull non-null path (audience_snapshot present)
    when(rs.wasNull()).thenReturn(false, false, false);
    assertThat(store.findById(id)).isPresent();
    // intOrNull null path
    when(rs.wasNull()).thenReturn(false, false, true);
    assertThat(store.findById(id)).isPresent();
    // longOrNull null path
    when(rs.wasNull()).thenReturn(true, true, false);
    assertThat(store.findById(id)).isPresent();
    assertThat(store.timeline(id)).hasSize(1);
    assertThat(store.findLatestInteraction(UUID.randomUUID())).isPresent();
    assertThat(store.listSegmentMemberIds(seg)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
    assertThat(store.count(CampaignStatus.DRAFT, CampaignChannel.PUSH)).isEqualTo(2);
    assertThat(store.count(null, null)).isEqualTo(2);
    assertThat(store.isSegmentReferencedByActiveCampaign(seg)).isTrue();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(5);
    assertThat(store.countSegmentMembers(seg)).isEqualTo(5);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    assertThat(store.isSegmentReferencedByActiveCampaign(seg)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.count(null, null)).isZero();
    assertThat(store.isSegmentReferencedByActiveCampaign(seg)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
    assertThat(store.countSegmentMembers(seg)).isZero();

    assertThat(store.list(null, null, null, "desc", 0, 5)).hasSize(1);
  }

  private Campaign sample() {
    return new Campaign(
        id,
        "Monsoon",
        CampaignChannel.WHATSAPP,
        seg,
        UUID.randomUUID(),
        null,
        null,
        "Order",
        "https://x",
        now,
        null,
        null,
        null,
        100L,
        200L,
        0L,
        0,
        0,
        0,
        0,
        0,
        0L,
        null,
        CampaignStatus.DRAFT,
        UUID.randomUUID(),
        now,
        now);
  }

  private ResultSet mockRs(Campaign c) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(c.id());
    when(rs.getString("name")).thenReturn(c.name());
    when(rs.getString("channel")).thenReturn(c.channel().name());
    when(rs.getObject("segment_id")).thenReturn(c.segmentId());
    when(rs.getObject("message_template_id")).thenReturn(c.messageTemplateId());
    when(rs.getString("subject")).thenReturn(null);
    when(rs.getString("body")).thenReturn(null);
    when(rs.getString("cta_label")).thenReturn(c.ctaLabel());
    when(rs.getString("cta_link")).thenReturn(c.ctaLink());
    when(rs.getTimestamp("scheduled_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("launched_at")).thenReturn(null);
    when(rs.getTimestamp("completed_at")).thenReturn(null);
    when(rs.getTimestamp("paused_at")).thenReturn(null);
    when(rs.getLong("estimated_cost_paise")).thenReturn(100L);
    when(rs.getLong("budget_cap_paise")).thenReturn(200L);
    when(rs.getLong("actual_spend_paise")).thenReturn(0L);
    when(rs.getInt("sent_count")).thenReturn(0);
    when(rs.getInt("delivered_count")).thenReturn(0);
    when(rs.getInt("opened_count")).thenReturn(0);
    when(rs.getInt("clicked_count")).thenReturn(0);
    when(rs.getInt("converted_count")).thenReturn(0);
    when(rs.getLong("revenue_attributed_paise")).thenReturn(0L);
    when(rs.getInt("audience_snapshot_count")).thenReturn(0);
    // longOrNull x2 (false), intOrNull audience (true → null)
    when(rs.wasNull()).thenReturn(false, false, true);
    when(rs.getString("status")).thenReturn("DRAFT");
    when(rs.getObject("created_by")).thenReturn(c.createdBy());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getObject("campaign_id")).thenReturn(id);
    when(rs.getString("event")).thenReturn("CREATED");
    when(rs.getTimestamp("at")).thenReturn(Timestamp.from(now));
    when(rs.getString("actor")).thenReturn("SYSTEM");
    when(rs.getObject("customer_id")).thenReturn(UUID.randomUUID());
    when(rs.getTimestamp("interacted_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
