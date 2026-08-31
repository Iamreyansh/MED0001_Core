package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.domain.InAppNotificationType;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyInAppNotificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-31T06:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();

  private JdbcTemplate jdbc;
  private PharmacyInAppNotificationService service;
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    service = new PharmacyInAppNotificationService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createListCountAndMark() throws Exception {
    service.create(null, InAppNotificationType.SYSTEM, "t", "b", null);
    service.create(PHARMACY, InAppNotificationType.ORDER_UPDATE, " ", "b", null);
    service.create(PHARMACY, InAppNotificationType.SYSTEM, null, "b", null);
    service.create(PHARMACY, InAppNotificationType.SYSTEM, "Hello", null, null);
    service.create(PHARMACY, InAppNotificationType.SYSTEM, "Hello", " ", null);
    service.create(PHARMACY, InAppNotificationType.SYSTEM, "Hello", "Body", null);
    service.create(PHARMACY, InAppNotificationType.SYSTEM, "Hello", "Body", "  ");
    service.create(PHARMACY, InAppNotificationType.SYSTEM, "Hello", "Body", " /orders ");
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(2L);
    assertThat(service.unreadCount(owner)).containsEntry("unread", 2L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    assertThat(service.unreadCount(owner)).containsEntry("unread", 0L);

    UUID id = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Map<String, Object>> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("type")).thenReturn("SYSTEM");
              when(rs.getString("title")).thenReturn("Hello");
              when(rs.getString("body")).thenReturn("Body");
              when(rs.getString("action_url")).thenReturn("/orders");
              when(rs.getBoolean("is_read")).thenReturn(false);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);
    PharmacyInAppNotificationService.HistoryPage page = service.list(owner, true, 1, 20);
    assertThat(page.total()).isEqualTo(1L);
    assertThat(service.list(owner, null, 1, 20).total()).isEqualTo(1L);
    ResultSet nullCreated = mock(ResultSet.class);
    when(nullCreated.getObject("id")).thenReturn(id);
    when(nullCreated.getString("type")).thenReturn("SYSTEM");
    when(nullCreated.getString("title")).thenReturn("Hello");
    when(nullCreated.getString("body")).thenReturn("Body");
    when(nullCreated.getString("action_url")).thenReturn(null);
    when(nullCreated.getBoolean("is_read")).thenReturn(true);
    when(nullCreated.getTimestamp("created_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Map<String, Object>> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(nullCreated, 0));
            });
    assertThat(service.list(owner, false, null, null).data().get("notifications"))
        .asList()
        .hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    assertThat(service.list(owner, false, 1, 20).total()).isZero();

    when(jdbc.update(anyString(), any(Timestamp.class), any(UUID.class), any(UUID.class)))
        .thenReturn(1);
    assertThat(service.markRead(owner, id)).containsEntry("is_read", true);
    when(jdbc.update(anyString(), any(Timestamp.class), any(UUID.class), any(UUID.class)))
        .thenReturn(0);
    assertThatThrownBy(() -> service.markRead(owner, id))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("NOT_FOUND");
    assertThatThrownBy(() -> service.markRead(owner, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(jdbc.update(anyString(), any(Timestamp.class), any(UUID.class))).thenReturn(4);
    assertThat(service.markAllRead(owner)).containsEntry("updated", 4);
  }

  @Test
  void guardsRoles() {
    assertThatThrownBy(() -> service.unreadCount(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.unreadCount(customer))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noShop =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.unreadCount(noShop))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }
}
