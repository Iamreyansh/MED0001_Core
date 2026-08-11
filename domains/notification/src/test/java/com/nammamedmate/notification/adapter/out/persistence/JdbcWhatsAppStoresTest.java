package com.nammamedmate.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.port.out.WhatsAppDeliveryLogStore;
import com.nammamedmate.notification.domain.WhatsAppCategory;
import com.nammamedmate.notification.domain.WhatsAppDeliveryLog;
import com.nammamedmate.notification.domain.WhatsAppLogStatus;
import com.nammamedmate.notification.domain.WhatsAppOptoutSource;
import com.nammamedmate.notification.domain.WhatsAppTemplate;
import com.nammamedmate.notification.domain.WhatsAppTemplateStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcWhatsAppStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ID = UUID.fromString("b1000001-0000-4000-8000-000000000001");

  @Test
  @SuppressWarnings("unchecked")
  void templateDeliveryOptoutAndSessionStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcWhatsAppTemplateStore templates = new JdbcWhatsAppTemplateStore(jdbc, mapper);
    JdbcWhatsAppDeliveryLogStore delivery = new JdbcWhatsAppDeliveryLogStore(jdbc, mapper);
    JdbcWhatsAppOptoutStore optouts = new JdbcWhatsAppOptoutStore(jdbc);
    JdbcWhatsAppSessionStore sessions = new JdbcWhatsAppSessionStore(jdbc);

    when(jdbc.query(contains("WHERE template_name = ?"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(templates.findByName("X")).isEmpty();
    when(jdbc.query(contains("WHERE template_name = ?"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<WhatsAppTemplate> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("template_name")).thenReturn("ORDER_CONFIRMED");
              when(rs.getString("category")).thenReturn("UTILITY");
              when(rs.getString("language")).thenReturn("en");
              when(rs.getString("status")).thenReturn("APPROVED");
              when(rs.getString("body_text")).thenReturn("Hi {{1}}");
              when(rs.getString("header_json")).thenReturn("");
              when(rs.getString("footer_text")).thenReturn(null);
              when(rs.getString("buttons_json")).thenReturn("");
              when(rs.getString("meta_template_id")).thenReturn(null);
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getTimestamp("submitted_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("approved_at")).thenReturn(null);
              when(rs.getTimestamp("last_used_at")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.findByName("ORDER_CONFIRMED")).isPresent();

    when(jdbc.queryForObject(
            contains("COUNT(*) FROM whatsapp_templates"), eq(Integer.class), any()))
        .thenReturn(null);
    assertThat(templates.exists("X")).isFalse();
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM whatsapp_templates"), eq(Integer.class), any()))
        .thenReturn(0);
    assertThat(templates.exists("X")).isFalse();
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM whatsapp_templates"), eq(Integer.class), any()))
        .thenReturn(1);
    assertThat(templates.exists("ORDER_CONFIRMED")).isTrue();

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
            any()))
        .thenReturn(1);
    templates.insert(
        new WhatsAppTemplate(
            ID,
            "ORDER_CONFIRMED",
            WhatsAppCategory.UTILITY,
            "en",
            WhatsAppTemplateStatus.APPROVED,
            "Hi {{1}}",
            Map.of("format", "TEXT"),
            "footer",
            List.of(Map.of("type", "URL")),
            "meta",
            null,
            NOW,
            NOW,
            null));
    templates.insert(
        new WhatsAppTemplate(
            UUID.randomUUID(),
            "NO_JSON",
            WhatsAppCategory.MARKETING,
            "en",
            WhatsAppTemplateStatus.PENDING,
            "x",
            null,
            null,
            null,
            null,
            "reason",
            NOW,
            null,
            null));

    when(jdbc.update(contains("last_used_at"), any(Timestamp.class), anyString())).thenReturn(1);
    templates.touchLastUsed("ORDER_CONFIRMED", NOW);

    when(jdbc.query(
            contains("ORDER BY submitted_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<WhatsAppTemplate> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("template_name")).thenReturn("ORDER_CONFIRMED");
              when(rs.getString("category")).thenReturn("UTILITY");
              when(rs.getString("language")).thenReturn("en");
              when(rs.getString("status")).thenReturn("APPROVED");
              when(rs.getString("body_text")).thenReturn("Hi {{1}}");
              when(rs.getString("header_json")).thenReturn("{\"format\":\"TEXT\"}");
              when(rs.getString("footer_text")).thenReturn("f");
              when(rs.getString("buttons_json")).thenReturn("[]");
              when(rs.getString("meta_template_id")).thenReturn("meta");
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getTimestamp("submitted_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("approved_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("last_used_at")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.list(WhatsAppCategory.UTILITY, WhatsAppTemplateStatus.APPROVED))
        .hasSize(1);

    when(jdbc.query(
            contains("ORDER BY submitted_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<WhatsAppTemplate> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("template_name")).thenReturn("NULLS");
              when(rs.getString("category")).thenReturn("UTILITY");
              when(rs.getString("language")).thenReturn("en");
              when(rs.getString("status")).thenReturn("APPROVED");
              when(rs.getString("body_text")).thenReturn("x");
              when(rs.getString("header_json")).thenReturn(null);
              when(rs.getString("footer_text")).thenReturn(null);
              when(rs.getString("buttons_json")).thenReturn(null);
              when(rs.getString("meta_template_id")).thenReturn(null);
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getTimestamp("submitted_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("approved_at")).thenReturn(null);
              when(rs.getTimestamp("last_used_at")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.list(null, null).get(0).header()).isNull();
    assertThat(templates.list(null, null).get(0).buttons()).isEmpty();

    when(jdbc.query(
            contains("ORDER BY submitted_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<WhatsAppTemplate> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("template_name")).thenReturn("SPACES");
              when(rs.getString("category")).thenReturn("UTILITY");
              when(rs.getString("language")).thenReturn("en");
              when(rs.getString("status")).thenReturn("APPROVED");
              when(rs.getString("body_text")).thenReturn("x");
              when(rs.getString("header_json")).thenReturn("   ");
              when(rs.getString("footer_text")).thenReturn(null);
              when(rs.getString("buttons_json")).thenReturn("   ");
              when(rs.getString("meta_template_id")).thenReturn(null);
              when(rs.getString("rejection_reason")).thenReturn(null);
              when(rs.getTimestamp("submitted_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("approved_at")).thenReturn(null);
              when(rs.getTimestamp("last_used_at")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.list(null, null).get(0).header()).isNull();
    assertThat(templates.list(null, null).get(0).buttons()).isEmpty();

    when(jdbc.query(
            contains("ORDER BY submitted_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<WhatsAppTemplate> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("template_name")).thenReturn("BAD");
              when(rs.getString("category")).thenReturn("UTILITY");
              when(rs.getString("language")).thenReturn("en");
              when(rs.getString("status")).thenReturn("REJECTED");
              when(rs.getString("body_text")).thenReturn("x");
              when(rs.getString("header_json")).thenReturn("{bad");
              when(rs.getString("footer_text")).thenReturn(null);
              when(rs.getString("buttons_json")).thenReturn("{bad");
              when(rs.getString("meta_template_id")).thenReturn(null);
              when(rs.getString("rejection_reason")).thenReturn("r");
              when(rs.getTimestamp("submitted_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("approved_at")).thenReturn(null);
              when(rs.getTimestamp("last_used_at")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.list(null, null).get(0).header()).isNull();
    assertThat(templates.list(null, null).get(0).buttons()).isEmpty();

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
            any()))
        .thenReturn(1);
    delivery.insert(
        new WhatsAppDeliveryLog(
            ID,
            "+919876543210",
            "ORDER_CONFIRMED",
            List.of(Map.of("type", "body")),
            "wamid.1",
            WhatsAppLogStatus.SENT,
            new BigDecimal("0.85"),
            NOW,
            null,
            null,
            null,
            null));
    delivery.insert(
        new WhatsAppDeliveryLog(
            UUID.randomUUID(),
            "+919876543210",
            "ORDER_CONFIRMED",
            null,
            null,
            WhatsAppLogStatus.FAILED,
            null,
            NOW,
            NOW,
            NOW,
            "E",
            "err"));

    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(inv -> List.of(mapDelivery(inv.getArgument(1), "[]")));
    assertThat(delivery.findById(ID)).isPresent();

    when(jdbc.query(contains("wa_message_id = ?"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(delivery.findByWaMessageId("x")).isEmpty();
    when(jdbc.query(contains("wa_message_id = ?"), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(mapDelivery(inv.getArgument(1), " ")));
    assertThat(delivery.findByWaMessageId("wamid.1")).isPresent();

    when(jdbc.update(anyString(), any(Timestamp.class), anyString())).thenReturn(1);
    assertThat(delivery.markDelivered("wamid.1", NOW)).isTrue();
    when(jdbc.update(anyString(), any(Timestamp.class), anyString())).thenReturn(0);
    assertThat(delivery.markDelivered("missing", NOW)).isFalse();

    when(jdbc.update(anyString(), any(Timestamp.class), any(Timestamp.class), anyString()))
        .thenReturn(1);
    assertThat(delivery.markRead("wamid.1", NOW)).isTrue();
    when(jdbc.update(anyString(), any(Timestamp.class), any(Timestamp.class), anyString()))
        .thenReturn(0);
    assertThat(delivery.markRead("missing", NOW)).isFalse();
    when(jdbc.update(anyString(), any(), any(), anyString())).thenReturn(1);
    assertThat(delivery.markFailed("wamid.1", "E", "err")).isTrue();
    when(jdbc.update(anyString(), any(), any(), anyString())).thenReturn(0);
    assertThat(delivery.markFailed("missing", "E", "err")).isFalse();

    when(jdbc.queryForObject(
            contains("COUNT(*) FROM whatsapp_delivery_logs"),
            eq(Integer.class),
            any(Object[].class)))
        .thenReturn(1);
    when(jdbc.query(contains("ORDER BY sent_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(mapDelivery(inv.getArgument(1), "not-json")));
    assertThat(
            delivery
                .list(
                    new WhatsAppDeliveryLogStore.ListFilter(
                        "+919876543210",
                        "ORDER_CONFIRMED",
                        WhatsAppLogStatus.SENT,
                        NOW.minusSeconds(1),
                        NOW.plusSeconds(1),
                        1,
                        20))
                .total())
        .isEqualTo(1);

    when(jdbc.queryForObject(
            contains("COUNT(*) FROM whatsapp_delivery_logs"),
            eq(Integer.class),
            any(Object[].class)))
        .thenReturn(null);
    when(jdbc.query(contains("ORDER BY sent_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    assertThat(
            delivery
                .list(new WhatsAppDeliveryLogStore.ListFilter(null, null, null, null, null, 1, 20))
                .total())
        .isZero();

    when(jdbc.queryForObject(contains("whatsapp_optouts"), eq(Integer.class), any()))
        .thenReturn(null);
    assertThat(optouts.isActivelyOptedOut("+91")).isFalse();
    when(jdbc.queryForObject(contains("whatsapp_optouts"), eq(Integer.class), any())).thenReturn(0);
    assertThat(optouts.isActivelyOptedOut("+91")).isFalse();
    when(jdbc.queryForObject(contains("whatsapp_optouts"), eq(Integer.class), any())).thenReturn(1);
    assertThat(optouts.isActivelyOptedOut("+91")).isTrue();

    when(jdbc.queryForObject(contains("whatsapp_optouts"), eq(Integer.class), any()))
        .thenReturn(null);
    when(jdbc.update(contains("INSERT INTO whatsapp_optouts"), any(), any(), any(), any()))
        .thenReturn(1);
    optouts.upsertActive(ID, "+93", WhatsAppOptoutSource.WA_REPLY, NOW);

    when(jdbc.queryForObject(contains("whatsapp_optouts"), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.update(contains("UPDATE whatsapp_optouts"), any(), any(), any())).thenReturn(1);
    optouts.upsertActive(ID, "+91", WhatsAppOptoutSource.WA_REPLY, NOW);

    when(jdbc.queryForObject(contains("whatsapp_optouts"), eq(Integer.class), any())).thenReturn(0);
    when(jdbc.update(contains("INSERT INTO whatsapp_optouts"), any(), any(), any(), any()))
        .thenReturn(1);
    optouts.upsertActive(ID, "+92", WhatsAppOptoutSource.IN_APP, NOW);

    when(jdbc.query(contains("FROM whatsapp_optouts"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("phone")).thenReturn("+91");
              when(rs.getString("optout_source")).thenReturn("WA_REPLY");
              when(rs.getTimestamp("opted_out_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getBoolean("is_active")).thenReturn(true);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(optouts.findActiveByPhone("+91")).isPresent();
    when(jdbc.query(contains("FROM whatsapp_optouts"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(optouts.findActiveByPhone("+99")).isEmpty();

    when(jdbc.update(contains("SET is_active = FALSE"), any(Object.class))).thenReturn(1);
    optouts.deactivateByPhone("+91");

    when(jdbc.update(contains("UPDATE whatsapp_sessions"), any(Timestamp.class), anyString()))
        .thenReturn(1);
    sessions.upsertCustomerMessage("+91", NOW);
    when(jdbc.update(contains("UPDATE whatsapp_sessions"), any(Timestamp.class), anyString()))
        .thenReturn(0);
    when(jdbc.update(contains("INSERT INTO whatsapp_sessions"), any(), any())).thenReturn(1);
    sessions.upsertCustomerMessage("+92", NOW);

    when(jdbc.query(contains("FROM whatsapp_sessions"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Instant> rowMapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getTimestamp("last_customer_message_at")).thenReturn(Timestamp.from(NOW));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(sessions.lastCustomerMessageAt("+91")).contains(NOW);
    when(jdbc.query(contains("FROM whatsapp_sessions"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(sessions.lastCustomerMessageAt("+99")).isEmpty();

    ObjectMapper bad = mock(ObjectMapper.class);
    when(bad.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    when(bad.readValue(anyString(), any(TypeReference.class)))
        .thenThrow(new JsonProcessingException("x") {});
    JdbcWhatsAppTemplateStore badTpl = new JdbcWhatsAppTemplateStore(jdbc, bad);
    JdbcWhatsAppDeliveryLogStore badLog = new JdbcWhatsAppDeliveryLogStore(jdbc, bad);
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
            any()))
        .thenReturn(1);
    badTpl.insert(
        new WhatsAppTemplate(
            ID,
            "Z",
            WhatsAppCategory.UTILITY,
            "en",
            WhatsAppTemplateStatus.APPROVED,
            "b",
            Map.of("a", 1),
            null,
            List.of(Map.of("b", 2)),
            null,
            null,
            NOW,
            null,
            null));
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
            any()))
        .thenReturn(1);
    badLog.insert(
        new WhatsAppDeliveryLog(
            ID,
            "+91",
            "Z",
            List.of(Map.of("type", "body")),
            "w",
            WhatsAppLogStatus.SENT,
            BigDecimal.ONE,
            NOW,
            null,
            null,
            null,
            null));
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(inv -> List.of(mapDelivery(inv.getArgument(1), null)));
    assertThat(delivery.findById(ID).orElseThrow().components()).isEmpty();
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(inv -> List.of(mapDelivery(inv.getArgument(1), "{bad")));
    assertThat(badLog.findById(ID).orElseThrow().components()).isEmpty();
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(inv -> List.of(mapDelivery(inv.getArgument(1), "[]", true)));
    assertThat(delivery.findById(ID).orElseThrow().deliveredAt()).isNotNull();
  }

  private static WhatsAppDeliveryLog mapDelivery(
      RowMapper<WhatsAppDeliveryLog> rowMapper, String components) throws Exception {
    return mapDelivery(rowMapper, components, false);
  }

  private static WhatsAppDeliveryLog mapDelivery(
      RowMapper<WhatsAppDeliveryLog> rowMapper, String components, boolean withTimestamps)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ID);
    when(rs.getString("to_phone")).thenReturn("+919876543210");
    when(rs.getString("template_name")).thenReturn("ORDER_CONFIRMED");
    when(rs.getString("components_json")).thenReturn(components);
    when(rs.getString("wa_message_id")).thenReturn("wamid.1");
    when(rs.getString("status")).thenReturn("SENT");
    when(rs.getBigDecimal("cost_rs")).thenReturn(new BigDecimal("0.85"));
    when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("delivered_at")).thenReturn(withTimestamps ? Timestamp.from(NOW) : null);
    when(rs.getTimestamp("read_at")).thenReturn(withTimestamps ? Timestamp.from(NOW) : null);
    when(rs.getString("error_code")).thenReturn(null);
    when(rs.getString("error_message")).thenReturn(null);
    return rowMapper.mapRow(rs, 0);
  }
}
