package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.domain.Distributor;
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
class JdbcDistributorStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcDistributorStore store;
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID id = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    store = new JdbcDistributorStore(jdbc);
  }

  @Test
  void findInsertUpdateDeactivateAndList() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(), 0));
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(
            anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(), any()))
        .thenReturn(LocalDate.of(2026, 7, 22));

    assertThat(store.findById(pharmacy, id)).isPresent();
    assertThat(store.findByIdIncludingDeleted(pharmacy, id)).isPresent();
    assertThat(store.findActiveByPhone(pharmacy, "+919876543210", null)).isPresent();
    assertThat(store.findActiveByPhone(pharmacy, "+919876543210", id)).isPresent();

    Distributor d = Distributor.minimal(id, pharmacy, "Firm", true, now);
    assertThat(store.insert(d)).isSameAs(d);
    assertThat(store.update(d)).isSameAs(d);
    store.deactivate(pharmacy, id, now);

    assertThat(store.list(pharmacy, true, "med", 1, 20).total()).isEqualTo(1);
    assertThat(store.list(pharmacy, null, null, 1, 20).items()).hasSize(1);
    assertThat(store.kpi(pharmacy).distributorCount()).isEqualTo(1);
    assertThat(store.outstandingPayablePaise(pharmacy, id)).isEqualTo(1L);
    assertThat(store.lastPurchaseDate(pharmacy, id)).isEqualTo(LocalDate.of(2026, 7, 22));
    assertThat(store.findActiveSystem(pharmacy)).isPresent();
    assertThat(store.insertSystem(d)).isSameAs(d);
    when(jdbc.queryForObject(anyString(), any(Class.class), any(), any())).thenReturn(Boolean.TRUE);
    assertThat(store.isSystem(pharmacy, id)).isTrue();
  }

  private ResultSet mockRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("firm_name")).thenReturn("Firm");
    when(rs.getString("contact_name")).thenReturn("Ramesh");
    when(rs.getString("phone")).thenReturn("+919876543210");
    when(rs.getString("email")).thenReturn("a@b.co");
    when(rs.getString("gstin")).thenReturn("27AABCM1234A1Z5");
    when(rs.getString("drug_licence_number")).thenReturn("DL-1");
    when(rs.getString("address")).thenReturn("Addr");
    when(rs.getInt("payment_terms_days")).thenReturn(30);
    when(rs.getLong("credit_limit_paise")).thenReturn(10000000L);
    when(rs.getBoolean("is_active")).thenReturn(true);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }

  @Test
  void mapDeletedDistributor() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs();
              when(rs.getBoolean("is_active")).thenReturn(false);
              when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findByIdIncludingDeleted(pharmacy, id).orElseThrow().deletedAt()).isNotNull();

    Distributor withDeleted =
        Distributor.minimal(id, pharmacy, "Firm", true, now).withDeletedAt(now);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    assertThat(store.insert(withDeleted)).isSameAs(withDeleted);
  }

  @Test
  void lastPurchaseDateWhenRowPresent() throws Exception {
    when(jdbc.query(
            anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              org.springframework.jdbc.core.ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getObject(1, LocalDate.class)).thenReturn(LocalDate.of(2026, 7, 1));
              return ex.extractData(rs);
            });
    assertThat(store.lastPurchaseDate(pharmacy, id)).isEqualTo(LocalDate.of(2026, 7, 1));
  }

  @Test
  void nullCountAndEmptyLastPurchaseDate() throws Exception {
    when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(jdbc.query(
            anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              org.springframework.jdbc.core.ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });

    assertThat(store.list(pharmacy, true, "  ", 1, 20).total()).isZero();
    assertThat(store.kpi(pharmacy).distributorCount()).isZero();
    assertThat(store.outstandingPayablePaise(pharmacy, id)).isZero();
    assertThat(store.lastPurchaseDate(pharmacy, id)).isNull();
  }
}
