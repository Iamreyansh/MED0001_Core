package com.nammamedmate.marketing.application;

import com.nammamedmate.marketing.application.port.out.CustomerGeoPort;
import com.nammamedmate.marketing.application.port.out.LoyaltyTierReadPort;
import com.nammamedmate.marketing.application.port.out.OrderSegmentMetricsPort;
import com.nammamedmate.marketing.application.port.out.SegmentStore;
import com.nammamedmate.marketing.domain.CustomerMetrics;
import com.nammamedmate.marketing.domain.Segment;
import com.nammamedmate.marketing.domain.SegmentType;
import com.nammamedmate.marketing.domain.SystemSegmentRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SegmentComputeService {

  private final SegmentStore store;
  private final OrderSegmentMetricsPort orderMetrics;
  private final CustomerGeoPort geoPort;
  private final LoyaltyTierReadPort loyaltyPort;
  private final Clock clock;
  private final Set<String> highValuePincodes;

  public SegmentComputeService(
      SegmentStore store,
      OrderSegmentMetricsPort orderMetrics,
      CustomerGeoPort geoPort,
      LoyaltyTierReadPort loyaltyPort,
      Clock clock,
      @Value("${medmate.marketing.high-value-pincodes:}") String highValuePincodesCsv) {
    this.store = store;
    this.orderMetrics = orderMetrics;
    this.geoPort = geoPort;
    this.loyaltyPort = loyaltyPort;
    this.clock = clock;
    this.highValuePincodes = parsePincodes(highValuePincodesCsv);
  }

  @Transactional
  public void computeSegment(UUID segmentId) {
    Segment segment =
        store
            .findById(segmentId)
            .orElseThrow(() -> new IllegalStateException("segment missing: " + segmentId));
    Instant now = clock.instant();
    List<CustomerMetrics> enriched = loadEnrichedMetrics();
    List<CustomerMetrics> members = filterMembers(segment, enriched, now);

    long totalLtv = 0L;
    long aovSum = 0L;
    List<UUID> ids = new ArrayList<>(members.size());
    for (CustomerMetrics m : members) {
      ids.add(m.customerId());
      totalLtv += m.ltvPaise();
      aovSum += m.avgAovPaise();
    }
    int count = members.size();
    Long avgAov = count == 0 ? null : aovSum / count;
    Long totalLtvPaise = count == 0 ? 0L : totalLtv;

    store.replaceMemberships(segmentId, ids, now);
    store.updateComputeResult(segmentId, count, avgAov, totalLtvPaise, now, "READY");
    LocalDate snapshotDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
    // Weekly rollup key: Monday of the UTC week
    LocalDate weekStart = snapshotDate.minusDays((snapshotDate.getDayOfWeek().getValue() + 6) % 7);
    store.upsertSnapshot(segmentId, weekStart, count);
  }

  @Transactional
  public void computeAllSystemSegments() {
    List<Segment> system = store.list(SegmentType.SYSTEM, 0, 100);
    for (Segment s : system) {
      computeSegment(s.id());
    }
  }

  List<CustomerMetrics> loadEnrichedMetrics() {
    List<CustomerMetrics> base = orderMetrics.listAllActiveCustomers();
    if (base.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = base.stream().map(CustomerMetrics::customerId).toList();
    Map<UUID, CustomerGeoPort.Geo> geos = geoPort.findByCustomerIds(ids);
    Map<UUID, String> tiers = loyaltyPort.tiersFor(ids);
    List<CustomerMetrics> out = new ArrayList<>(base.size());
    for (CustomerMetrics m : base) {
      CustomerGeoPort.Geo geo = geos.get(m.customerId());
      String city = geo != null && geo.city() != null ? geo.city() : m.city();
      String pin = geo != null && geo.pincode() != null ? geo.pincode() : m.pincode();
      String tier = tiers.getOrDefault(m.customerId(), m.loyaltyTierOrNone());
      out.add(
          new CustomerMetrics(
              m.customerId(),
              m.name(),
              m.phone(),
              m.totalOrders(),
              m.ltvPaise(),
              m.lastOrderAt(),
              m.avgAovPaise(),
              m.hasRxOrders(),
              m.accountAgeDays(),
              m.ordersLast30Days(),
              city,
              pin,
              tier));
    }
    return out;
  }

  private List<CustomerMetrics> filterMembers(
      Segment segment, List<CustomerMetrics> all, Instant now) {
    if (segment.segmentType() == SegmentType.SYSTEM) {
      return all.stream()
          .filter(m -> SystemSegmentRules.matches(segment.name(), m, now, highValuePincodes))
          .toList();
    }
    return all.stream().filter(m -> m.matchesAll(segment.criteria(), now)).toList();
  }

  static Set<String> parsePincodes(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    for (String part : csv.split(",")) {
      String p = part.trim();
      if (!p.isEmpty()) {
        out.add(p);
      }
    }
    return Set.copyOf(out);
  }
}
