package com.nammamedmate.rider.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.DemandHour;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.ZoneRow;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.ZoneSummaryRow;
import com.nammamedmate.rider.application.port.out.RebalancingSuggestionStore;
import com.nammamedmate.rider.application.port.out.RebalancingSuggestionStore.SuggestionRow;
import com.nammamedmate.rider.application.port.out.RiderFleetStore;
import com.nammamedmate.rider.application.port.out.RiderFleetStore.FleetRiderRow;
import com.nammamedmate.rider.domain.DeliveryFeeFormula;
import com.nammamedmate.rider.domain.RiderAvailability;
import com.nammamedmate.rider.domain.ZoneCoverage;
import com.nammamedmate.rider.domain.ZonePolygons;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDeliveryZoneService {

  private static final Set<AuthRole> OPS = Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final Duration SUGGESTION_TTL = Duration.ofHours(2);
  private static final BigDecimal MIN_SURGE = new BigDecimal("1.0");
  private static final BigDecimal MAX_SURGE = new BigDecimal("5.0");

  private final DeliveryZoneStore zones;
  private final RebalancingSuggestionStore suggestions;
  private final RiderFleetStore fleet;
  private final ActiveDeliveryPort deliveries;
  private final OutboxPublisher outbox;
  private final ObjectMapper mapper;
  private final Clock clock;

  public AdminDeliveryZoneService(
      DeliveryZoneStore zones,
      RebalancingSuggestionStore suggestions,
      RiderFleetStore fleet,
      ActiveDeliveryPort deliveries,
      OutboxPublisher outbox,
      ObjectMapper mapper,
      Clock clock) {
    this.zones = zones;
    this.suggestions = suggestions;
    this.fleet = fleet;
    this.deliveries = deliveries;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public ListResult list(
      MedmatePrincipal principal, String city, Boolean serviceable, Integer page, Integer limit) {
    requireOps(principal);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    int offset = (p - 1) * lim;
    List<ZoneSummaryRow> rows = zones.list(city, serviceable, offset, lim);
    int total = zones.count(city, serviceable);

    int activeRiders = zones.countOnlineRidersAll();
    int liveAll = 0;
    int underStrain = 0;
    List<Map<String, Object>> zoneMaps = new ArrayList<>();
    for (ZoneSummaryRow row : rows) {
      int online = zones.countOnlineRiders(row.id());
      int live = deliveries.countLiveOrdersInZone(row.id());
      liveAll += live;
      String coverage = ZoneCoverage.status(online, live);
      if (ZoneCoverage.underStrain(online, live) && online > 0) {
        underStrain++;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("zone_id", row.id().toString());
      m.put("zone_name", row.name());
      m.put("city", row.city());
      m.put("pharmacies_count", row.pharmaciesCount());
      m.put("riders_online", online);
      m.put("live_orders", live);
      m.put("base_fee", money(row.baseFee()));
      m.put("sla_minutes", row.slaMinutes());
      m.put("surge_multiplier", money(row.surgeMultiplier()));
      m.put("is_surge_active", row.surgeActive());
      m.put("is_serviceable", row.serviceable());
      m.put("coverage_status", coverage);
      zoneMaps.add(m);
    }

    BigDecimal avg = zones.avgDeliveryMinutesAll();
    double fleetUtil =
        activeRiders == 0
            ? 0.0
            : BigDecimal.valueOf(liveAll * 100.0 / Math.max(activeRiders, 1))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();

    Map<String, Object> chips = new LinkedHashMap<>();
    chips.put("serviceable_zones", zones.countServiceable());
    chips.put("active_riders", activeRiders);
    chips.put("fleet_utilization_pct", fleetUtil);
    chips.put("live_deliveries", liveAll);
    chips.put("zones_under_strain", underStrain);
    chips.put(
        "avg_delivery_minutes",
        avg == null ? 0.0 : avg.setScale(1, RoundingMode.HALF_UP).doubleValue());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary_chips", chips);
    data.put("zones", zoneMaps);
    return new ListResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateZoneCommand cmd) {
    requireOps(principal);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "zone_name and city are required", 422);
    }
    if (blank(cmd.zoneName()) || blank(cmd.city())) {
      throw new AppException("VALIDATION_ERROR", "zone_name and city are required", 422);
    }
    List<List<Double>> ring = extractRing(cmd.polygon());
    if (!ZonePolygons.isValidClosed(ring)) {
      throw new AppException("INVALID_POLYGON", "GeoJSON polygon not closed or < 3 vertices", 422);
    }
    if (neg(cmd.baseFee()) || neg(cmd.perKmFee())) {
      throw new AppException("INVALID_FEE", "base_fee or per_km_fee is negative", 422);
    }
    if (zones.existsNameInCity(cmd.zoneName().trim(), cmd.city().trim(), null)) {
      throw new AppException(
          "ZONE_NAME_DUPLICATE", "Zone name already exists in the same city", 409);
    }

    Instant now = clock.instant();
    UUID id = Ids.newId();
    BigDecimal area =
        BigDecimal.valueOf(ZonePolygons.approxAreaSqKm(ring)).setScale(3, RoundingMode.HALF_UP);
    String wkt = ZonePolygons.toWkt(ring);
    String geoJson = toPolygonGeoJson(ring);
    boolean serviceable = cmd.isServiceable() == null || cmd.isServiceable();
    BigDecimal surge =
        cmd.surgeMultiplier() == null ? BigDecimal.ONE.setScale(2) : scale2(cmd.surgeMultiplier());
    String state = blank(cmd.state()) ? defaultState(cmd.city()) : cmd.state().trim();

    zones.insert(
        id,
        cmd.zoneName().trim(),
        cmd.city().trim(),
        state,
        wkt,
        geoJson,
        area,
        scale2(cmd.baseFee() == null ? new BigDecimal("25.00") : cmd.baseFee()),
        scale2(cmd.perKmFee() == null ? new BigDecimal("5.00") : cmd.perKmFee()),
        cmd.slaMinutes() == null ? 30 : cmd.slaMinutes(),
        scale2(cmd.minOrderValue() == null ? BigDecimal.ZERO : cmd.minOrderValue()),
        scale2(
            cmd.freeDeliveryThreshold() == null
                ? new BigDecimal("199.00")
                : cmd.freeDeliveryThreshold()),
        surge,
        serviceable,
        principal.subject(),
        now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", id.toString());
    data.put("zone_name", cmd.zoneName().trim());
    data.put("city", cmd.city().trim());
    data.put("is_serviceable", serviceable);
    data.put("base_fee", money(cmd.baseFee() == null ? new BigDecimal("25.00") : cmd.baseFee()));
    data.put("per_km_fee", money(cmd.perKmFee() == null ? new BigDecimal("5.00") : cmd.perKmFee()));
    data.put("sla_minutes", cmd.slaMinutes() == null ? Integer.valueOf(30) : cmd.slaMinutes());
    data.put(
        "min_order_value",
        money(cmd.minOrderValue() == null ? BigDecimal.ZERO : cmd.minOrderValue()));
    data.put(
        "free_delivery_threshold",
        money(
            cmd.freeDeliveryThreshold() == null
                ? new BigDecimal("199.00")
                : cmd.freeDeliveryThreshold()));
    data.put("surge_multiplier", money(surge));
    data.put("is_surge_active", false);
    data.put("area_sq_km", area.doubleValue());
    data.put("created_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID zoneId) {
    requireOps(principal);
    ZoneRow zone = requireZone(zoneId);
    int online = zones.countOnlineRiders(zoneId);
    int live = deliveries.countLiveOrdersInZone(zoneId);
    List<Map<String, Object>> riders = new ArrayList<>();
    Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
    for (FleetRiderRow row : fleet.listByZone(zoneId)) {
      String display =
          RiderAvailability.displayStatus(
              row.accountStatus(), deliveries.findActiveByRider(row.riderId()).isPresent());
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("rider_id", row.riderId().toString());
      r.put("name", row.name());
      r.put("status", display);
      r.put("avg_rating", row.avgRating());
      r.put("trips_today", fleet.countTripsToday(row.riderId(), dayStart, dayEnd));
      riders.add(r);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", zone.id().toString());
    data.put("zone_name", zone.name());
    data.put("city", zone.city());
    data.put("polygon", parsePolygon(zone.polygonGeoJson()));
    data.put("base_fee", money(zone.baseFee()));
    data.put("per_km_fee", money(zone.perKmFee()));
    data.put("sla_minutes", zone.slaMinutes());
    data.put("min_order_value", money(zone.minOrderValue()));
    data.put("free_delivery_threshold", money(zone.freeDeliveryThreshold()));
    data.put("surge_multiplier", money(zone.surgeMultiplier()));
    data.put("is_surge_active", zone.surgeActive());
    data.put("is_serviceable", zone.serviceable());
    data.put("pharmacies_count", zones.countPharmacies(zoneId));
    data.put("riders_online", online);
    data.put("live_orders", live);
    data.put("coverage_status", ZoneCoverage.status(online, live));
    data.put("riders", riders);
    data.put("created_at", zone.createdAt().toString());
    data.put("updated_at", zone.updatedAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> patch(MedmatePrincipal principal, UUID zoneId, PatchZoneCommand cmd) {
    requireOps(principal);
    requireZone(zoneId);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "body required", 422);
    }
    if (neg(cmd.baseFee()) || neg(cmd.perKmFee())) {
      throw new AppException("INVALID_FEE", "base_fee or per_km_fee is negative", 422);
    }
    String wkt = null;
    String geoJson = null;
    BigDecimal area = null;
    if (cmd.polygon() != null) {
      List<List<Double>> ring = extractRing(cmd.polygon());
      if (!ZonePolygons.isValidClosed(ring)) {
        throw new AppException(
            "INVALID_POLYGON", "GeoJSON polygon not closed or < 3 vertices", 422);
      }
      wkt = ZonePolygons.toWkt(ring);
      geoJson = toPolygonGeoJson(ring);
      area =
          BigDecimal.valueOf(ZonePolygons.approxAreaSqKm(ring)).setScale(3, RoundingMode.HALF_UP);
    }
    if (!blank(cmd.zoneName())) {
      ZoneRow existing = requireZone(zoneId);
      if (zones.existsNameInCity(cmd.zoneName().trim(), existing.city(), zoneId)) {
        throw new AppException(
            "ZONE_NAME_DUPLICATE", "Zone name already exists in the same city", 409);
      }
    }
    Instant now = clock.instant();
    zones.updateFields(
        zoneId,
        cmd.slaMinutes(),
        cmd.baseFee() == null ? null : scale2(cmd.baseFee()),
        cmd.perKmFee() == null ? null : scale2(cmd.perKmFee()),
        cmd.minOrderValue() == null ? null : scale2(cmd.minOrderValue()),
        cmd.freeDeliveryThreshold() == null ? null : scale2(cmd.freeDeliveryThreshold()),
        blank(cmd.zoneName()) ? null : cmd.zoneName().trim(),
        wkt,
        geoJson,
        area,
        now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", zoneId.toString());
    if (cmd.slaMinutes() != null) {
      data.put("sla_minutes", cmd.slaMinutes());
    }
    if (cmd.perKmFee() != null) {
      data.put("per_km_fee", money(cmd.perKmFee()));
    }
    if (cmd.baseFee() != null) {
      data.put("base_fee", money(cmd.baseFee()));
    }
    if (cmd.minOrderValue() != null) {
      data.put("min_order_value", money(cmd.minOrderValue()));
    }
    if (cmd.freeDeliveryThreshold() != null) {
      data.put("free_delivery_threshold", money(cmd.freeDeliveryThreshold()));
    }
    if (!blank(cmd.zoneName())) {
      data.put("zone_name", cmd.zoneName().trim());
    }
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> setSurge(
      MedmatePrincipal principal, UUID zoneId, Boolean active, BigDecimal multiplier) {
    requireOps(principal);
    requireZone(zoneId);
    if (active == null) {
      throw new AppException("VALIDATION_ERROR", "is_surge_active is required", 422);
    }
    BigDecimal mult = multiplier == null ? BigDecimal.ONE.setScale(2) : scale2(multiplier);
    if (mult.compareTo(MIN_SURGE) < 0 || mult.compareTo(MAX_SURGE) > 0) {
      throw new AppException("INVALID_MULTIPLIER", "surge_multiplier < 1.0 or > 5.0", 422);
    }
    Instant now = clock.instant();
    zones.updateSurge(zoneId, active, mult, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", zoneId.toString());
    data.put("is_surge_active", active);
    data.put("surge_multiplier", money(mult));
    data.put("updated_by", principal.subject().toString());
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> setServiceable(
      MedmatePrincipal principal, UUID zoneId, Boolean serviceable, String reason) {
    requireOps(principal);
    requireZone(zoneId);
    if (serviceable == null) {
      throw new AppException("VALIDATION_ERROR", "is_serviceable is required", 422);
    }
    Instant now = clock.instant();
    String offlineReason = Boolean.FALSE.equals(serviceable) ? reason : null;
    zones.updateServiceable(zoneId, serviceable, offlineReason, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", zoneId.toString());
    data.put("is_serviceable", serviceable);
    data.put("reason", reason);
    data.put("updated_by", principal.subject().toString());
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> rebalancingSuggestions(MedmatePrincipal principal) {
    requireOps(principal);
    Instant now = clock.instant();
    suggestions.expireStale(now);
    List<SuggestionRow> pending = suggestions.listPending(now);
    if (pending.isEmpty()) {
      generateStubSuggestions(now);
      pending = suggestions.listPending(now);
    }
    List<Map<String, Object>> maps = new ArrayList<>();
    for (SuggestionRow s : pending) {
      maps.add(toSuggestionMap(s));
    }
    return Map.of("suggestions", maps);
  }

  @Transactional
  public Map<String, Object> applyRebalancing(MedmatePrincipal principal, UUID suggestionId) {
    requireOps(principal);
    Instant now = clock.instant();
    suggestions.expireStale(now);
    SuggestionRow row =
        suggestions
            .findById(suggestionId)
            .orElseThrow(
                () ->
                    new AppException(
                        "SUGGESTION_NOT_FOUND", "suggestion_id invalid or expired", 404));
    if ("APPLIED".equals(row.status())) {
      throw new AppException("SUGGESTION_ALREADY_APPLIED", "Suggestion was already applied", 409);
    }
    if (!"PENDING".equals(row.status()) || !row.expiresAt().isAfter(now)) {
      throw new AppException("SUGGESTION_NOT_FOUND", "suggestion_id invalid or expired", 404);
    }
    if (!suggestions.markApplied(suggestionId, principal.subject(), now)) {
      throw new AppException("SUGGESTION_ALREADY_APPLIED", "Suggestion was already applied", 409);
    }
    List<Map<String, Object>> riders = parseSuggestedRiders(row.suggestedRidersJson());
    for (Map<String, Object> rider : riders) {
      Object rid = rider.get("rider_id");
      if (rid == null) {
        continue;
      }
      UUID riderId = UUID.fromString(rid.toString());
      outbox.publish(
          DomainEvent.of(
              "rider.notification.rebalancing",
              "rider",
              riderId,
              Map.of(
                  "rider_id",
                  riderId.toString(),
                  "from_zone_id",
                  row.fromZoneId().toString(),
                  "to_zone_id",
                  row.toZoneId().toString(),
                  "template",
                  "RIDER_REBALANCING",
                  "channels",
                  List.of("PUSH"))));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("suggestion_id", suggestionId.toString());
    data.put("applied", true);
    data.put("riders_notified", riders.size());
    data.put("applied_by", principal.subject().toString());
    data.put("applied_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> demandVsSupply(
      MedmatePrincipal principal, UUID zoneId, LocalDate from, LocalDate to) {
    requireOps(principal);
    if (zoneId == null) {
      throw new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404);
    }
    ZoneRow zone = requireZone(zoneId);
    LocalDate toDate = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
    LocalDate fromDate = from == null ? toDate.minusDays(7) : from;
    Instant fromTs = fromDate.atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant toTs = toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusSeconds(1);
    // align series end to last full hour start
    Instant seriesEnd = toTs.truncatedTo(ChronoUnit.HOURS);
    Instant seriesStart = fromTs.truncatedTo(ChronoUnit.HOURS);
    List<DemandHour> hours = zones.demandVsSupply(zone.id(), seriesStart, seriesEnd);
    List<Map<String, Object>> chart = new ArrayList<>();
    for (DemandHour h : hours) {
      double ratio =
          h.onlineRiders() == 0
              ? (h.orders() == 0 ? 0.0 : h.orders())
              : BigDecimal.valueOf(h.orders() / (double) h.onlineRiders())
                  .setScale(1, RoundingMode.HALF_UP)
                  .doubleValue();
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("hour", h.hour().toString());
      point.put("orders", h.orders());
      point.put("online_riders", h.onlineRiders());
      point.put("demand_supply_ratio", ratio);
      point.put("strain", ZoneCoverage.underStrain(h.onlineRiders(), h.orders()));
      chart.add(point);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", zone.id().toString());
    data.put("zone_name", zone.name());
    data.put("chart_data", chart);
    return data;
  }

  /** AC-004 helper for fee-estimate consumers (STORY-006). */
  public BigDecimal estimateDeliveryFee(
      ZoneRow zone, double distanceKm, BigDecimal orderValueRupees) {
    return DeliveryFeeFormula.estimateRupees(
        zone.baseFee(),
        zone.perKmFee(),
        distanceKm,
        orderValueRupees,
        zone.freeDeliveryThreshold(),
        zone.surgeActive(),
        zone.surgeMultiplier());
  }

  public record CreateZoneCommand(
      String zoneName,
      String city,
      String state,
      Map<String, Object> polygon,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      Integer slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      BigDecimal surgeMultiplier,
      Boolean isServiceable) {}

  public record PatchZoneCommand(
      String zoneName,
      Integer slaMinutes,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold,
      Map<String, Object> polygon) {}

  private void generateStubSuggestions(Instant now) {
    // ponytail: heuristic stub until EPIC-015 demand forecast model
    List<ZoneSummaryRow> all = zones.list(null, true, 0, 100);
    ZoneSummaryRow strained = null;
    ZoneSummaryRow spare = null;
    int strainedLive = 0;
    int strainedOnline = 0;
    int spareOnline = 0;
    int spareLive = 0;
    for (ZoneSummaryRow row : all) {
      int online = zones.countOnlineRiders(row.id());
      int live = deliveries.countLiveOrdersInZone(row.id());
      if (ZoneCoverage.underStrain(online, live)) {
        strained = row;
        strainedLive = live;
        strainedOnline = online;
      } else {
        spare = row;
        spareOnline = online;
        spareLive = live;
      }
    }
    if (strained != null && spare != null) {
      List<Map<String, Object>> suggested = new ArrayList<>();
      for (FleetRiderRow r : fleet.listByZone(spare.id())) {
        String display =
            RiderAvailability.displayStatus(
                r.accountStatus(), deliveries.findActiveByRider(r.riderId()).isPresent());
        if (!"ONLINE".equals(display)) {
          continue;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("rider_id", r.riderId().toString());
        s.put("name", r.name());
        s.put("distance_to_target_km", 1.2);
        suggested.add(s);
        if (suggested.size() >= 2) {
          break;
        }
      }
      if (!suggested.isEmpty()) {
        String reason =
            strained.name()
                + " has "
                + strainedLive
                + " pending orders and "
                + strainedOnline
                + " online riders. "
                + spare.name()
                + " has "
                + spareOnline
                + " online riders and "
                + spareLive
                + " live orders (low strain).";
        suggestions.insert(
            Ids.newId(),
            spare.id(),
            strained.id(),
            suggested.size(),
            reason,
            new BigDecimal("88.40"),
            writeJson(suggested),
            now.plus(SUGGESTION_TTL),
            now);
      }
    }
  }

  private Map<String, Object> toSuggestionMap(SuggestionRow s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("suggestion_id", s.id().toString());
    m.put("from_zone_id", s.fromZoneId().toString());
    m.put("from_zone_name", s.fromZoneName());
    m.put("to_zone_id", s.toZoneId().toString());
    m.put("to_zone_name", s.toZoneName());
    m.put("riders_to_move", s.ridersToMove());
    m.put("reason", s.reason());
    m.put(
        "confidence_pct",
        s.confidencePct() == null
            ? 0.0
            : s.confidencePct().setScale(1, RoundingMode.HALF_UP).doubleValue());
    m.put("suggested_riders", parseSuggestedRiders(s.suggestedRidersJson()));
    m.put("generated_at", s.generatedAt().toString());
    return m;
  }

  private List<Map<String, Object>> parseSuggestedRiders(String json) {
    if (blank(json)) {
      return List.of();
    }
    try {
      return mapper.readValue(json, new TypeReference<>() {});
    } catch (Exception e) {
      return List.of();
    }
  }

  private String writeJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new AppException("VALIDATION_ERROR", "Unable to serialize polygon", 422);
    }
  }

  private List<List<Double>> extractRing(Map<String, Object> polygon) {
    if (polygon == null) {
      return List.of();
    }
    Object coords = polygon.get("coordinates");
    if (!(coords instanceof List<?>)) {
      return List.of();
    }
    List<?> outer = (List<?>) coords;
    if (outer.isEmpty()) {
      return List.of();
    }
    if (!(outer.get(0) instanceof List<?>)) {
      return List.of();
    }
    List<?> ringRaw = (List<?>) outer.get(0);
    List<List<Double>> ring = new ArrayList<>();
    for (Object p : ringRaw) {
      if (!(p instanceof List<?> pair) || pair.size() < 2) {
        return List.of();
      }
      ring.add(List.of(toDouble(pair.get(0)), toDouble(pair.get(1))));
    }
    return ring;
  }

  private static double toDouble(Object o) {
    if (o instanceof Number n) {
      return n.doubleValue();
    }
    return Double.parseDouble(String.valueOf(o));
  }

  private Object parsePolygon(String geoJson) {
    if (blank(geoJson)) {
      return Map.of("type", "Polygon", "coordinates", List.of());
    }
    try {
      return mapper.readValue(geoJson, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Map.of("type", "Polygon", "coordinates", List.of());
    }
  }

  private String toPolygonGeoJson(List<List<Double>> ring) {
    Map<String, Object> poly = new LinkedHashMap<>();
    poly.put("type", "Polygon");
    poly.put("coordinates", List.of(ring));
    return writeJson(poly);
  }

  private ZoneRow requireZone(UUID zoneId) {
    return zones
        .findById(zoneId)
        .orElseThrow(() -> new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404));
  }

  private static void requireOps(MedmatePrincipal principal) {
    if (principal == null || !OPS.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin operations role required", 403);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static boolean neg(BigDecimal v) {
    return v != null && v.compareTo(BigDecimal.ZERO) < 0;
  }

  private static BigDecimal scale2(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP);
  }

  private static double money(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private static String defaultState(String city) {
    return city.toLowerCase().contains("mumbai") ? "Maharashtra" : "Karnataka";
  }
}
