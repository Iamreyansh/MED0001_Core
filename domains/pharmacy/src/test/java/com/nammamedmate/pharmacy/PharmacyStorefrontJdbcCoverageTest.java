package com.nammamedmate.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.out.cache.RedisZonePharmacyCacheClient;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubCatalogueVisibilityClient;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubPharmacyCatalogueStatsClient;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcCataloguePauseStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyStorefrontStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcZoneStore;
import com.nammamedmate.pharmacy.application.port.out.CataloguePauseStore.CataloguePauseRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyStorefrontJdbcCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final UUID PID = Ids.newId();
  private static final UUID ZONE = UUID.fromString("a0000001-0000-4000-8000-000000000001");

  @Test
  void jdbcPharmacyStorefrontStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPharmacyStorefrontStore store = new JdbcPharmacyStorefrontStore(jdbc);

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID)))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(storefrontRs(), 0));
            });

    assertThat(store.findStorefront(PID)).isEmpty();
    assertThat(store.findStorefront(PID)).isPresent();
    store.updateOnlineStatus(PID, false, true, NOW);
    store.updateZone(PID, ZONE, NOW);
  }

  @Test
  void jdbcCataloguePauseStore() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCataloguePauseStore store = new JdbcCataloguePauseStore(jdbc);
    UUID pauseId = Ids.newId();

    when(jdbc.query(any(String.class), any(RowMapper.class), eq(PID)))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(pauseRs(pauseId), 0));
            });
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(Timestamp.from(NOW))))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(pauseRs(pauseId), 0));
            });

    assertThat(store.findActivePause(PID)).isEmpty();
    assertThat(store.findActivePause(PID)).isPresent();
    store.insert(
        new CataloguePauseRow(
            pauseId, PID, "reason", NOW, NOW.plusSeconds(3600), null, 10, Ids.newId()));
    store.markResumed(pauseId, NOW);
    assertThat(store.findDueForResume(NOW)).hasSize(1);
  }

  @Test
  void jdbcZoneStoreFindById() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcZoneStore store = new JdbcZoneStore(jdbc);
    when(jdbc.query(any(String.class), any(RowMapper.class), eq(ZONE)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(zoneFindRs(), 0));
            });
    assertThat(store.findById(ZONE)).isPresent();
    assertThat(store.findById(ZONE).orElseThrow().name()).isEqualTo("Koramangala Zone");
  }

  @Test
  void jdbcZoneStoreListForAdmin() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcZoneStore store = new JdbcZoneStore(jdbc);

    when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(zoneRs(), 0));
            });

    assertThat(store.listForAdmin("Bengaluru", true)).hasSize(1);
    assertThat(store.listForAdmin(null, null)).hasSize(1);
    assertThat(store.listForAdmin("", true)).hasSize(1);
    assertThat(store.listForAdmin("  ", false)).hasSize(1);
    assertThat(store.listForAdmin(null, true)).hasSize(1);

    when(jdbc.query(any(String.class), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(zoneRsNullCreated(), 0));
            });
    assertThat(store.listForAdmin("Bengaluru", true).get(0).createdAt()).isNull();
  }

  @Test
  void redisZonePharmacyCacheClient() {
    @SuppressWarnings("unchecked")
    ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> provider =
        mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    RedisZonePharmacyCacheClient client = new RedisZonePharmacyCacheClient(provider);
    client.invalidate(ZONE);
    assertThat(client.wasInvalidatedLocally(ZONE)).isTrue();
    assertThat(RedisZonePharmacyCacheClient.cacheKey(ZONE))
        .isEqualTo("zone:" + ZONE + ":pharmacies");
    client.invalidate(null);
  }

  @Test
  void stubCatalogueVisibilityClient() {
    StubCatalogueVisibilityClient client =
        new StubCatalogueVisibilityClient(new StubPharmacyCatalogueStatsClient());
    int hidden = client.hideAll(PID);
    assertThat(hidden).isEqualTo(100);
    assertThat(client.isHidden(PID)).isTrue();
    client.restoreAll(PID);
    assertThat(client.isHidden(PID)).isFalse();
  }

  private static ResultSet storefrontRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(PID);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getBoolean("is_online")).thenReturn(true);
    when(rs.getBoolean("admin_forced_offline")).thenReturn(false);
    when(rs.getObject("zone_id")).thenReturn(ZONE);
    when(rs.getString("zone_name")).thenReturn("Koramangala Zone");
    return rs;
  }

  private static ResultSet pauseRs(UUID pauseId) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(pauseId);
    when(rs.getObject("pharmacy_id")).thenReturn(PID);
    when(rs.getString("reason")).thenReturn("audit");
    when(rs.getTimestamp("paused_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("auto_resume_at")).thenReturn(Timestamp.from(NOW.plusSeconds(3600)));
    when(rs.getTimestamp("resumed_at")).thenReturn(null);
    when(rs.getInt("items_hidden_count")).thenReturn(5);
    when(rs.getObject("paused_by")).thenReturn(Ids.newId());
    return rs;
  }

  private static ResultSet zoneFindRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ZONE);
    when(rs.getString("name")).thenReturn("Koramangala Zone");
    when(rs.getBoolean("active")).thenReturn(true);
    return rs;
  }

  private static ResultSet zoneRsNullCreated() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ZONE);
    when(rs.getString("name")).thenReturn("Koramangala Zone");
    when(rs.getString("city")).thenReturn("Bengaluru");
    when(rs.getString("state")).thenReturn("Karnataka");
    when(rs.getBoolean("active")).thenReturn(true);
    when(rs.getBigDecimal("coverage_area_sqkm")).thenReturn(new BigDecimal("8.40"));
    when(rs.getTimestamp("created_at")).thenReturn(null);
    when(rs.getInt("pharmacy_count")).thenReturn(5);
    when(rs.getInt("online_pharmacy_count")).thenReturn(3);
    return rs;
  }

  private static ResultSet zoneRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(ZONE);
    when(rs.getString("name")).thenReturn("Koramangala Zone");
    when(rs.getString("city")).thenReturn("Bengaluru");
    when(rs.getString("state")).thenReturn("Karnataka");
    when(rs.getBoolean("active")).thenReturn(true);
    when(rs.getBigDecimal("coverage_area_sqkm")).thenReturn(new BigDecimal("8.40"));
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getInt("pharmacy_count")).thenReturn(5);
    when(rs.getInt("online_pharmacy_count")).thenReturn(3);
    return rs;
  }
}
