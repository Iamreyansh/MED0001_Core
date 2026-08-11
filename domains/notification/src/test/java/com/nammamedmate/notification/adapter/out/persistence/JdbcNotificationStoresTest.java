package com.nammamedmate.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.notification.application.port.out.PushLogStore;
import com.nammamedmate.notification.domain.BroadcastAudience;
import com.nammamedmate.notification.domain.BroadcastStatus;
import com.nammamedmate.notification.domain.DevicePlatform;
import com.nammamedmate.notification.domain.DeviceToken;
import com.nammamedmate.notification.domain.NotificationUserType;
import com.nammamedmate.notification.domain.PushBroadcast;
import com.nammamedmate.notification.domain.PushLogStatus;
import com.nammamedmate.notification.domain.PushNotificationLog;
import com.nammamedmate.notification.domain.PushPriority;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcNotificationStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ID = UUID.fromString("d0000001-0000-4000-8000-000000000001");
  private static final UUID USER = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  @Test
  @SuppressWarnings("unchecked")
  void deviceTokenStoreDelegates() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcDeviceTokenStore store = new JdbcDeviceTokenStore(jdbc);

    when(jdbc.query(
            contains("user_id = ? AND user_type = ? AND device_id = ?"),
            any(RowMapper.class),
            any(),
            any(),
            any()))
        .thenReturn(List.of());
    when(jdbc.update(contains("NOT (user_id = ? AND user_type = ?)"), any(), any(), any(), any()))
        .thenReturn(0);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    DeviceToken created =
        store.upsert(
            USER, NotificationUserType.CUSTOMER, "tok", DevicePlatform.ANDROID, "dev", NOW);
    assertThat(created.token()).isEqualTo("tok");
    verify(jdbc)
        .update(contains("NOT (user_id = ? AND user_type = ?)"), any(), eq("tok"), any(), any());

    DeviceToken existing =
        new DeviceToken(
            ID,
            USER,
            NotificationUserType.CUSTOMER,
            "old",
            DevicePlatform.IOS,
            "dev",
            true,
            NOW,
            NOW);
    when(jdbc.query(
            contains("user_id = ? AND user_type = ? AND device_id = ?"),
            any(RowMapper.class),
            any(),
            any(),
            any()))
        .thenReturn(List.of(existing));
    when(jdbc.update(contains("SET token = ?"), any(), any(), any(), any())).thenReturn(1);
    DeviceToken updated =
        store.upsert(
            USER, NotificationUserType.CUSTOMER, "new", DevicePlatform.ANDROID, "dev", NOW);
    assertThat(updated.token()).isEqualTo("new");

    when(jdbc.update(contains("is_active = FALSE"), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.deactivate(USER, NotificationUserType.CUSTOMER, "dev", NOW)).isTrue();
    when(jdbc.update(contains("is_active = FALSE"), any(), any(), any(), any())).thenReturn(0);
    assertThat(store.deactivate(USER, NotificationUserType.CUSTOMER, "dev", NOW)).isFalse();
    store.deactivateById(ID, NOW);

    when(jdbc.query(
            contains("user_id = ? AND user_type = ? AND is_active = TRUE"),
            any(RowMapper.class),
            any(),
            any()))
        .thenAnswer(
            inv -> {
              RowMapper<DeviceToken> mapper = inv.getArgument(1);
              ResultSet rs2 = mock(ResultSet.class);
              when(rs2.getObject("id")).thenReturn(ID);
              when(rs2.getObject("user_id")).thenReturn(USER);
              when(rs2.getString("user_type")).thenReturn("CUSTOMER");
              when(rs2.getString("token")).thenReturn("tok");
              when(rs2.getString("platform")).thenReturn("ANDROID");
              when(rs2.getString("device_id")).thenReturn("dev");
              when(rs2.getBoolean("is_active")).thenReturn(true);
              when(rs2.getTimestamp("registered_at")).thenReturn(Timestamp.from(NOW));
              when(rs2.getTimestamp("last_refreshed_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs2, 0));
            });
    assertThat(store.findActiveByUser(USER, NotificationUserType.CUSTOMER)).hasSize(1);

    when(jdbc.query(
            contains("WHERE user_type = ? AND is_active = TRUE"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<DeviceToken> mapper = inv.getArgument(1);
              ResultSet rs2 = mock(ResultSet.class);
              when(rs2.getObject("id")).thenReturn(ID);
              when(rs2.getObject("user_id")).thenReturn(USER);
              when(rs2.getString("user_type")).thenReturn("CUSTOMER");
              when(rs2.getString("token")).thenReturn("tok");
              when(rs2.getString("platform")).thenReturn("ANDROID");
              when(rs2.getString("device_id")).thenReturn("dev");
              when(rs2.getBoolean("is_active")).thenReturn(true);
              when(rs2.getTimestamp("registered_at")).thenReturn(Timestamp.from(NOW));
              when(rs2.getTimestamp("last_refreshed_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs2, 0));
            });
    assertThat(store.findActiveByUserType(NotificationUserType.CUSTOMER)).hasSize(1);

    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any())).thenReturn(3);
    assertThat(store.countActiveByUserType(NotificationUserType.CUSTOMER)).isEqualTo(3);
    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.countActiveByUserType(NotificationUserType.CUSTOMER)).isZero();

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ID);
    when(rs.getObject("user_id")).thenReturn(USER);
    when(rs.getString("user_type")).thenReturn("CUSTOMER");
    when(rs.getString("token")).thenReturn("tok");
    when(rs.getString("platform")).thenReturn("ANDROID");
    when(rs.getString("device_id")).thenReturn("dev");
    when(rs.getBoolean("is_active")).thenReturn(true);
    when(rs.getTimestamp("registered_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("last_refreshed_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(
            contains("user_id = ? AND user_type = ? AND device_id = ?"),
            any(RowMapper.class),
            any(),
            any(),
            any()))
        .thenAnswer(
            inv -> {
              RowMapper<DeviceToken> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByUserAndDevice(USER, NotificationUserType.CUSTOMER, "dev")).isPresent();
  }

  @Test
  @SuppressWarnings("unchecked")
  void pushLogStoreDelegates() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPushLogStore store = new JdbcPushLogStore(jdbc);
    PushNotificationLog log =
        new PushNotificationLog(
            ID,
            null,
            USER,
            NotificationUserType.CUSTOMER,
            ID,
            "t",
            "b",
            PushPriority.HIGH,
            "fcm",
            PushLogStatus.SENT,
            NOW,
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
            any()))
        .thenReturn(1);
    store.insert(log);
    store.insert(
        new PushNotificationLog(
            UUID.randomUUID(),
            ID,
            USER,
            NotificationUserType.CUSTOMER,
            ID,
            "t",
            "b",
            PushPriority.NORMAL,
            null,
            PushLogStatus.DELIVERED,
            NOW,
            NOW,
            NOW,
            null));

    when(jdbc.update(contains("opened_at"), any(), any(), any())).thenReturn(1);
    assertThat(store.markOpened(ID, USER, NOW)).isTrue();
    when(jdbc.update(contains("opened_at"), any(), any(), any())).thenReturn(0);
    assertThat(store.markOpened(ID, USER, NOW)).isFalse();

    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenReturn(List.of(log));
    assertThat(store.findById(ID)).isPresent();

    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class)))
        .thenReturn(1);
    when(jdbc.query(contains("ORDER BY sent_at"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<PushNotificationLog> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getObject("broadcast_id")).thenReturn(null);
              when(rs.getObject("recipient_user_id")).thenReturn(USER);
              when(rs.getString("recipient_type")).thenReturn("CUSTOMER");
              when(rs.getObject("device_token_id")).thenReturn(ID);
              when(rs.getString("title")).thenReturn("t");
              when(rs.getString("body")).thenReturn("b");
              when(rs.getString("priority")).thenReturn("HIGH");
              when(rs.getString("fcm_message_id")).thenReturn("fcm");
              when(rs.getString("status")).thenReturn("SENT");
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getTimestamp("opened_at")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    PushLogStore.Page page =
        store.list(
            new PushLogStore.ListFilter(
                NotificationUserType.CUSTOMER,
                PushLogStatus.SENT,
                NOW.minusSeconds(10),
                NOW.plusSeconds(10),
                1,
                20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.logs().get(0).deliveredAt()).isNull();

    when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class)))
        .thenReturn(null);
    when(jdbc.query(contains("ORDER BY sent_at"), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    assertThat(store.list(new PushLogStore.ListFilter(null, null, null, null, 1, 20)).total())
        .isZero();

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ID);
    when(rs.getObject("broadcast_id")).thenReturn(null);
    when(rs.getObject("recipient_user_id")).thenReturn(USER);
    when(rs.getString("recipient_type")).thenReturn("CUSTOMER");
    when(rs.getObject("device_token_id")).thenReturn(ID);
    when(rs.getString("title")).thenReturn("t");
    when(rs.getString("body")).thenReturn("b");
    when(rs.getString("priority")).thenReturn("HIGH");
    when(rs.getString("fcm_message_id")).thenReturn("fcm");
    when(rs.getString("status")).thenReturn("SENT");
    when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("delivered_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("opened_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getString("error_message")).thenReturn(null);
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(
            inv -> {
              RowMapper<PushNotificationLog> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(ID).orElseThrow().openedAt()).isEqualTo(NOW);
  }

  @Test
  @SuppressWarnings("unchecked")
  void broadcastStoreDelegates() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcBroadcastStore store = new JdbcBroadcastStore(jdbc, new ObjectMapper());
    PushBroadcast row =
        new PushBroadcast(
            ID,
            BroadcastAudience.ALL_CUSTOMERS,
            "t",
            "b",
            Map.of("k", "v"),
            null,
            BroadcastStatus.QUEUED,
            10,
            USER,
            NOW,
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
            any()))
        .thenReturn(1);
    assertThat(store.insert(row).id()).isEqualTo(ID);
    assertThat(
            store
                .insert(
                    new PushBroadcast(
                        UUID.randomUUID(),
                        BroadcastAudience.ALL_PHARMACIES,
                        "t",
                        "b",
                        Map.of("k", "v"),
                        NOW,
                        BroadcastStatus.QUEUED,
                        1,
                        USER,
                        NOW,
                        NOW))
                .scheduleAt())
        .isEqualTo(NOW);

    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenReturn(List.of(row));
    assertThat(store.findById(ID)).isPresent();

    when(jdbc.query(contains("status = 'QUEUED'"), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<PushBroadcast> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("audience")).thenReturn("ALL_CUSTOMERS");
              when(rs.getString("title")).thenReturn("t");
              when(rs.getString("body")).thenReturn("b");
              when(rs.getString("data")).thenReturn(" ");
              when(rs.getTimestamp("schedule_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getInt("estimated_recipients")).thenReturn(1);
              when(rs.getObject("created_by")).thenReturn(USER);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("executed_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findDueQueued(NOW, 5)).hasSize(1);
    assertThat(store.findDueQueued(NOW, 5).get(0).scheduleAt()).isEqualTo(NOW);

    when(jdbc.update(contains("status = 'RUNNING'"), eq(ID))).thenReturn(1);
    assertThat(store.claimRunning(ID, NOW)).isTrue();
    when(jdbc.update(contains("status = 'RUNNING'"), eq(ID))).thenReturn(0);
    assertThat(store.claimRunning(ID, NOW)).isFalse();

    store.updateStatus(ID, BroadcastStatus.COMPLETED, NOW, 12);
    store.updateStatus(ID, BroadcastStatus.RUNNING, null, 5);
    store.updateStatus(ID, BroadcastStatus.FAILED, NOW, null);
    store.updateStatus(ID, BroadcastStatus.QUEUED, null, null);
    verify(jdbc).update(contains("SET status = ? WHERE id = ?"), eq("QUEUED"), eq(ID));

    ObjectMapper badMapper = mock(ObjectMapper.class);
    when(badMapper.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    JdbcBroadcastStore badStore = new JdbcBroadcastStore(jdbc, badMapper);
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
    badStore.insert(
        new PushBroadcast(
            ID,
            BroadcastAudience.ALL_CUSTOMERS,
            "t",
            "b",
            null,
            null,
            BroadcastStatus.QUEUED,
            0,
            USER,
            NOW,
            null));

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ID);
    when(rs.getString("audience")).thenReturn("ALL_CUSTOMERS");
    when(rs.getString("title")).thenReturn("t");
    when(rs.getString("body")).thenReturn("b");
    when(rs.getString("data")).thenReturn("{\"k\":\"v\"}");
    when(rs.getTimestamp("schedule_at")).thenReturn(null);
    when(rs.getString("status")).thenReturn("QUEUED");
    when(rs.getInt("estimated_recipients")).thenReturn(1);
    when(rs.getObject("created_by")).thenReturn(USER);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("executed_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(
            inv -> {
              RowMapper<PushBroadcast> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(ID).orElseThrow().executedAt()).isEqualTo(NOW);

    when(rs.getString("data")).thenReturn("not-json");
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(
            inv -> {
              RowMapper<PushBroadcast> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(ID).orElseThrow().data()).isEmpty();

    when(rs.getString("data")).thenReturn(null);
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(
            inv -> {
              RowMapper<PushBroadcast> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(ID).orElseThrow().data()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void smsTemplateAndDeliveryStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    JdbcSmsTemplateStore templates = new JdbcSmsTemplateStore(jdbc);
    JdbcSmsDeliveryLogStore delivery = new JdbcSmsDeliveryLogStore(jdbc, mapper);

    when(jdbc.query(contains("WHERE template_id = ?"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(templates.findById("OTP_VERIFICATION")).isEmpty();
    when(jdbc.query(contains("WHERE template_id = ?"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsTemplate> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("template_id")).thenReturn("OTP_VERIFICATION");
              when(rs.getString("content")).thenReturn("c");
              when(rs.getString("category")).thenReturn("OTP");
              when(rs.getString("dlt_template_id")).thenReturn("1007");
              when(rs.getString("sender_id")).thenReturn("NMMATE");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getObject("created_by")).thenReturn(USER);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    when(jdbc.query(contains("WHERE template_id = ?"), any(RowMapper.class), eq("NULL_TS")))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsTemplate> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("template_id")).thenReturn("NULL_TS");
              when(rs.getString("content")).thenReturn("c");
              when(rs.getString("category")).thenReturn("OTP");
              when(rs.getString("dlt_template_id")).thenReturn("1007");
              when(rs.getString("sender_id")).thenReturn("NMMATE");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getObject("created_by")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.findById("NULL_TS").orElseThrow().createdAt()).isNull();

    when(jdbc.queryForObject(contains("COUNT(*) FROM sms_templates"), eq(Integer.class), any()))
        .thenReturn(1);
    assertThat(templates.exists("OTP_VERIFICATION")).isTrue();
    when(jdbc.queryForObject(contains("COUNT(*) FROM sms_templates"), eq(Integer.class), any()))
        .thenReturn(0);
    assertThat(templates.exists("X")).isFalse();
    when(jdbc.queryForObject(contains("COUNT(*) FROM sms_templates"), eq(Integer.class), any()))
        .thenReturn(null);
    assertThat(templates.exists("Y")).isFalse();

    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    templates.insert(
        new com.nammamedmate.notification.domain.SmsTemplate(
            "T1",
            "c",
            com.nammamedmate.notification.domain.SmsCategory.OTP,
            "1007",
            "NMMATE",
            true,
            USER,
            NOW));

    when(jdbc.query(contains("ORDER BY created_at"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsTemplate> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("template_id")).thenReturn("T1");
              when(rs.getString("content")).thenReturn("c");
              when(rs.getString("category")).thenReturn("OTP");
              when(rs.getString("dlt_template_id")).thenReturn("1007");
              when(rs.getString("sender_id")).thenReturn("NMMATE");
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getObject("created_by")).thenReturn(USER);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.list(com.nammamedmate.notification.domain.SmsCategory.OTP, true))
        .hasSize(1);

    when(jdbc.query(contains("ORDER BY created_at DESC"), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsTemplate> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("template_id")).thenReturn("T2");
              when(rs.getString("content")).thenReturn("c");
              when(rs.getString("category")).thenReturn("PROMOTIONAL");
              when(rs.getString("dlt_template_id")).thenReturn(null);
              when(rs.getString("sender_id")).thenReturn("NMMATE");
              when(rs.getBoolean("is_active")).thenReturn(false);
              when(rs.getObject("created_by")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(templates.list(null, null)).hasSize(1);

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
        new com.nammamedmate.notification.domain.SmsDeliveryLog(
            ID,
            "+919876543210",
            "OTP_VERIFICATION",
            Map.of("1", "x"),
            com.nammamedmate.notification.domain.SmsProvider.MSG91,
            "msg91-1",
            false,
            com.nammamedmate.notification.domain.SmsLogStatus.SENT,
            new java.math.BigDecimal("0.12"),
            NOW,
            null,
            null));
    delivery.insert(
        new com.nammamedmate.notification.domain.SmsDeliveryLog(
            UUID.randomUUID(),
            "+919876543210",
            "OTP_VERIFICATION",
            null,
            null,
            null,
            false,
            com.nammamedmate.notification.domain.SmsLogStatus.SKIPPED_DND,
            null,
            NOW,
            NOW,
            "err"));

    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsDeliveryLog> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_phone")).thenReturn("+919876543210");
              when(rs.getString("template_id")).thenReturn("OTP_VERIFICATION");
              when(rs.getString("variables")).thenReturn("{\"1\":\"x\"}");
              when(rs.getString("provider")).thenReturn("MSG91");
              when(rs.getString("provider_message_id")).thenReturn("msg91-1");
              when(rs.getBoolean("fallback_used")).thenReturn(false);
              when(rs.getString("status")).thenReturn("SENT");
              when(rs.getBigDecimal("cost_rs")).thenReturn(new java.math.BigDecimal("0.12"));
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(delivery.findById(ID)).isPresent();

    when(jdbc.query(contains("provider_message_id = ?"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    assertThat(delivery.findByProviderMessageId("x")).isEmpty();
    when(jdbc.query(contains("provider_message_id = ?"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsDeliveryLog> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_phone")).thenReturn("+919876543210");
              when(rs.getString("template_id")).thenReturn("OTP_VERIFICATION");
              when(rs.getString("variables")).thenReturn(" ");
              when(rs.getString("provider")).thenReturn("MSG91");
              when(rs.getString("provider_message_id")).thenReturn("msg91-1");
              when(rs.getBoolean("fallback_used")).thenReturn(false);
              when(rs.getString("status")).thenReturn("SENT");
              when(rs.getBigDecimal("cost_rs")).thenReturn(new java.math.BigDecimal("0.12"));
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(delivery.findByProviderMessageId("msg91-1")).isPresent();

    delivery.insert(
        new com.nammamedmate.notification.domain.SmsDeliveryLog(
            UUID.randomUUID(),
            "+919876543210",
            "OTP_VERIFICATION",
            null,
            com.nammamedmate.notification.domain.SmsProvider.MSG91,
            "msg91-null-vars",
            false,
            com.nammamedmate.notification.domain.SmsLogStatus.SENT,
            new java.math.BigDecimal("0.12"),
            NOW,
            null,
            null));

    when(jdbc.update(anyString(), any(Timestamp.class), anyString())).thenReturn(1);
    assertThat(delivery.markDelivered("msg91-1", NOW)).isTrue();
    when(jdbc.update(anyString(), any(Timestamp.class), anyString())).thenReturn(0);
    assertThat(delivery.markDelivered("missing", NOW)).isFalse();

    when(jdbc.queryForObject(
            contains("COUNT(*) FROM sms_delivery_logs"), eq(Integer.class), any(Object[].class)))
        .thenReturn(1);
    when(jdbc.query(contains("ORDER BY sent_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsDeliveryLog> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_phone")).thenReturn("+919876543210");
              when(rs.getString("template_id")).thenReturn("OTP_VERIFICATION");
              when(rs.getString("variables")).thenReturn("not-json");
              when(rs.getString("provider")).thenReturn(null);
              when(rs.getString("provider_message_id")).thenReturn(null);
              when(rs.getBoolean("fallback_used")).thenReturn(false);
              when(rs.getString("status")).thenReturn("FAILED");
              when(rs.getBigDecimal("cost_rs")).thenReturn(null);
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("error_message")).thenReturn("e");
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(
            delivery
                .list(
                    new com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore
                        .ListFilter(
                        "+919876543210",
                        "OTP_VERIFICATION",
                        com.nammamedmate.notification.domain.SmsLogStatus.FAILED,
                        NOW.minusSeconds(1),
                        NOW.plusSeconds(1),
                        1,
                        20))
                .total())
        .isEqualTo(1);

    when(jdbc.queryForObject(
            contains("COUNT(*) FROM sms_delivery_logs"), eq(Integer.class), any(Object[].class)))
        .thenReturn(null);
    when(jdbc.query(contains("ORDER BY sent_at DESC"), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    assertThat(
            delivery
                .list(
                    new com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore
                        .ListFilter(null, null, null, null, null, 1, 20))
                .total())
        .isZero();

    when(jdbc.queryForObject(
            contains("SUM(cost_rs)"), eq(java.math.BigDecimal.class), any(), any()))
        .thenReturn(new java.math.BigDecimal("1.20"));
    assertThat(delivery.sumCostBetween(NOW.minusSeconds(10), NOW.plusSeconds(10)))
        .isEqualByComparingTo("1.20");
    when(jdbc.queryForObject(
            contains("SUM(cost_rs)"), eq(java.math.BigDecimal.class), any(), any()))
        .thenReturn(null);
    assertThat(delivery.sumCostBetween(NOW, NOW.plusSeconds(1))).isEqualByComparingTo("0");

    ObjectMapper bad = mock(ObjectMapper.class);
    when(bad.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    when(bad.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("x") {});
    JdbcSmsDeliveryLogStore badStore = new JdbcSmsDeliveryLogStore(jdbc, bad);
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
    badStore.insert(
        new com.nammamedmate.notification.domain.SmsDeliveryLog(
            ID,
            "+919876543210",
            "OTP_VERIFICATION",
            Map.of("1", "x"),
            com.nammamedmate.notification.domain.SmsProvider.MSG91,
            "m",
            false,
            com.nammamedmate.notification.domain.SmsLogStatus.SENT,
            new java.math.BigDecimal("0.12"),
            NOW,
            null,
            null));
    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsDeliveryLog> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_phone")).thenReturn("+919876543210");
              when(rs.getString("template_id")).thenReturn("OTP_VERIFICATION");
              when(rs.getString("variables")).thenReturn("{bad");
              when(rs.getString("provider")).thenReturn("TWILIO");
              when(rs.getString("provider_message_id")).thenReturn("t");
              when(rs.getBoolean("fallback_used")).thenReturn(true);
              when(rs.getString("status")).thenReturn("SENT");
              when(rs.getBigDecimal("cost_rs")).thenReturn(new java.math.BigDecimal("0.20"));
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(badStore.findById(ID).orElseThrow().variables()).isEmpty();

    when(jdbc.query(contains("WHERE id = ?"), any(RowMapper.class), eq(ID)))
        .thenAnswer(
            inv -> {
              RowMapper<com.nammamedmate.notification.domain.SmsDeliveryLog> rowMapper =
                  inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(ID);
              when(rs.getString("to_phone")).thenReturn("+919876543210");
              when(rs.getString("template_id")).thenReturn("OTP_VERIFICATION");
              when(rs.getString("variables")).thenReturn(null);
              when(rs.getString("provider")).thenReturn("MSG91");
              when(rs.getString("provider_message_id")).thenReturn("m");
              when(rs.getBoolean("fallback_used")).thenReturn(false);
              when(rs.getString("status")).thenReturn("SENT");
              when(rs.getBigDecimal("cost_rs")).thenReturn(new java.math.BigDecimal("0.12"));
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn(null);
              return List.of(rowMapper.mapRow(rs, 0));
            });
    assertThat(delivery.findById(ID).orElseThrow().variables()).isEmpty();
  }
}
