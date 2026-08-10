package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.prescription.adapter.out.persistence.JdbcInventoryBanAdapter;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class JdbcInventoryBanAdapterCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void bansOrEmpty() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcInventoryBanAdapter adapter = new JdbcInventoryBanAdapter(jdbc);
    assertThat(adapter.banByDrugNameAndBatch(null, "B").batchesBanned()).isZero();
    assertThat(adapter.banByDrugNameAndBatch(" ", "B").batchesBanned()).isZero();
    assertThat(adapter.banByDrugNameAndBatch("D", null).batchesBanned()).isZero();
    assertThat(adapter.banByDrugNameAndBatch("D", " ").batchesBanned()).isZero();

    UUID pharmacy = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getObject("product_id")).thenReturn(product);
              return List.of(
                  ((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0),
                  ((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 1));
            });
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getInt("total_stock_units")).thenReturn(0);
              when(rs.getInt("total_batches")).thenReturn(0);
              when(rs.getDate("earliest_expiry")).thenReturn(null);
              when(rs.getLong("cost_value_paise")).thenReturn(0L);
              return ((ResultSetExtractor<?>) inv.getArgument(1)).extractData(rs);
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);

    var result = adapter.banByDrugNameAndBatch("Paracetamol 500mg", "PCM2024Q1");
    assertThat(result.batchesBanned()).isEqualTo(2);
    assertThat(result.pharmacyIds()).containsExactly(pharmacy);
    verify(jdbc, times(1)).query(anyString(), any(ResultSetExtractor.class), any(), any());
    verify(jdbc, times(1))
        .update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
    assertThat(adapter.banByDrugNameAndBatch("D", "B").batchesBanned()).isZero();
  }

  @Test
  @SuppressWarnings("unchecked")
  void refreshSkipsWhenAggregateEmpty() throws DataAccessException {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcInventoryBanAdapter adapter = new JdbcInventoryBanAdapter(jdbc);
    UUID pharmacy = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getObject("product_id")).thenReturn(product);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    AtomicInteger calls = new AtomicInteger();
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ((ResultSetExtractor<?>) inv.getArgument(1)).extractData(rs);
            });
    assertThat(adapter.banByDrugNameAndBatch("Drug", "B1").batchesBanned()).isEqualTo(1);
    assertThat(calls.get()).isEqualTo(1);
  }
}
