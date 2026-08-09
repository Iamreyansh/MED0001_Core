package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.ListResult;
import com.nammamedmate.inventory.domain.ReorderSuggestionSnapshot;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
class JdbcReorderSuggestionStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcReorderSuggestionStore store;
  private final UUID pharmacy = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcReorderSuggestionStore(jdbc);
  }

  @Test
  @SuppressWarnings("unchecked")
  void coversQueriesAndReplace() throws Exception {
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1L);

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              return List.of(mapper.mapRow(rs, 0));
            });

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              String sql = inv.getArgument(0);
              if (sql.contains("MAX(snapshot_date)")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getDate("d")).thenReturn(Date.valueOf(LocalDate.of(2026, 8, 9)));
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("MAX(created_at)")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getTimestamp("t")).thenReturn(Timestamp.from(now));
                return List.of(mapper.mapRow(rs, 0));
              }
              return List.of(mapper.mapRow(mockLowStockRs(), 0));
            });

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              String sql = inv.getArgument(0);
              if (sql.contains("distributor_supply_item")) {
                return List.of(mapper.mapRow(mockOfferRs(), 0));
              }
              return List.of(mapper.mapRow(mockSuggestionRs(), 0));
            });

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockSuggestionRs(), 0));
            });

    assertThat(store.listLowStockProducts(pharmacy)).hasSize(1);
    assertThat(store.listActiveOffers(pharmacy, UUID.randomUUID())).hasSize(1);
    assertThat(store.latestSnapshotDate(pharmacy)).isPresent();
    assertThat(store.latestRefreshedAt(pharmacy)).isPresent();
    assertThat(store.listPharmacyIdsWithLowStock()).contains(pharmacy);
    assertThat(store.countLatest(pharmacy)).isEqualTo(1L);

    ListResult list = store.listLatest(pharmacy, 1, 50);
    assertThat(list.rows()).hasSize(1);

    ReorderSuggestionSnapshot snap =
        new ReorderSuggestionSnapshot(
            UUID.randomUUID(),
            pharmacy,
            UUID.randomUUID(),
            40,
            60,
            null,
            null,
            null,
            LocalDate.of(2026, 8, 9),
            now);
    assertThat(store.replaceSnapshots(pharmacy, LocalDate.of(2026, 8, 9), List.of(snap)))
        .isEqualTo(1);
    assertThat(store.replaceSnapshots(pharmacy, LocalDate.of(2026, 8, 9), List.of())).isEqualTo(0);

    // landed price present branch
    ResultSet withLanded = mockSuggestionRs();
    when(withLanded.getObject("landed_price_paise")).thenReturn(1182L);
    when(withLanded.getLong("landed_price_paise")).thenReturn(1182L);
    when(withLanded.getObject("best_distributor_id")).thenReturn(UUID.randomUUID());
    when(withLanded.getBigDecimal("days_of_cover")).thenReturn(new BigDecimal("3.0"));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(withLanded, 0));
            });
    assertThat(store.listLatest(pharmacy, 1, 50).rows().get(0).snapshot().landedPricePaise())
        .isEqualTo(1182L);
  }

  @Test
  void listLatest_emptyWhenNoSnapshot() {
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.listLatest(pharmacy, 1, 20).total()).isEqualTo(0);
    assertThat(store.countLatest(pharmacy)).isEqualTo(0);
  }

  @Test
  void replaceSnapshots_nullRows() {
    when(jdbc.update(anyString(), any(), any())).thenReturn(0);
    assertThat(store.replaceSnapshots(pharmacy, LocalDate.of(2026, 8, 9), null)).isEqualTo(0);
  }

  @Test
  void countForDate_nullQueryResult() {
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getDate("d")).thenReturn(Date.valueOf(LocalDate.of(2026, 8, 9)));
              java.util.ArrayList<Object> out = new java.util.ArrayList<>();
              out.add(mapper.mapRow(rs, 0));
              return out;
            });
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(null);
    assertThat(store.countLatest(pharmacy)).isEqualTo(0L);
  }

  @Test
  void listResultNullRowsCopy() {
    assertThat(new ReorderSuggestionStore.ListResult(null, 0).rows()).isEmpty();
    assertThat(new PurchaseOrderStore.ListResult(null, 0).rows()).isEmpty();
  }

  @Test
  void latestSnapshotDate_nullRow() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getDate("d")).thenReturn(null);
              when(rs.getTimestamp("t")).thenReturn(null);
              java.util.ArrayList<Object> out = new java.util.ArrayList<>();
              out.add(mapper.mapRow(rs, 0));
              return out;
            });
    assertThat(store.latestSnapshotDate(pharmacy)).isEmpty();
    assertThat(store.latestRefreshedAt(pharmacy)).isEmpty();
  }

  private ResultSet mockLowStockRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getString("name")).thenReturn("Para");
    when(rs.getString("manufacturer")).thenReturn("Cipla");
    when(rs.getInt("total_stock_units")).thenReturn(40);
    when(rs.getInt("reorder_level")).thenReturn(60);
    when(rs.getLong("mrp_paise")).thenReturn(2000L);
    when(rs.getInt("gst_pct")).thenReturn(12);
    return rs;
  }

  private ResultSet mockOfferRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("distributor_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("firm_name")).thenReturn("Medico");
    when(rs.getString("phone")).thenReturn("+919876543210");
    when(rs.getLong("purchase_price_paise")).thenReturn(1182L);
    when(rs.getString("scheme_description")).thenReturn(null);
    return rs;
  }

  private ResultSet mockSuggestionRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("product_id")).thenReturn(UUID.randomUUID());
    when(rs.getInt("current_stock")).thenReturn(40);
    when(rs.getInt("reorder_level")).thenReturn(60);
    when(rs.getBigDecimal("days_of_cover")).thenReturn(null);
    when(rs.getObject("best_distributor_id")).thenReturn(null);
    when(rs.getObject("landed_price_paise")).thenReturn(null);
    when(rs.getDate("snapshot_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 8, 9)));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("product_name")).thenReturn("Para");
    when(rs.getString("manufacturer")).thenReturn("Cipla");
    when(rs.getString("best_distributor_name")).thenReturn(null);
    when(rs.getString("best_distributor_phone")).thenReturn(null);
    return rs;
  }
}
