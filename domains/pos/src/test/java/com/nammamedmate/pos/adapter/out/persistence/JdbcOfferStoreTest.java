package com.nammamedmate.pos.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.OfferAppliesTo;
import com.nammamedmate.pos.domain.OfferRedemption;
import com.nammamedmate.pos.domain.OfferRedemptionChannel;
import com.nammamedmate.pos.domain.PharmacyOffer;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
class JdbcOfferStoreTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock JdbcTemplate jdbc;
  JdbcOfferStore store;
  UUID pharmacy = UUID.randomUUID();
  UUID offerId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    store = new JdbcOfferStore(jdbc);
  }

  @Test
  void crudAndQueries() throws Exception {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);

    PharmacyOffer offer = sampleOffer(List.of(UUID.randomUUID()));
    assertThat(store.insert(offer).id()).isEqualTo(offerId);
    assertThat(store.insert(sampleOffer(List.of())).id()).isEqualTo(offerId);
    assertThat(store.update(offer).title()).isEqualTo("Title");
    store.hardDelete(pharmacy, offerId);
    assertThat(store.couponExists(pharmacy, "CODE", null)).isFalse();
    assertThat(store.couponExists(pharmacy, "CODE", offerId)).isTrue();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> List.of(inv.getArgument(1, RowMapper.class).mapRow(mockOfferRs(null), 0)));
    assertThat(store.findById(pharmacy, offerId)).isPresent();
    assertThat(store.findByCoupon(pharmacy, "CODE")).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv ->
                List.of(inv.getArgument(1, RowMapper.class).mapRow(mockOfferRs(new UUID[0]), 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv ->
                List.of(
                    inv.getArgument(1, RowMapper.class)
                        .mapRow(mockOfferRs(new UUID[] {UUID.randomUUID()}), 0)));

    assertThat(store.list(pharmacy, "ACTIVE", LocalDate.of(2026, 7, 24), 1, 20).items()).hasSize(1);
    assertThat(store.list(pharmacy, "EXPIRED", LocalDate.of(2026, 7, 24), 1, 20).items())
        .hasSize(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> List.of(inv.getArgument(1, RowMapper.class).mapRow(mockOfferRs(null), 0)));
    assertThat(store.list(pharmacy, "ALL", LocalDate.of(2026, 7, 24), 1, 20).total()).isEqualTo(5L);
    assertThat(store.listActiveCounterOffers(pharmacy, LocalDate.of(2026, 7, 24))).hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);
    assertThat(store.kpi(pharmacy, LocalDate.of(2026, 7, 24)).activeCount()).isEqualTo(2);

    store.insertRedemption(
        new OfferRedemption(
            UUID.randomUUID(),
            offerId,
            pharmacy,
            UUID.randomUUID(),
            null,
            100L,
            OfferRedemptionChannel.COUNTER,
            NOW));
    store.incrementRedemptions(offerId, NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              UUID pid = UUID.randomUUID();
              UUID cat = UUID.randomUUID();
              when(rs.getObject("id")).thenReturn(pid);
              when(rs.getObject("category_id")).thenReturn(cat);
              when(rs.getString("name")).thenReturn("Antibiotics");
              Object row = mapper.mapRow(rs, 0);
              return List.of(row);
            });
    Map<UUID, UUID> cats = store.productCategoryIds(pharmacy, List.of(UUID.randomUUID()));
    assertThat(cats).hasSize(1);
    assertThat(store.productCategoryIds(pharmacy, null)).isEmpty();
    assertThat(store.productCategoryIds(pharmacy, List.of())).isEmpty();
    assertThat(store.categoryNames(null)).isEmpty();
    assertThat(store.categoryNames(List.of())).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(store.couponExists(pharmacy, "Z", null)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
    assertThat(store.couponExists(pharmacy, "Z", offerId)).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              UUID cat = UUID.randomUUID();
              when(rs.getObject("id")).thenReturn(cat);
              when(rs.getString("name")).thenReturn("Antibiotics");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.categoryNames(List.of(UUID.randomUUID()))).hasSize(1);

    // null category row → mapper returns null; loop skips null entries
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("category_id")).thenReturn(null);
              Object row = mapper.mapRow(rs, 0);
              return java.util.Collections.singletonList(row);
            });
    assertThat(store.productCategoryIds(pharmacy, List.of(UUID.randomUUID()))).isEmpty();

    assertThat(store.list(pharmacy, null, LocalDate.of(2026, 7, 24), 1, 20).items()).hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(store.list(pharmacy, "ALL", LocalDate.of(2026, 7, 24), 1, 20).total()).isZero();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(store.kpi(pharmacy, LocalDate.of(2026, 7, 24)).activeCount()).isZero();
    assertThat(store.kpi(pharmacy, LocalDate.of(2026, 7, 24)).totalRedemptions()).isZero();

    Array arrOk = mock(Array.class);
    when(arrOk.getArray()).thenReturn(new Object[] {UUID.randomUUID(), null});
    ResultSet rsOk = mock(ResultSet.class);
    when(rsOk.getArray("scope_ids")).thenReturn(arrOk);
    assertThat(JdbcOfferStore.readUuidArray(rsOk, "scope_ids")).hasSize(1);
    Array arrStr = mock(Array.class);
    when(arrStr.getArray()).thenReturn(new Object[] {UUID.randomUUID().toString()});
    ResultSet rsStr = mock(ResultSet.class);
    when(rsStr.getArray("scope_ids")).thenReturn(arrStr);
    assertThat(JdbcOfferStore.readUuidArray(rsStr, "scope_ids")).hasSize(1);
    Array arr2 = mock(Array.class);
    when(arr2.getArray()).thenReturn("not-array");
    ResultSet rsBad = mock(ResultSet.class);
    when(rsBad.getArray("scope_ids")).thenReturn(arr2);
    assertThat(JdbcOfferStore.readUuidArray(rsBad, "scope_ids")).isEmpty();
    assertThat(JdbcOfferStore.readUuidArray(mockOfferRs(null), "scope_ids")).isEmpty();
    assertThat(JdbcOfferStore.toUuidArrayLiteral(null)).isEqualTo("{}");
    assertThat(JdbcOfferStore.toUuidArrayLiteral(List.of())).isEqualTo("{}");
    UUID u = UUID.randomUUID();
    assertThat(JdbcOfferStore.toUuidArrayLiteral(List.of(u))).isEqualTo("{" + u + "}");
  }

  private PharmacyOffer sampleOffer(List<UUID> scope) {
    return new PharmacyOffer(
        offerId,
        pharmacy,
        "Title",
        "CODE",
        DiscountType.PERCENTAGE,
        10,
        OfferAppliesTo.CATEGORY,
        scope,
        true,
        true,
        true,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 31),
        0,
        0,
        NOW,
        NOW);
  }

  private ResultSet mockOfferRs(UUID[] scope) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(offerId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("title")).thenReturn("Title");
    when(rs.getString("coupon_code")).thenReturn("CODE");
    when(rs.getString("discount_type")).thenReturn("PERCENTAGE");
    when(rs.getLong("discount_value")).thenReturn(10L);
    when(rs.getString("applies_to")).thenReturn("ALL");
    when(rs.getBoolean("is_online")).thenReturn(false);
    when(rs.getBoolean("is_counter")).thenReturn(true);
    when(rs.getBoolean("is_active")).thenReturn(true);
    when(rs.getObject("valid_from", LocalDate.class)).thenReturn(LocalDate.of(2026, 7, 1));
    when(rs.getObject("valid_until", LocalDate.class)).thenReturn(LocalDate.of(2026, 7, 31));
    when(rs.getInt("max_redemptions")).thenReturn(0);
    when(rs.getInt("total_redemptions")).thenReturn(0);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    if (scope == null) {
      when(rs.getArray("scope_ids")).thenReturn(null);
    } else {
      Array arr = mock(Array.class);
      when(arr.getArray()).thenReturn(scope);
      when(rs.getArray("scope_ids")).thenReturn(arr);
    }
    return rs;
  }
}
