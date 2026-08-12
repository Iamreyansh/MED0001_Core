package com.nammamedmate.observability_ops.application.port.out;

import com.nammamedmate.observability_ops.domain.MetricSample;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetricSampleStore {

  void upsert(String metricName, Instant bucketTs, BigDecimal value, UUID zoneId);

  Optional<Instant> latestBucketTs();

  List<MetricSample> series(String metricName, Instant fromInclusive, Instant toExclusive);

  /** Count consecutive trailing zero-value buckets for a zone metric ending at {@code asOf}. */
  int consecutiveZeroBuckets(String metricName, UUID zoneId, Instant asOf, int lookback);

  List<MetricSample> lastN(String metricName, UUID zoneId, int n);
}
