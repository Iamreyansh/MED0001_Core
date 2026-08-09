package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceCompareResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.SetPreferredResult;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class JdbcDistributorSupplyItemStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcDistributorSupplyItemStore store;
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID dist = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcDistributorSupplyItemStore(jdbc);
  }

  @Test
  void upsertListCompareAndPreferred() throws Exception {
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    store.upsertFromGrn(pharmacy, dist, product, 1300, "1 free on 10", now);

    when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("product_id")).thenReturn(product);
              when(rs.getString("product_name")).thenReturn("Para");
              when(rs.getString("manufacturer")).thenReturn("Cipla");
              when(rs.getLong("purchase_price_paise")).thenReturn(1300L);
              when(rs.getString("scheme_description")).thenReturn("1 free on 10");
              when(rs.getLong("mrp_paise")).thenReturn(2250L);
              when(rs.getBoolean("is_preferred_source")).thenReturn(true);
              when(rs.getObject("distributor_id")).thenReturn(dist);
              when(rs.getString("firm_name")).thenReturn("Medico");
              List<Object> rows = new ArrayList<>();
              rows.add(mapper.mapRow(rs, 0));
              return rows;
            });

    assertThat(store.listByDistributor(pharmacy, dist, "para", 1, 20).items()).hasSize(1);

    PriceCompareResult compare = store.priceCompare(pharmacy, true, "para", 1, 20);
    assertThat(compare.products()).hasSize(1);
    assertThat(compare.products().get(0).offers()).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenReturn(List.of(UUID.randomUUID()));
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    Optional<SetPreferredResult> pref = store.setPreferred(pharmacy, dist, product, now);
    assertThat(pref).isPresent();
    assertThat(pref.get().previousPreferredId()).isNotNull();

    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    assertThat(store.setPreferred(pharmacy, dist, product, now)).isEmpty();
  }
}
