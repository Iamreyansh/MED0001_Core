package com.nammamedmate.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.notification.application.port.out.EmailDeliveryLogStore;
import com.nammamedmate.notification.domain.EmailBounce;
import com.nammamedmate.notification.domain.EmailBounceType;
import com.nammamedmate.notification.domain.EmailCategory;
import com.nammamedmate.notification.domain.EmailDeliveryLog;
import com.nammamedmate.notification.domain.EmailLogStatus;
import com.nammamedmate.notification.domain.EmailProvider;
import com.nammamedmate.notification.domain.EmailTemplate;
import com.nammamedmate.notification.domain.EmailUnsubscribe;
import com.nammamedmate.notification.domain.EmailUnsubscribeSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcEmailStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ID = UUID.fromString("e1000001-0000-4000-8000-000000000001");
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");

  @Test
  @SuppressWarnings("unchecked")
  void templateLogBounceUnsubscribeStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcEmailTemplateStore templates = new JdbcEmailTemplateStore(jdbc);
    JdbcEmailDeliveryLogStore logs = new JdbcEmailDeliveryLogStore(jdbc);
    JdbcEmailBounceStore bounces = new JdbcEmailBounceStore(jdbc);
    JdbcEmailUnsubscribeStore unsubs = new JdbcEmailUnsubscribeStore(jdbc);

    when(jdbc.query(contains("WHERE template_id = ?"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(templates.findById("X")).isEmpty();

    when(jdbc.query(contains("WHERE template_id = ?"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailTemplate> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("template_id")).thenReturn("ORDER_CONFIRMATION");
              when(rs.getString("name")).thenReturn("Order");
              when(rs.getString("subject")).thenReturn("s");
              when(rs.getString("html_body")).thenReturn("<p>h</p>");
              when(rs.getString("text_body")).thenReturn("t");
              when(rs.getString("category")).thenReturn("TRANSACTIONAL");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getInt("version")).thenReturn(1);
              when(rs.getObject("created_by")).thenReturn(ADMIN);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(templates.findById("ORDER_CONFIRMATION")).isPresent();
    assertThat(templates.findById("ORDER_CONFIRMATION").orElseThrow().createdAt()).isNull();

    when(jdbc.queryForObject(contains("COUNT(*) FROM email_templates"), eq(Integer.class), any()))
        .thenReturn(null);
    assertThat(templates.exists("X")).isFalse();
    when(jdbc.queryForObject(contains("COUNT(*) FROM email_templates"), eq(Integer.class), any()))
        .thenReturn(0);
    assertThat(templates.exists("X")).isFalse();
    when(jdbc.queryForObject(contains("COUNT(*) FROM email_templates"), eq(Integer.class), any()))
        .thenReturn(1);
    assertThat(templates.exists("ORDER_CONFIRMATION")).isTrue();

    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    templates.upsert(
        new EmailTemplate(
            "ORDER_CONFIRMATION",
            "Order",
            "s",
            "<p>h</p>",
            "t",
            EmailCategory.TRANSACTIONAL,
            true,
            1,
            ADMIN,
            NOW,
            NOW));

    when(jdbc.query(contains("FROM email_templates"), any(RowMapper.class))).thenReturn(List.of());
    assertThat(templates.list(null, null)).isEmpty();
    when(jdbc.query(contains("FROM email_templates"), any(RowMapper.class), any(), any()))
        .thenReturn(List.of());
    assertThat(templates.list(EmailCategory.MARKETING, true)).isEmpty();

    EmailDeliveryLog log =
        new EmailDeliveryLog(
            ID,
            "a@b.com",
            "A",
            "ORDER_CONFIRMATION",
            "subj",
            EmailProvider.SENDGRID,
            false,
            "sg1",
            EmailLogStatus.SENT,
            NOW,
            null,
            null,
            null,
            null,
            null);
    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    logs.insert(log);

    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailDeliveryLog> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_email")).thenReturn("a@b.com");
              when(rs.getString("to_name")).thenReturn("A");
              when(rs.getString("template_id")).thenReturn("ORDER_CONFIRMATION");
              when(rs.getString("subject")).thenReturn("subj");
              when(rs.getString("provider")).thenReturn(null);
              when(rs.getBoolean("fallback_used")).thenReturn(false);
              when(rs.getString("provider_message_id")).thenReturn("sg1");
              when(rs.getString("status")).thenReturn("SENT");
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("opened_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("clicked_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("bounce_type")).thenReturn("HARD");
              when(rs.getString("error_message")).thenReturn("err");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(logs.findById(ID)).isPresent();
    assertThat(logs.findById(ID).orElseThrow().provider()).isNull();
    assertThat(logs.findById(ID).orElseThrow().bounceType()).isEqualTo(EmailBounceType.HARD);

    EmailDeliveryLog nullProvider =
        new EmailDeliveryLog(
            UUID.fromString("e1000001-0000-4000-8000-000000000099"),
            "n@b.com",
            "N",
            "ORDER_CONFIRMATION",
            "subj",
            null,
            false,
            null,
            EmailLogStatus.SENT,
            NOW,
            NOW,
            NOW,
            NOW,
            null,
            null);
    logs.insert(nullProvider);

    EmailDeliveryLog withBounce =
        new EmailDeliveryLog(
            UUID.fromString("e1000001-0000-4000-8000-000000000098"),
            "bounce@b.com",
            "B",
            "ORDER_CONFIRMATION",
            "subj",
            EmailProvider.SENDGRID,
            false,
            "sg-b",
            EmailLogStatus.BOUNCED,
            NOW,
            null,
            null,
            null,
            EmailBounceType.HARD,
            "hard");
    logs.insert(withBounce);

    when(jdbc.query(contains("COUNT(opened_at)"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(logs.statsForTemplate("NONE").sentCount()).isZero();

    when(jdbc.query(contains("provider_message_id = ?"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailDeliveryLog> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_email")).thenReturn("a@b.com");
              when(rs.getString("to_name")).thenReturn("A");
              when(rs.getString("template_id")).thenReturn("ORDER_CONFIRMATION");
              when(rs.getString("subject")).thenReturn("subj");
              when(rs.getString("provider")).thenReturn("SES");
              when(rs.getBoolean("fallback_used")).thenReturn(true);
              when(rs.getString("provider_message_id")).thenReturn("ses1");
              when(rs.getString("status")).thenReturn("DELIVERED");
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("opened_at")).thenReturn(null);
              when(rs.getTimestamp("clicked_at")).thenReturn(null);
              when(rs.getString("bounce_type")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(logs.findByProviderMessageId("ses1")).isPresent();

    when(jdbc.query(contains("FROM email_templates"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailTemplate> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("template_id")).thenReturn("ORDER_CONFIRMATION");
              when(rs.getString("name")).thenReturn("Order");
              when(rs.getString("subject")).thenReturn("s");
              when(rs.getString("html_body")).thenReturn("<p>h</p>");
              when(rs.getString("text_body")).thenReturn("t");
              when(rs.getString("category")).thenReturn("TRANSACTIONAL");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getInt("version")).thenReturn(1);
              when(rs.getObject("created_by")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(templates.list(EmailCategory.TRANSACTIONAL, null)).hasSize(1);

    when(jdbc.queryForObject(contains("bounce_type = 'HARD'"), eq(Integer.class), any()))
        .thenReturn(0);
    assertThat(bounces.hasHardBounce("none@b.com")).isFalse();

    when(jdbc.queryForObject(contains("email_unsubscribes"), eq(Integer.class), any()))
        .thenReturn(null);
    when(jdbc.update(contains("INSERT INTO email_unsubscribes"), any(), any(), any(), any()))
        .thenReturn(1);
    unsubs.upsertActive(ID, "c@b.com", EmailUnsubscribeSource.MANUAL, NOW);

    // mark* false returns
    when(jdbc.update(contains("DELIVERED"), any(Object.class), any(Object.class))).thenReturn(0);
    assertThat(logs.markDelivered("missing", NOW)).isFalse();
    when(jdbc.update(contains("OPENED"), any(Object.class), any(Object.class))).thenReturn(0);
    assertThat(logs.markOpened(ID, NOW)).isFalse();
    when(jdbc.update(contains("CLICKED"), any(Object.class), any(Object.class), any(Object.class)))
        .thenReturn(0);
    assertThat(logs.markClicked(ID, NOW)).isFalse();
    when(jdbc.update(contains("BOUNCED"), any(Object.class), any(Object.class))).thenReturn(0);
    assertThat(logs.markBounced("sg1", EmailBounceType.HARD, NOW)).isFalse();
    when(jdbc.update(contains("SPAM"), any(Object.class))).thenReturn(0);
    assertThat(logs.markSpam("sg1", NOW)).isFalse();

    when(jdbc.update(contains("DELIVERED"), any(Object.class), any(Object.class))).thenReturn(1);
    assertThat(logs.markDelivered("sg1", NOW)).isTrue();
    when(jdbc.update(contains("OPENED"), any(Object.class), any(Object.class))).thenReturn(1);
    assertThat(logs.markOpened(ID, NOW)).isTrue();
    when(jdbc.update(contains("CLICKED"), any(Object.class), any(Object.class), any(Object.class)))
        .thenReturn(1);
    assertThat(logs.markClicked(ID, NOW)).isTrue();
    when(jdbc.update(contains("BOUNCED"), any(Object.class), any(Object.class))).thenReturn(1);
    assertThat(logs.markBounced("sg1", EmailBounceType.HARD, NOW)).isTrue();
    when(jdbc.update(contains("SPAM"), any(Object.class))).thenReturn(1);
    assertThat(logs.markSpam("sg1", NOW)).isTrue();

    when(jdbc.queryForObject(contains("COUNT(*) FROM email_delivery_logs"), eq(Integer.class)))
        .thenReturn(null);
    when(jdbc.query(contains("ORDER BY sent_at DESC"), any(RowMapper.class), any(), any()))
        .thenReturn(List.of());
    EmailDeliveryLogStore.Page page =
        logs.list(new EmailDeliveryLogStore.ListFilter(null, null, null, null, null, 1, 20));
    assertThat(page.total()).isZero();

    when(jdbc.queryForObject(
            contains("COUNT(*) FROM email_delivery_logs"),
            eq(Integer.class),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(2);
    when(jdbc.query(
            contains("ORDER BY sent_at DESC"),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(List.of());
    assertThat(
            logs.list(
                    new EmailDeliveryLogStore.ListFilter(
                        "a@b.com", "ORDER_CONFIRMATION", EmailLogStatus.SENT, NOW, NOW, 1, 20))
                .total())
        .isEqualTo(2);

    when(jdbc.query(contains("stats") /* won't match */, any(RowMapper.class), any()))
        .thenReturn(List.of());
    when(jdbc.query(contains("COUNT(opened_at)"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailDeliveryLogStore.TemplateStats> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("last_sent")).thenReturn(Timestamp.from(NOW));
              when(rs.getLong("sent_count")).thenReturn(10L);
              when(rs.getLong("opened_count")).thenReturn(5L);
              when(rs.getLong("clicked_count")).thenReturn(2L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(logs.statsForTemplate("ORDER_CONFIRMATION").sentCount()).isEqualTo(10);

    when(jdbc.query(contains("COUNT(opened_at)"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailDeliveryLogStore.TemplateStats> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("last_sent")).thenReturn(null);
              when(rs.getLong("sent_count")).thenReturn(0L);
              when(rs.getLong("opened_count")).thenReturn(0L);
              when(rs.getLong("clicked_count")).thenReturn(0L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(logs.statsForTemplate("EMPTY").lastSent()).isNull();

    when(jdbc.query(contains("ORDER BY sent_at DESC"), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailDeliveryLog> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_email")).thenReturn("a@b.com");
              when(rs.getString("to_name")).thenReturn(null);
              when(rs.getString("template_id")).thenReturn("ORDER_CONFIRMATION");
              when(rs.getString("subject")).thenReturn("subj");
              when(rs.getString("provider")).thenReturn(null);
              when(rs.getBoolean("fallback_used")).thenReturn(false);
              when(rs.getString("provider_message_id")).thenReturn(null);
              when(rs.getString("status")).thenReturn("SENT");
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getTimestamp("opened_at")).thenReturn(null);
              when(rs.getTimestamp("clicked_at")).thenReturn(null);
              when(rs.getString("bounce_type")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(contains("COUNT(*) FROM email_delivery_logs"), eq(Integer.class)))
        .thenReturn(1);
    assertThat(
            logs.list(new EmailDeliveryLogStore.ListFilter(null, null, null, null, null, 1, 20))
                .logs())
        .hasSize(1);

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    bounces.insert(new EmailBounce(ID, "a@b.com", EmailBounceType.HARD, "gone", true, NOW));
    when(jdbc.queryForObject(contains("bounce_type = 'HARD'"), eq(Integer.class), any()))
        .thenReturn(1);
    assertThat(bounces.hasHardBounce("a@b.com")).isTrue();
    when(jdbc.queryForObject(contains("bounce_type = 'HARD'"), eq(Integer.class), any()))
        .thenReturn(null);
    assertThat(bounces.hasHardBounce("a@b.com")).isFalse();

    when(jdbc.query(contains("FROM email_bounces"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailBounce> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("email")).thenReturn("a@b.com");
              when(rs.getString("bounce_type")).thenReturn("HARD");
              when(rs.getString("bounce_reason")).thenReturn("gone");
              when(rs.getBoolean("is_unsubscribed")).thenReturn(true);
              when(rs.getTimestamp("recorded_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(bounces.findLatestHard("a@b.com")).isPresent();

    when(jdbc.queryForObject(contains("email_unsubscribes"), eq(Integer.class), any()))
        .thenReturn(1);
    when(jdbc.update(contains("UPDATE email_unsubscribes"), any(), any(), any())).thenReturn(1);
    unsubs.upsertActive(ID, "a@b.com", EmailUnsubscribeSource.LINK_CLICK, NOW);
    when(jdbc.queryForObject(contains("email_unsubscribes"), eq(Integer.class), any()))
        .thenReturn(0);
    when(jdbc.update(contains("INSERT INTO email_unsubscribes"), any(), any(), any(), any()))
        .thenReturn(1);
    unsubs.upsertActive(ID, "b@b.com", EmailUnsubscribeSource.SPAM_REPORT, NOW);

    when(jdbc.queryForObject(contains("is_active = TRUE"), eq(Integer.class), any())).thenReturn(0);
    assertThat(unsubs.isActivelyUnsubscribed("nope@b.com")).isFalse();
    when(jdbc.queryForObject(contains("is_active = TRUE"), eq(Integer.class), any())).thenReturn(1);
    assertThat(unsubs.isActivelyUnsubscribed("a@b.com")).isTrue();
    when(jdbc.queryForObject(contains("is_active = TRUE"), eq(Integer.class), any()))
        .thenReturn(null);
    assertThat(unsubs.isActivelyUnsubscribed("a@b.com")).isFalse();

    when(jdbc.query(contains("FROM email_unsubscribes"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EmailUnsubscribe> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("email")).thenReturn("a@b.com");
              when(rs.getString("unsubscribe_source")).thenReturn("LINK_CLICK");
              when(rs.getTimestamp("unsubscribed_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getBoolean("is_active")).thenReturn(true);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(unsubs.findActive("a@b.com")).isPresent();

    verify(jdbc)
        .update(contains("INSERT INTO email_bounces"), any(), any(), any(), any(), any(), any());
  }
}
