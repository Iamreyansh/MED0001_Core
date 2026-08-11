package com.nammamedmate.notification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.notification.application.port.out.DispatchLogStore;
import com.nammamedmate.notification.application.port.out.InAppNotificationStore;
import com.nammamedmate.notification.domain.DispatchLogEntry;
import com.nammamedmate.notification.domain.InAppNotification;
import com.nammamedmate.notification.domain.InAppNotificationType;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcInAppAndDispatchStoresTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ID = UUID.fromString("a1000001-0000-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c1000001-0000-4000-8000-000000000001");

  @Test
  @SuppressWarnings("unchecked")
  void inAppStoreCrudAndFilters() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcInAppNotificationStore store = new JdbcInAppNotificationStore(jdbc);
    AtomicInteger updates = new AtomicInteger();
    when(jdbc.update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class)))
        .thenAnswer(
            inv -> {
              updates.incrementAndGet();
              return 1;
            });

    store.insert(
        new InAppNotification(
            ID,
            CUST,
            InAppNotificationType.ORDER_UPDATE,
            "t",
            "b",
            "nmmedmate://order/1",
            false,
            false,
            null,
            NOW.plusSeconds(3600),
            NOW));
    store.insert(
        new InAppNotification(
            UUID.fromString("a1000001-0000-4000-8000-000000000002"),
            CUST,
            InAppNotificationType.PROMO,
            "t2",
            "b2",
            null,
            true,
            false,
            NOW,
            NOW.plusSeconds(3600),
            NOW));

    when(jdbc.query(contains("WHERE id = ? AND customer_id"), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<InAppNotification> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRow(false), 0));
            });
    assertThat(store.findByIdForCustomer(ID, CUST)).isPresent();
    assertThat(store.findByIdForCustomer(ID, CUST).orElseThrow().readAt()).isNull();

    when(jdbc.query(contains("WHERE id = ? AND customer_id"), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<InAppNotification> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRow(true), 0));
            });
    assertThat(store.findByIdForCustomer(ID, CUST).orElseThrow().readAt()).isEqualTo(NOW);

    when(jdbc.queryForObject(
            contains("SELECT COUNT(*) FROM customer_in_app_notifications"),
            any(Class.class),
            any(),
            any(),
            any()))
        .thenReturn(1);
    when(jdbc.query(
            contains("ORDER BY created_at DESC"),
            any(RowMapper.class),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenAnswer(
            inv -> {
              RowMapper<InAppNotification> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRow(false), 0));
            });
    InAppNotificationStore.Page page =
        store.list(
            new InAppNotificationStore.ListFilter(
                CUST, true, InAppNotificationType.ORDER_UPDATE, NOW, 1, 20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);

    when(jdbc.queryForObject(contains("is_read = FALSE"), any(Class.class), any(), any()))
        .thenReturn(null);
    assertThat(store.countUnread(CUST, NOW)).isZero();
    when(jdbc.queryForObject(contains("is_read = FALSE"), any(Class.class), any(), any()))
        .thenReturn(4);
    assertThat(store.countUnread(CUST, NOW)).isEqualTo(4);

    assertThat(store.markRead(ID, CUST, NOW)).isTrue();
    assertThat(store.markAllRead(CUST, NOW, NOW)).isEqualTo(1);
    assertThat(store.softDelete(ID, CUST)).isTrue();
    assertThat(store.softDeleteExpired(NOW)).isEqualTo(1);
    assertThat(store.hardDeletePastRetention(NOW)).isEqualTo(1);
    assertThat(updates.get()).isGreaterThanOrEqualTo(5);

    when(jdbc.queryForObject(
            contains("SELECT COUNT(*) FROM customer_in_app_notifications"),
            any(Class.class),
            any(),
            any()))
        .thenReturn(null);
    when(jdbc.query(
            contains("ORDER BY created_at DESC"), any(RowMapper.class), any(), any(), any(), any()))
        .thenReturn(List.of());
    assertThat(
            store
                .list(new InAppNotificationStore.ListFilter(CUST, false, null, NOW, 1, 20))
                .total())
        .isZero();

    when(jdbc.update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class)))
        .thenReturn(0);
    assertThat(store.markRead(ID, CUST, NOW)).isFalse();
    assertThat(store.softDelete(ID, CUST)).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void dispatchLogStoreFilters() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcDispatchLogStore store = new JdbcDispatchLogStore(jdbc);

    when(jdbc.queryForObject(
            contains("SELECT COUNT(*) FROM notification_dispatch_log"),
            any(Class.class),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
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
        .thenAnswer(
            inv -> {
              RowMapper<DispatchLogEntry> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("dispatch_id")).thenReturn(ID);
              when(rs.getObject("recipient_id")).thenReturn(CUST);
              when(rs.getString("recipient_type")).thenReturn("CUSTOMER");
              when(rs.getString("channel")).thenReturn("SMS");
              when(rs.getString("type")).thenReturn("OTP");
              when(rs.getString("title")).thenReturn("OTP");
              when(rs.getString("status")).thenReturn("FAILED");
              when(rs.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("delivered_at")).thenReturn(null);
              ResultSet rs2 = mock(ResultSet.class);
              when(rs2.getObject("dispatch_id")).thenReturn(ID);
              when(rs2.getObject("recipient_id")).thenReturn(CUST);
              when(rs2.getString("recipient_type")).thenReturn("CUSTOMER");
              when(rs2.getString("channel")).thenReturn("SMS");
              when(rs2.getString("type")).thenReturn("OTP");
              when(rs2.getString("title")).thenReturn("OTP");
              when(rs2.getString("status")).thenReturn("DELIVERED");
              when(rs2.getTimestamp("sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs2.getTimestamp("delivered_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0), mapper.mapRow(rs2, 1));
            });

    DispatchLogStore.Page page =
        store.list(
            new DispatchLogStore.ListFilter(
                "SMS", "FAILED", "CUSTOMER", NOW.minusSeconds(10), NOW.plusSeconds(10), 1, 20));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).deliveredAt()).isNull();
    assertThat(page.items().get(1).deliveredAt()).isEqualTo(NOW);
    when(jdbc.queryForObject(
            contains("SELECT COUNT(*) FROM notification_dispatch_log"), any(Class.class)))
        .thenReturn(null);
    when(jdbc.query(contains("ORDER BY sent_at DESC"), any(RowMapper.class), any(), any()))
        .thenReturn(List.of());
    assertThat(
            store
                .list(new DispatchLogStore.ListFilter(null, null, null, null, null, 1, 20))
                .total())
        .isZero();
  }

  private static ResultSet mockRow(boolean withReadAt) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ID);
    when(rs.getObject("customer_id")).thenReturn(CUST);
    when(rs.getString("type")).thenReturn("ORDER_UPDATE");
    when(rs.getString("title")).thenReturn("t");
    when(rs.getString("body")).thenReturn("b");
    when(rs.getString("action_url")).thenReturn("nmmedmate://order/1");
    when(rs.getBoolean("is_read")).thenReturn(withReadAt);
    when(rs.getBoolean("is_deleted")).thenReturn(false);
    when(rs.getTimestamp("read_at")).thenReturn(withReadAt ? Timestamp.from(NOW) : null);
    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(3600)));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }
}
