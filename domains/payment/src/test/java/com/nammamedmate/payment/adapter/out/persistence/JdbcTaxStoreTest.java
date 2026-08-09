package com.nammamedmate.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.payment.application.port.out.TaxStorePort.TaxFilingRecord;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsRegisterRecord;
import com.nammamedmate.payment.domain.TaxFilingStatuses;
import com.nammamedmate.payment.domain.TaxFilingTypes;
import java.sql.Array;
import java.sql.Date;
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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcTaxStoreTest {

  @Mock private JdbcTemplate jdbc;
  @Mock private ResultSet rs;
  @Mock private Array sqlArray;

  private final Instant now = Instant.parse("2026-08-09T10:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void filingCrudAndFilters() throws Exception {
    JdbcTaxStore store = new JdbcTaxStore(jdbc);
    UUID id = UUID.randomUUID();
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<TaxFilingRecord> mapper = inv.getArgument(1);
              stubFilingRs(id);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findFiling(id)).isPresent();
    assertThat(store.findFilingByTypeAndPeriod(TaxFilingTypes.GSTR_8, "2026-07")).isPresent();

    lenient().when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    store.listFilings(2026, null);
    store.listFilings(2026, "OVERDUE");
    store.listFilings(2026, "PENDING");
    store.listFilings(null, "FILED");

    store.insertFiling(
        new TaxFilingRecord(
            id,
            TaxFilingTypes.GSTR_8,
            "2026-07",
            LocalDate.of(2026, 8, 10),
            TaxFilingStatuses.PENDING,
            now,
            null,
            "n",
            null,
            null,
            now,
            now));
    store.insertFiling(
        new TaxFilingRecord(
            UUID.randomUUID(),
            TaxFilingTypes.GSTR_8,
            "2026-06",
            LocalDate.of(2026, 7, 10),
            TaxFilingStatuses.PENDING,
            null,
            null,
            "n",
            null,
            null,
            now,
            now));
    store.listFilings(2026, " ");
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.listTcs("2026-07", null, 10, 0).total()).isZero();
    store.markFiled(id, now, "ARN", "n", UUID.randomUUID(), now);
    store.appendGeneratedFile(id, "{\"url\":\"x\"}", now);
    store.markOverduePending(LocalDate.of(2026, 8, 9), now);
    verify(jdbc, atLeastOnce()).update(anyString(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void tcsUpsertListAndAggregates() throws Exception {
    JdbcTaxStore store = new JdbcTaxStore(jdbc);
    UUID pharmacyId = UUID.randomUUID();
    UUID settlementId = UUID.randomUUID();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacyId), eq("2026-07")))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<TcsRegisterRecord> mapper = inv.getArgument(1);
              stubTcsRs(pharmacyId, settlementId);
              return List.of(mapper.mapRow(rs, 0));
            });
    store.upsertTcsOnRelease(pharmacyId, "2026-07", "P", "G", "PAN", settlementId, 1000, 10, now);
    store.upsertTcsOnRelease(pharmacyId, "2026-07", "P", "G", "PAN", settlementId, 1000, 10, now);
    store.upsertTcsOnRelease(
        pharmacyId, "2026-07", null, null, null, UUID.randomUUID(), 500, 5, now);

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq("2026-07")))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              lenient().when(rs.getLong(1)).thenReturn(100L);
              lenient().when(rs.getLong(2)).thenReturn(1L);
              lenient().when(rs.getInt(3)).thenReturn(1);
              return mapper.mapRow(rs, 0);
            })
        .thenThrow(new EmptyResultDataAccessException(1));
    assertThat(store.tcsTotals("2026-07").pharmaciesCount()).isEqualTo(1);
    assertThat(store.tcsTotals("2026-07").totalGmvPaise()).isZero();

    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(0L, 3L, null, 42L, null, 7L);
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of());
    assertThat(store.listTcs("2026-07", null, 10, 0).total()).isZero();
    assertThat(store.listTcs("2026-07", pharmacyId, 10, 0).total()).isEqualTo(3);
    assertThat(store.listTcsAll("2026-07")).isEmpty();
    store.linkTcsToFiling("2026-07", UUID.randomUUID(), now);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("pan")).thenReturn("P");
              when(rs.getLong("commission_paise")).thenReturn(100L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.commissionByPharmacy(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
        .hasSize(1);
    assertThat(store.totalCommissionPaise(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
        .isZero();
    assertThat(store.totalCommissionPaise(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
        .isEqualTo(42L);
    assertThat(store.gatewayFeesPaise(now, now.plusSeconds(1))).isZero();
    assertThat(store.gatewayFeesPaise(now, now.plusSeconds(1))).isEqualTo(7L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void findEmptyAndObjectArraySettlementIds() throws Exception {
    JdbcTaxStore store = new JdbcTaxStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.findFiling(UUID.randomUUID())).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.findFilingByTypeAndPeriod("GSTR-8", "2026-07")).isEmpty();
    assertThat(store.findTcs(UUID.randomUUID(), "2026-07")).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<TcsRegisterRecord> mapper = inv.getArgument(1);
              UUID pharmacyId = UUID.randomUUID();
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
              when(rs.getString("month")).thenReturn("2026-07");
              when(rs.getString("pharmacy_name")).thenReturn("P");
              when(rs.getString("gstin")).thenReturn("");
              when(rs.getString("pan")).thenReturn("");
              when(rs.getLong("gmv_paise")).thenReturn(0L);
              when(rs.getLong("tcs_collected_paise")).thenReturn(0L);
              when(rs.getLong("cgst_tcs_paise")).thenReturn(0L);
              when(rs.getLong("sgst_tcs_paise")).thenReturn(0L);
              when(rs.getObject("gstr8_filing_id")).thenReturn(null);
              when(rs.getArray("settlement_ids")).thenReturn(sqlArray);
              when(sqlArray.getArray())
                  .thenReturn(new Object[] {UUID.randomUUID().toString(), null});
              return List.of(mapper.mapRow(rs, 0));
            });
    Optional<TcsRegisterRecord> row = store.findTcs(UUID.randomUUID(), "2026-07");
    assertThat(row).isPresent();
    assertThat(row.get().settlementIds()).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<TcsRegisterRecord> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("month")).thenReturn("2026-07");
              when(rs.getString("pharmacy_name")).thenReturn("P");
              when(rs.getString("gstin")).thenReturn("");
              when(rs.getString("pan")).thenReturn("");
              when(rs.getLong("gmv_paise")).thenReturn(0L);
              when(rs.getLong("tcs_collected_paise")).thenReturn(0L);
              when(rs.getLong("cgst_tcs_paise")).thenReturn(0L);
              when(rs.getLong("sgst_tcs_paise")).thenReturn(0L);
              when(rs.getObject("gstr8_filing_id")).thenReturn(null);
              when(rs.getArray("settlement_ids")).thenReturn(sqlArray);
              when(sqlArray.getArray()).thenReturn(new Object[] {UUID.randomUUID()});
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findTcs(UUID.randomUUID(), "2026-07").get().settlementIds()).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<TcsRegisterRecord> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("month")).thenReturn("2026-07");
              when(rs.getString("pharmacy_name")).thenReturn("P");
              when(rs.getString("gstin")).thenReturn("");
              when(rs.getString("pan")).thenReturn("");
              when(rs.getLong("gmv_paise")).thenReturn(0L);
              when(rs.getLong("tcs_collected_paise")).thenReturn(0L);
              when(rs.getLong("cgst_tcs_paise")).thenReturn(0L);
              when(rs.getLong("sgst_tcs_paise")).thenReturn(0L);
              when(rs.getObject("gstr8_filing_id")).thenReturn(null);
              when(rs.getArray("settlement_ids")).thenReturn(sqlArray);
              when(sqlArray.getArray()).thenReturn("not-an-array");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findTcs(UUID.randomUUID(), "2026-07").get().settlementIds()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<TcsRegisterRecord> mapper = inv.getArgument(1);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("month")).thenReturn("2026-07");
              when(rs.getString("pharmacy_name")).thenReturn(null);
              when(rs.getString("gstin")).thenReturn(null);
              when(rs.getString("pan")).thenReturn(null);
              when(rs.getLong("gmv_paise")).thenReturn(0L);
              when(rs.getLong("tcs_collected_paise")).thenReturn(0L);
              when(rs.getLong("cgst_tcs_paise")).thenReturn(0L);
              when(rs.getLong("sgst_tcs_paise")).thenReturn(0L);
              when(rs.getObject("gstr8_filing_id")).thenReturn(null);
              when(rs.getArray("settlement_ids")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findTcs(UUID.randomUUID(), "2026-07").get().settlementIds()).isEmpty();
  }

  private void stubFilingRs(UUID id) throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("filing_type")).thenReturn(TaxFilingTypes.GSTR_8);
    when(rs.getString("period")).thenReturn("2026-07");
    when(rs.getDate("due_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 8, 10)));
    when(rs.getString("status")).thenReturn(TaxFilingStatuses.PENDING);
    when(rs.getTimestamp("filed_at")).thenReturn(null);
    when(rs.getString("reference_number")).thenReturn(null);
    when(rs.getString("notes")).thenReturn("n");
    when(rs.getObject("marked_by")).thenReturn(null);
    when(rs.getString("generated_files")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
  }

  private void stubTcsRs(UUID pharmacyId, UUID settlementId) throws Exception {
    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
    when(rs.getString("month")).thenReturn("2026-07");
    when(rs.getString("pharmacy_name")).thenReturn("P");
    when(rs.getString("gstin")).thenReturn("G");
    when(rs.getString("pan")).thenReturn("PAN");
    when(rs.getLong("gmv_paise")).thenReturn(1000L);
    when(rs.getLong("tcs_collected_paise")).thenReturn(10L);
    when(rs.getLong("cgst_tcs_paise")).thenReturn(5L);
    when(rs.getLong("sgst_tcs_paise")).thenReturn(5L);
    when(rs.getObject("gstr8_filing_id")).thenReturn(null);
    when(rs.getArray("settlement_ids")).thenReturn(sqlArray);
    when(sqlArray.getArray()).thenReturn(new UUID[] {settlementId});
  }
}
