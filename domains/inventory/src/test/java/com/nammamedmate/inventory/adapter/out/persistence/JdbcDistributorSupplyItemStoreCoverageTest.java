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
import java.util.concurrent.atomic.AtomicInteger;
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
class JdbcDistributorSupplyItemStoreCoverageTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcDistributorSupplyItemStore store;
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID distA = UUID.randomUUID();
  private final UUID distB = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcDistributorSupplyItemStore(jdbc);
  }

  @Test
  void multiOfferCompareEmptyPageAndRank() throws Exception {
    when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(null);

    AtomicInteger offerCalls = new AtomicInteger();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              String sql = inv.getArgument(0);
              List<Object> rows = new ArrayList<>();
              if (sql.contains("firm_name")) {
                // price-compare offers: two distributors
                ResultSet rsA = mockOffer(distA, 1450L, null, false);
                ResultSet rsB = mockOffer(distB, 1300L, "1 free on 10", true);
                rows.add(mapper.mapRow(rsA, 0));
                rows.add(mapper.mapRow(rsB, 1));
                offerCalls.incrementAndGet();
              } else if (sql.contains("is_preferred_source")) {
                ResultSet rs = mockSupply(1300L, "1 free on 10", true);
                rows.add(mapper.mapRow(rs, 0));
              } else {
                // priceRank peers
                ResultSet cheap = mock(ResultSet.class);
                when(cheap.getLong("purchase_price_paise")).thenReturn(1000L);
                when(cheap.getString("scheme_description")).thenReturn(null);
                ResultSet mine = mock(ResultSet.class);
                when(mine.getLong("purchase_price_paise")).thenReturn(1300L);
                when(mine.getString("scheme_description")).thenReturn("1 free on 10");
                rows.add(mapper.mapRow(cheap, 0));
                rows.add(mapper.mapRow(mine, 1));
              }
              return rows;
            });

    assertThat(store.listByDistributor(pharmacy, distA, "  ", 1, 20).total()).isZero();
    assertThat(store.listByDistributor(pharmacy, distA, null, 1, 20).items().get(0).priceRank())
        .isGreaterThanOrEqualTo(1);

    PriceCompareResult blankQ = store.priceCompare(pharmacy, false, "   ", 1, 20);
    assertThat(blankQ.total()).isZero();
    PriceCompareResult nullQ = store.priceCompare(pharmacy, false, null, 1, 20);
    assertThat(nullQ.products()).hasSize(1);
    PriceCompareResult withQ = store.priceCompare(pharmacy, false, "para", 1, 20);
    assertThat(withQ.products()).hasSize(1);

    PriceCompareResult emptyPage = store.priceCompare(pharmacy, true, "x", 99, 20);
    assertThat(emptyPage.products()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of());
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    Optional<SetPreferredResult> pref = store.setPreferred(pharmacy, distA, product, now);
    assertThat(pref).isPresent();
    assertThat(pref.get().previousPreferredId()).isNull();
  }

  @Test
  void preferredMapperAndEqualRankAndLastPurchasePresent() throws Exception {
    when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              String sql = inv.getArgument(0);
              List<Object> rows = new ArrayList<>();
              if (sql.contains("scheme_description")
                  && !sql.contains("firm_name")
                  && !sql.contains("product_name")) {
                // priceRank peers only (no product_name column)
                ResultSet cheap = mock(ResultSet.class);
                when(cheap.getLong("purchase_price_paise")).thenReturn(1000L);
                when(cheap.getString("scheme_description")).thenReturn(null);
                ResultSet peer = mock(ResultSet.class);
                when(peer.getLong("purchase_price_paise")).thenReturn(1300L);
                when(peer.getString("scheme_description")).thenReturn(null);
                rows.add(mapper.mapRow(cheap, 0));
                rows.add(mapper.mapRow(peer, 1));
              } else if (sql.contains("product_name")) {
                ResultSet rs = mockSupply(1300L, null, false);
                rows.add(mapper.mapRow(rs, 0));
              } else {
                // empty peer set — covers empty for-loop branch in priceRank
              }
              return rows;
            });
    assertThat(store.listByDistributor(pharmacy, distA, null, 1, 20).items().get(0).priceRank())
        .isGreaterThanOrEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("distributor_id")).thenReturn(distB);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    assertThat(
            store.setPreferred(pharmacy, distA, product, now).orElseThrow().previousPreferredId())
        .isEqualTo(distB);
  }

  private ResultSet mockOffer(UUID dist, long price, String scheme, boolean preferred)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("product_id")).thenReturn(product);
    when(rs.getString("product_name")).thenReturn("Para");
    when(rs.getString("manufacturer")).thenReturn("Cipla");
    when(rs.getObject("distributor_id")).thenReturn(dist);
    when(rs.getString("firm_name")).thenReturn("Firm");
    when(rs.getLong("purchase_price_paise")).thenReturn(price);
    when(rs.getString("scheme_description")).thenReturn(scheme);
    when(rs.getLong("mrp_paise")).thenReturn(2250L);
    when(rs.getBoolean("is_preferred_source")).thenReturn(preferred);
    return rs;
  }

  private ResultSet mockSupply(long price, String scheme, boolean preferred) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("product_id")).thenReturn(product);
    when(rs.getString("product_name")).thenReturn("Para");
    when(rs.getString("manufacturer")).thenReturn("Cipla");
    when(rs.getLong("purchase_price_paise")).thenReturn(price);
    when(rs.getString("scheme_description")).thenReturn(scheme);
    when(rs.getLong("mrp_paise")).thenReturn(2250L);
    when(rs.getBoolean("is_preferred_source")).thenReturn(preferred);
    return rs;
  }
}
