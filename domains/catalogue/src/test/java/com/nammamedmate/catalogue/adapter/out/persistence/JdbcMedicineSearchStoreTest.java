package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.AutocompleteHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.AvailabilityHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.PharmacyMasterHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SearchHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.StockOffer;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SubstituteHit;
import java.math.BigDecimal;
import java.sql.ResultSet;
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
class JdbcMedicineSearchStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcMedicineSearchStore store;

  @BeforeEach
  void setUp() {
    store = new JdbcMedicineSearchStore(jdbc);
  }

  @Test
  void toTsQueryAndEmptyBranches() {
    assertThat(JdbcMedicineSearchStore.toTsQuery(null)).isEqualTo("nomatch");
    assertThat(JdbcMedicineSearchStore.toTsQuery("   ")).isEqualTo("nomatch");
    assertThat(JdbcMedicineSearchStore.toTsQuery("!!!")).isEqualTo("nomatch");
    assertThat(JdbcMedicineSearchStore.toTsQuery("Aug 625")).isEqualTo("aug:* | 625:*");
    assertThat(JdbcMedicineSearchStore.uuidArrayLiteral(List.of(UUID.randomUUID())))
        .startsWith("{")
        .endsWith("}");
    assertThat(store.bestOffers(List.of(), null, null, false)).isEmpty();
    assertThat(store.bestOffers(null, null, null, false)).isEmpty();
    assertThat(store.findSubstitutes(List.of())).isEmpty();
    assertThat(store.findSubstitutes(null)).isEmpty();
    assertThat(store.checkAvailability(UUID.randomUUID(), List.of())).isEmpty();
    assertThat(store.checkAvailability(UUID.randomUUID(), null)).isEmpty();
    assertThat(new MedicineSearchStore.SearchPage(null, 0).rows()).isEmpty();
    assertThat(new MedicineSearchStore.PharmacyMasterPage(null, 0).rows()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void searchAutocompleteDidYouMeanStockingPharmacySearch() throws Exception {
    UUID med = UUID.randomUUID();
    UUID cat = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    UUID zone = UUID.randomUUID();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              String sql = inv.getArgument(0);
              if (sql.contains("relevance_score")) {
                when(rs.getObject("id")).thenReturn(med);
                when(rs.getString("name")).thenReturn("Augmentin");
                when(rs.getString("salt_composition")).thenReturn("Amox");
                when(rs.getString("manufacturer")).thenReturn("GSK");
                when(rs.getString("category_name")).thenReturn("Antibiotics");
                when(rs.getString("category_slug")).thenReturn("antibiotics");
                when(rs.getString("form")).thenReturn("TABLET");
                when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("10"));
                when(rs.getString("pack_unit")).thenReturn("TABLET");
                when(rs.getString("schedule")).thenReturn("H");
                when(rs.getBoolean("is_rx_only")).thenReturn(true);
                when(rs.getLong("mrp_paise")).thenReturn(21850L);
                when(rs.getDouble("relevance_score")).thenReturn(0.9);
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("ILIKE ?")
                  && sql.contains("LIMIT ?")
                  && !sql.contains("LEFT JOIN")) {
                when(rs.getObject("id")).thenReturn(med);
                when(rs.getString("name")).thenReturn("Augmentin");
                when(rs.getString("manufacturer")).thenReturn("GSK");
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("similarity(name") && sql.contains("LIMIT 1")) {
                when(rs.getString("name")).thenReturn("Augmentin");
                return List.of(mapper.mapRow(rs, 0));
              }
              if (sql.contains("ORDER BY pcm.pharmacy_price_paise")) {
                when(rs.getObject("master_medicine_id")).thenReturn(med);
                when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
                when(rs.getString("pharmacy_name")).thenReturn("Shop");
                when(rs.getLong("pharmacy_price_paise")).thenReturn(20000L);
                when(rs.getInt("stock_quantity")).thenReturn(3);
                return List.of(mapper.mapRow(rs, 0));
              }
              return List.of();
            });

    var page = store.search("aug", cat, "H", true, true, 1, 20);
    assertThat(page.total()).isEqualTo(0);
    assertThat(page.rows().getFirst()).isInstanceOf(SearchHit.class);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
    store.search("aug", null, null, null, false, 1, 20);
    store.search("aug", null, "  ", false, true, 1, 20);

    assertThat(store.autocomplete("aug", 10).getFirst()).isInstanceOf(AutocompleteHit.class);
    assertThat(store.didYouMean("aug")).contains("Augmentin");
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.didYouMean("zzz")).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("master_medicine_id")).thenReturn(med);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("pharmacy_name")).thenReturn("Shop");
              when(rs.getLong("pharmacy_price_paise")).thenReturn(20000L);
              when(rs.getInt("stock_quantity")).thenReturn(3);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.stockingOffers(med, zone, false)).hasSize(1);
    assertThat(store.stockingOffers(med, null, false)).hasSize(1);
    assertThat(store.stockingOffers(med, zone, true)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("Crocin");
              when(rs.getString("salt_composition")).thenReturn("Para");
              when(rs.getString("manufacturer")).thenReturn("GSK");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("20"));
              when(rs.getString("schedule")).thenReturn("OTC");
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getLong("mrp_paise")).thenReturn(2250L);
              when(rs.getObject("pharmacy_price_paise")).thenReturn(null);
              when(rs.getObject("stock_quantity")).thenReturn(null);
              when(rs.getObject("mapping_id")).thenReturn(null);
              when(rs.getObject("is_visible")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    var master = store.searchMasterForPharmacy(pharmacy, "crocin", false, 1, 20);
    assertThat(master.total()).isEqualTo(0);
    assertThat(master.rows().getFirst().mapped()).isFalse();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("Crocin");
              when(rs.getString("salt_composition")).thenReturn("Para");
              when(rs.getString("manufacturer")).thenReturn("GSK");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("20"));
              when(rs.getString("schedule")).thenReturn("OTC");
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getLong("mrp_paise")).thenReturn(2250L);
              when(rs.getObject("pharmacy_price_paise")).thenReturn(2100L);
              when(rs.getObject("stock_quantity")).thenReturn(5);
              when(rs.getInt("stock_quantity")).thenReturn(5);
              when(rs.getObject("mapping_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("is_visible")).thenReturn(false);
              when(rs.getBoolean("is_visible")).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    var inStock = store.searchMasterForPharmacy(pharmacy, "crocin", true, 1, 20);
    assertThat(inStock.rows().getFirst()).isInstanceOf(PharmacyMasterHit.class);
    assertThat(inStock.rows().getFirst().visible()).isFalse();
  }

  @Test
  @SuppressWarnings("unchecked")
  void bestOffersSubstitutesAvailability() throws Exception {
    UUID med = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    UUID zone = UUID.randomUUID();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("master_medicine_id")).thenReturn(med);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("pharmacy_name")).thenReturn("Shop");
              when(rs.getLong("pharmacy_price_paise")).thenReturn(100L);
              when(rs.getInt("stock_quantity")).thenReturn(2);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.bestOffers(List.of(med), zone, pharmacy, false).getFirst())
        .isInstanceOf(StockOffer.class);
    assertThat(store.bestOffers(List.of(med), null, null, false).getFirst().inStock()).isTrue();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              String sql = inv.getArgument(0);
              if (sql.contains("salt_composition")) {
                when(rs.getObject("id")).thenReturn(med);
                when(rs.getString("name")).thenReturn("A");
                when(rs.getString("salt_composition")).thenReturn("S");
                when(rs.getString("manufacturer")).thenReturn("M");
                when(rs.getString("form")).thenReturn("TABLET");
                when(rs.getBigDecimal("pack_size")).thenReturn(BigDecimal.ONE);
                when(rs.getString("schedule")).thenReturn("H");
                when(rs.getBoolean("is_rx_only")).thenReturn(true);
                when(rs.getLong("mrp_paise")).thenReturn(100L);
                return List.of(mapper.mapRow(rs, 0));
              }
              when(rs.getObject("master_medicine_id")).thenReturn(med);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("pharmacy_name")).thenReturn("Shop");
              when(rs.getLong("pharmacy_price_paise")).thenReturn(100L);
              when(rs.getInt("stock_quantity")).thenReturn(0);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findSubstitutes(List.of(med)).getFirst()).isInstanceOf(SubstituteHit.class);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("A");
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getInt("stock_quantity")).thenReturn(2);
              when(rs.getObject("pharmacy_price_paise")).thenReturn(100L);
              return List.of(mapper.mapRow(rs, 0));
            });
    AvailabilityHit avail = store.checkAvailability(pharmacy, List.of(med)).getFirst();
    assertThat(avail.inStock()).isTrue();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("A");
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getInt("stock_quantity")).thenReturn(0);
              when(rs.getObject("pharmacy_price_paise")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.checkAvailability(pharmacy, List.of(med)).getFirst().inStock()).isFalse();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("master_medicine_id")).thenReturn(med);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("pharmacy_name")).thenReturn("Shop");
              when(rs.getLong("pharmacy_price_paise")).thenReturn(100L);
              when(rs.getInt("stock_quantity")).thenReturn(0);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.bestOffers(List.of(med), zone, pharmacy, false).getFirst().inStock())
        .isFalse();
    assertThat(store.bestOffers(List.of(med), zone, pharmacy, true)).hasSize(1);
  }
}
