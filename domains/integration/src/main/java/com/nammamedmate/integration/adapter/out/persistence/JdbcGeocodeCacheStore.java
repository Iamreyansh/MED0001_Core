package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.GeocodeCacheStore;
import com.nammamedmate.integration.domain.GeocodeCacheEntry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class JdbcGeocodeCacheStore implements GeocodeCacheStore {

  private final JdbcTemplate jdbc;

  public JdbcGeocodeCacheStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final RowMapper<GeocodeCacheEntry> MAPPER =
      (rs, i) ->
          new GeocodeCacheEntry(
              rs.getString("cache_key"),
              rs.getBigDecimal("lat").doubleValue(),
              rs.getBigDecimal("lng").doubleValue(),
              rs.getString("formatted_address"),
              rs.getString("place_id"),
              rs.getTimestamp("cached_at").toInstant(),
              rs.getTimestamp("expires_at").toInstant());

  @Override
  public Optional<GeocodeCacheEntry> findValid(String cacheKey, Instant now) {
    List<GeocodeCacheEntry> rows =
        jdbc.query(
            """
            SELECT cache_key, lat, lng, formatted_address, place_id, cached_at, expires_at
            FROM maps_geocode_cache
            WHERE cache_key = ? AND expires_at > ?
            """,
            MAPPER,
            cacheKey,
            Timestamp.from(now));
    return rows.stream().findFirst();
  }

  @Override
  public void upsert(GeocodeCacheEntry entry) {
    jdbc.update(
        """
        INSERT INTO maps_geocode_cache (
          cache_key, lat, lng, formatted_address, place_id, cached_at, expires_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (cache_key) DO UPDATE SET
          lat = EXCLUDED.lat,
          lng = EXCLUDED.lng,
          formatted_address = EXCLUDED.formatted_address,
          place_id = EXCLUDED.place_id,
          cached_at = EXCLUDED.cached_at,
          expires_at = EXCLUDED.expires_at
        """,
        entry.cacheKey(),
        BigDecimal.valueOf(entry.lat()),
        BigDecimal.valueOf(entry.lng()),
        entry.formattedAddress(),
        entry.placeId(),
        Timestamp.from(entry.cachedAt()),
        Timestamp.from(entry.expiresAt()));
  }
}
