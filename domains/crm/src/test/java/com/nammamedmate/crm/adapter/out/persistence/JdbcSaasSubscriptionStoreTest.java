package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.domain.BillingCycle;
import com.nammamedmate.crm.domain.SaasSubscription;
import com.nammamedmate.crm.domain.SubscriptionStatus;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
class JdbcSaasSubscriptionStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void coversCrudAndQueries() throws Exception {
    JdbcSaasSubscriptionStore store = new JdbcSaasSubscriptionStore(jdbc);
    UUID id = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    UUID accountId = UUID.fromString("b1000000-0000-4000-8000-000000000002");
    UUID planId = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    SaasSubscription sub =
        new SaasSubscription(
            id,
            accountId,
            planId,
            null,
            SubscriptionStatus.ACTIVE,
            BillingCycle.MONTHLY,
            now.plusSeconds(86400),
            null,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);

    store.insert(sub);
    store.update(sub);
    store.updateAccountDenorm(accountId, "FREE", "ACTIVE", now);

    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("account_id")).thenReturn(accountId);
    when(rs.getObject("plan_id")).thenReturn(planId);
    when(rs.getObject("scheduled_plan_id")).thenReturn(null);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getString("billing_cycle")).thenReturn("MONTHLY");
    when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
    when(rs.getBoolean("auto_renew")).thenReturn(true);
    when(rs.getObject("last_invoice_id")).thenReturn(null);
    when(rs.getObject("override_plan_id")).thenReturn(null);
    when(rs.getString("override_reason")).thenReturn(null);

    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              Object mapped = mapper.mapRow(rs, 0);
              return List.of(mapped);
            });
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.findByAccountId(accountId)).isPresent();
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findDueForAutoRenew(now, now.plusSeconds(10))).isNotEmpty();
    assertThat(store.findPastDueExpired(now)).isNotEmpty();
    assertThat(store.findTrialsEnding(now)).isNotEmpty();
    assertThat(store.findCancelsDue(now)).isNotEmpty();
    assertThat(store.findOverridesExpired(now)).isNotEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(accountId)))
        .thenAnswer(
            inv -> {
              RowMapper<UUID> mapper = inv.getArgument(1);
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              return List.of(mapper.mapRow(rs, 0));
            })
        .thenReturn(List.of());
    assertThat(store.findPharmacyId(accountId)).isPresent();
    assertThat(store.findPharmacyId(accountId)).isEmpty();

    when(rs.getTimestamp(anyString())).thenReturn(null);
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("account_id")).thenReturn(accountId);
              when(rs.getObject("plan_id")).thenReturn(planId);
              when(rs.getObject("scheduled_plan_id")).thenReturn(null);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("billing_cycle")).thenReturn("MONTHLY");
              when(rs.getBoolean("auto_renew")).thenReturn(true);
              when(rs.getObject("last_invoice_id")).thenReturn(null);
              when(rs.getObject("override_plan_id")).thenReturn(null);
              when(rs.getString("override_reason")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();
    verify(jdbc).update(anyString(), eq("FREE"), eq("ACTIVE"), any(), eq(accountId));
  }
}
