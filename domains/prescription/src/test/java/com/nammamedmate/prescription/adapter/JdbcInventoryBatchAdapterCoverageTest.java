package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.prescription.adapter.out.persistence.JdbcInventoryBatchAdapter;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcInventoryBatchAdapterCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void findsOpeningOrEmpty() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcInventoryBatchAdapter adapter = new JdbcInventoryBatchAdapter(jdbc);
    UUID pharmacy = UUID.randomUUID();
    assertThat(adapter.findOpeningStock(pharmacy, null)).isEmpty();
    assertThat(adapter.findOpeningStock(pharmacy, "  ")).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq("Alprazolam")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("batch_number")).thenReturn("BX1");
              when(rs.getInt("qty")).thenReturn(500);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(adapter.findOpeningStock(pharmacy, "Alprazolam"))
        .hasValueSatisfying(
            o -> {
              assertThat(o.batchNo()).isEqualTo("BX1");
              assertThat(o.quantity()).isEqualTo(500);
            });
  }
}
