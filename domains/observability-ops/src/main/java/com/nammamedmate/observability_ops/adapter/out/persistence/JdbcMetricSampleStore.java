package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.MetricSampleStore;
import com.nammamedmate.observability_ops.domain.MetricSample;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** ponytail: ceiling = single Postgres; upgrade → TimescaleDB hypertable */
@Component
public class JdbcMetricSampleStore implements MetricSampleStore {

  private static final RowMapper<MetricSample> ROW =
      (rs, i) ->
          new MetricSample(
              (UUID) rs.getObject("id"),
              rs.getString("metric_name"),
              rs.getTimestamp("bucket_at").toInstant(),
              rs.getBigDecimal("value"),
              (UUID) rs.getObject("zone_id"));

  private final JdbcTemplate jdbc;

  public JdbcMetricSampleStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsert(String metricName, Instant bucketTs, BigDecimal value, UUID zoneId) {
    jdbc.update(
        """
        INSERT INTO metric_samples (id, metric_name, bucket_at, value, zone_id)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (metric_name, bucket_at, (COALESCE(zone_id, '00000000-0000-0000-0000-000000000000'::uuid)))
        DO UPDATE SET value = EXCLUDED.value
        """,
        UUID.randomUUID(),
        metricName,
        Timestamp.from(bucketTs),
        value,
        zoneId);
  }

  @Override
  public Optional<Instant> latestBucketTs() {
    List<Timestamp> rows =
        jdbc.query(
            "SELECT bucket_at FROM metric_samples ORDER BY bucket_at DESC LIMIT 1",
            (rs, i) -> rs.getTimestamp("bucket_at"));
    if (rows.isEmpty() || rows.getFirst() == null) {
      return Optional.empty();
    }
    return Optional.of(rows.getFirst().toInstant());
  }

  @Override
  public List<MetricSample> series(String metricName, Instant fromInclusive, Instant toExclusive) {
    return jdbc.query(
        """
        SELECT id, metric_name, bucket_at, value, zone_id
        FROM metric_samples
        WHERE metric_name = ? AND bucket_at >= ? AND bucket_at < ? AND zone_id IS NULL
        ORDER BY bucket_at ASC
        """,
        ROW,
        metricName,
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  @Override
  public int consecutiveZeroBuckets(String metricName, UUID zoneId, Instant asOf, int lookback) {
    List<BigDecimal> values =
        jdbc.query(
            """
            SELECT value FROM metric_samples
            WHERE metric_name = ? AND zone_id = ? AND bucket_at <= ?
            ORDER BY bucket_at DESC
            LIMIT ?
            """,
            (rs, i) -> rs.getBigDecimal("value"),
            metricName,
            zoneId,
            Timestamp.from(asOf),
            lookback);
    int count = 0;
    for (BigDecimal v : values) {
      boolean zero = v != null && v.compareTo(BigDecimal.ZERO) == 0;
      if (!zero) {
        break;
      }
      count++;
    }
    return count;
  }

  @Override
  public List<MetricSample> lastN(String metricName, UUID zoneId, int n) {
    if (zoneId == null) {
      return jdbc.query(
          """
          SELECT id, metric_name, bucket_at, value, zone_id
          FROM metric_samples
          WHERE metric_name = ? AND zone_id IS NULL
          ORDER BY bucket_at DESC
          LIMIT ?
          """,
          ROW,
          metricName,
          n);
    }
    return jdbc.query(
        """
        SELECT id, metric_name, bucket_at, value, zone_id
        FROM metric_samples
        WHERE metric_name = ? AND zone_id = ?
        ORDER BY bucket_at DESC
        LIMIT ?
        """,
        ROW,
        metricName,
        zoneId,
        n);
  }
}
