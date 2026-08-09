package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.ModuleUsageMonthly;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
class JdbcSaasModuleUsageStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock Array sqlArray;

  @Test
  @SuppressWarnings("unchecked")
  void coversQueriesAndMutations() throws Exception {
    JdbcSaasModuleUsageStore store = new JdbcSaasModuleUsageStore(jdbc);
    UUID accountId = UUID.randomUUID();
    UUID pharmacyId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    LocalDate month = LocalDate.of(2026, 7, 1);

    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("account_id")).thenReturn(accountId);
    when(rs.getString("module_id")).thenReturn("mod_billing");
    when(rs.getString("module_name")).thenReturn("Billing");
    when(rs.getString("module_code")).thenReturn("BILLING");
    when(rs.getString("group_name")).thenReturn("CORE");
    when(rs.getArray("plan_names")).thenReturn(sqlArray);
    when(sqlArray.getArray()).thenReturn(new String[] {"STARTER"});
    when(rs.getBoolean("enabled")).thenReturn(true);
    when(rs.getBoolean("module_enabled")).thenReturn(true);
    when(rs.getString("reason")).thenReturn("beta");
    when(rs.getObject("toggled_by")).thenReturn(UUID.randomUUID());
    when(rs.getTimestamp(anyString())).thenReturn(Timestamp.from(now));
    when(rs.getDate("event_month")).thenReturn(java.sql.Date.valueOf(month));
    when(rs.getInt("event_count")).thenReturn(3);
    when(rs.getString("pharmacy_name")).thenReturn("Shop");
    when(rs.getString("name")).thenReturn("Ramesh");
    when(rs.next()).thenReturn(true, false);

    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    lenient()
        .when(
            jdbc.query(
                anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              var extractor = inv.getArgument(1);
              return ((org.springframework.jdbc.core.ResultSetExtractor<?>) extractor)
                  .extractData(rs);
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(2L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(7L);

    Optional<ModuleMatrixRow> mod = store.findModuleById("mod_billing");
    assertThat(mod).isPresent();
    assertThat(store.listModuleMatrix()).isNotEmpty();
    assertThat(store.countEligibleAccounts("mod_billing")).isEqualTo(5L);
    assertThat(store.countAccountsUsing("mod_billing", month)).isEqualTo(2L);
    assertThat(store.listEligibleNotUsing("mod_billing", month)).isNotEmpty();
    assertThat(store.listPerAccountUsage("mod_billing", month)).isNotEmpty();

    Optional<AccountModuleOverride> ov = store.findOverride(accountId, "mod_billing");
    assertThat(ov).isPresent();
    AccountModuleOverride upserted =
        store.upsertOverride(accountId, "mod_billing", true, "beta", UUID.randomUUID(), now);
    assertThat(upserted.enabled()).isTrue();

    store.incrementUsage(accountId, "mod_billing", month, now);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());

    List<ModuleUsageMonthly> usage = store.listAccountUsageMonth(accountId, month);
    assertThat(usage).isNotEmpty();
    assertThat(store.maxLastActive(accountId)).isEqualTo(now);
    assertThat(store.countModulesUsedSince(accountId, now)).isEqualTo(2);
    assertThat(store.countActiveStaff(pharmacyId)).isEqualTo(5L);
    assertThat(store.listActiveStaffNames(pharmacyId)).contains("Ramesh");
    assertThat(store.countInvoicesThisMonth(pharmacyId, now, now)).isEqualTo(7L);
    assertThat(store.pharmacyName(pharmacyId)).isEqualTo("Ramesh");
    assertThat(store.listNudgeTargetAccountIds("mod_billing", now)).isNotEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullCountsAndEmptyPharmacy() throws Exception {
    JdbcSaasModuleUsageStore store = new JdbcSaasModuleUsageStore(jdbc);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    when(jdbc.query(
            anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              when(rs.next()).thenReturn(false);
              return ((org.springframework.jdbc.core.ResultSetExtractor<?>) inv.getArgument(1))
                  .extractData(rs);
            });

    assertThat(store.countEligibleAccounts("x")).isEqualTo(0L);
    assertThat(store.countAccountsUsing("x", LocalDate.of(2026, 7, 1))).isEqualTo(0L);
    assertThat(store.countModulesUsedSince(UUID.randomUUID(), Instant.now())).isEqualTo(0);
    assertThat(store.countActiveStaff(UUID.randomUUID())).isEqualTo(0L);
    assertThat(store.countInvoicesThisMonth(UUID.randomUUID(), Instant.now(), Instant.now()))
        .isEqualTo(0L);
    assertThat(store.pharmacyName(UUID.randomUUID())).isNull();
    assertThat(store.maxLastActive(UUID.randomUUID())).isNull();

    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("account_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("module_id")).thenReturn("mod_x");
    when(rs.getString("module_name")).thenReturn("X");
    when(rs.getString("module_code")).thenReturn("X");
    when(rs.getString("group_name")).thenReturn("CORE");
    when(rs.getArray("plan_names")).thenReturn(null);
    when(rs.getDate("event_month")).thenReturn(null);
    when(rs.getInt("event_count")).thenReturn(0);
    when(rs.getTimestamp("last_active_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    ModuleMatrixRow row = store.listModuleMatrix().getFirst();
    assertThat(row.planNames()).isEmpty();
    ModuleUsageMonthly usageRow =
        store.listAccountUsageMonth(UUID.randomUUID(), LocalDate.of(2026, 7, 1)).getFirst();
    assertThat(usageRow.eventMonth()).isNull();
    assertThat(usageRow.lastActiveAt()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    AccountModuleOverride fallback =
        store.upsertOverride(
            UUID.randomUUID(), "mod_x", false, "hold", UUID.randomUUID(), Instant.now());
    assertThat(fallback.enabled()).isFalse();
  }
}
