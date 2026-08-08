package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.rider.application.port.out.DeliveryFeeSnapshotStore;
import com.nammamedmate.rider.application.port.out.DeliveryFeeSnapshotStore.Snapshot;
import com.nammamedmate.rider.application.port.out.DeliveryPricingLookupPort;
import com.nammamedmate.rider.application.port.out.DeliveryPricingLookupPort.AddressGeo;
import com.nammamedmate.rider.application.port.out.DeliveryPricingLookupPort.PharmacyGeo;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore;
import com.nammamedmate.rider.application.port.out.DeliveryZoneStore.ZoneRow;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort.RouteEstimate;
import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import com.nammamedmate.rider.domain.DeliveryFeeFormula;
import com.nammamedmate.rider.domain.DeliveryFeeFormula.Breakdown;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryPricingService {

  private static final Set<AuthRole> OPS = Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);
  private static final int FEE_ESTIMATE_LIMIT = 30;
  private static final int FEE_ESTIMATE_WINDOW_SEC = 60;

  private final DeliveryZoneStore zones;
  private final PlatformPricingConfigStore pricingConfig;
  private final DeliveryFeeSnapshotStore snapshots;
  private final DeliveryPricingLookupPort lookup;
  private final DistanceMatrixPort distanceMatrix;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public DeliveryPricingService(
      DeliveryZoneStore zones,
      PlatformPricingConfigStore pricingConfig,
      DeliveryFeeSnapshotStore snapshots,
      DeliveryPricingLookupPort lookup,
      DistanceMatrixPort distanceMatrix,
      RateLimiter rateLimiter,
      Clock clock) {
    this.zones = zones;
    this.pricingConfig = pricingConfig;
    this.snapshots = snapshots;
    this.lookup = lookup;
    this.distanceMatrix = distanceMatrix;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  /** Locked quote used by order placement bridge (zone + distance known). */
  public record LockedQuote(
      UUID zoneId,
      BigDecimal distanceKm,
      BigDecimal baseFee,
      BigDecimal distanceCharge,
      BigDecimal surgeMultiplier,
      BigDecimal deliveryFee,
      BigDecimal handlingFee,
      boolean freeDelivery,
      BigDecimal riderPayout,
      long deliveryFeePaise,
      long handlingFeePaise) {}

  @Transactional(readOnly = true)
  public Map<String, Object> listPricing(MedmatePrincipal principal) {
    requireOps(principal);
    BigDecimal handling = pricingConfig.handlingFeeRupees();
    List<Map<String, Object>> zoneMaps = new ArrayList<>();
    for (ZoneRow zone : zones.listPricing()) {
      BigDecimal effective =
          DeliveryFeeFormula.effectiveSurge(zone.surgeActive(), zone.surgeMultiplier());
      BigDecimal sample2 =
          DeliveryFeeFormula.estimateRupees(
              zone.baseFee(),
              zone.perKmFee(),
              2.0,
              BigDecimal.ZERO,
              zone.freeDeliveryThreshold(),
              zone.surgeActive(),
              zone.surgeMultiplier());
      BigDecimal sample5 =
          DeliveryFeeFormula.estimateRupees(
              zone.baseFee(),
              zone.perKmFee(),
              5.0,
              BigDecimal.ZERO,
              zone.freeDeliveryThreshold(),
              zone.surgeActive(),
              zone.surgeMultiplier());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("zone_id", zone.id().toString());
      m.put("zone_name", zone.name());
      m.put("city", zone.city());
      m.put("base_fee", money(zone.baseFee()));
      m.put("per_km_fee", money(zone.perKmFee()));
      m.put("sla_minutes", zone.slaMinutes());
      m.put("min_order_value", money(zone.minOrderValue()));
      m.put("free_delivery_threshold", money(zone.freeDeliveryThreshold()));
      m.put("surge_multiplier", money(zone.surgeMultiplier()));
      m.put("is_surge_active", zone.surgeActive());
      m.put("effective_surge", money(effective));
      m.put("sample_fee_2km", money(sample2));
      m.put("sample_fee_5km", money(sample5));
      zoneMaps.add(m);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("handling_fee", money(handling));
    data.put("zones", zoneMaps);
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> simulate(
      MedmatePrincipal principal, UUID zoneId, BigDecimal distanceKm, BigDecimal orderValue) {
    requireOps(principal);
    if (zoneId == null) {
      throw new AppException("ZONE_NOT_FOUND", "zone_id does not exist", 404);
    }
    if (distanceKm == null || distanceKm.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("INVALID_DISTANCE", "distance_km must be > 0", 422);
    }
    if (orderValue == null || orderValue.compareTo(BigDecimal.ZERO) < 0) {
      throw new AppException("INVALID_ORDER_VALUE", "order_value must be >= 0", 422);
    }
    ZoneRow zone = requireZone(zoneId);
    Breakdown b =
        DeliveryFeeFormula.breakdown(
            zone.baseFee(),
            zone.perKmFee(),
            distanceKm.doubleValue(),
            orderValue,
            zone.freeDeliveryThreshold(),
            zone.surgeActive(),
            zone.surgeMultiplier(),
            pricingConfig.handlingFeeRupees());
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("distance_km", distanceKm.setScale(2, RoundingMode.HALF_UP).doubleValue());
    input.put("order_value", money(orderValue));
    Map<String, Object> breakdown = new LinkedHashMap<>();
    breakdown.put("base_fee", money(b.baseFee()));
    breakdown.put("distance_charge", money(b.distanceCharge()));
    breakdown.put("subtotal_before_surge", money(b.subtotalBeforeSurge()));
    breakdown.put("surge_multiplier", money(b.surgeMultiplier()));
    breakdown.put("surge_charge", money(b.surgeCharge()));
    breakdown.put("delivery_fee", money(b.deliveryFee()));
    breakdown.put("handling_fee", money(b.handlingFee()));
    breakdown.put("free_delivery_waiver", b.freeDeliveryWaiver());
    breakdown.put("total_customer_pays", money(b.totalCustomerPays()));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", zone.id().toString());
    data.put("zone_name", zone.name());
    data.put("input", input);
    data.put("breakdown", breakdown);
    data.put("rider_delivery_payout", money(b.riderPayout()));
    data.put(
        "rider_payout_note", DeliveryFeeFormula.riderPayoutNote(b.deliveryFee(), b.riderPayout()));
    return data;
  }

  @Transactional
  public Map<String, Object> patchPricing(
      MedmatePrincipal principal,
      UUID zoneId,
      BigDecimal baseFee,
      BigDecimal perKmFee,
      Integer slaMinutes,
      BigDecimal minOrderValue,
      BigDecimal freeDeliveryThreshold) {
    requireOps(principal);
    ZoneRow zone = requireZone(zoneId);
    if (neg(baseFee) || neg(perKmFee)) {
      throw new AppException("INVALID_FEE", "base_fee or per_km_fee is negative", 422);
    }
    BigDecimal nextMin = minOrderValue != null ? scale2(minOrderValue) : zone.minOrderValue();
    BigDecimal nextFree =
        freeDeliveryThreshold != null
            ? scale2(freeDeliveryThreshold)
            : zone.freeDeliveryThreshold();
    if (nextFree != null && nextMin != null && nextFree.compareTo(nextMin) < 0) {
      throw new AppException("INVALID_THRESHOLD", "free_delivery_threshold < min_order_value", 422);
    }
    Instant now = clock.instant();
    zones.updateFields(
        zoneId,
        slaMinutes,
        baseFee == null ? null : scale2(baseFee),
        perKmFee == null ? null : scale2(perKmFee),
        minOrderValue == null ? null : scale2(minOrderValue),
        freeDeliveryThreshold == null ? null : scale2(freeDeliveryThreshold),
        null,
        null,
        null,
        null,
        now);
    ZoneRow updated = requireZone(zoneId);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("zone_id", updated.id().toString());
    data.put("zone_name", updated.name());
    data.put("base_fee", money(updated.baseFee()));
    data.put("per_km_fee", money(updated.perKmFee()));
    data.put("sla_minutes", updated.slaMinutes());
    data.put("min_order_value", money(updated.minOrderValue()));
    data.put("free_delivery_threshold", money(updated.freeDeliveryThreshold()));
    data.put("updated_by", principal.subject().toString());
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> feeEstimate(
      String clientIp,
      UUID pharmacyId,
      UUID deliveryAddressId,
      Double lat,
      Double lng,
      BigDecimal orderValue) {
    rateLimitFeeEstimate(clientIp);
    if (pharmacyId == null) {
      throw new AppException("PHARMACY_NOT_FOUND", "pharmacy_id does not exist", 404);
    }
    PharmacyGeo pharmacy =
        lookup
            .findPharmacy(pharmacyId)
            .orElseThrow(
                () -> new AppException("PHARMACY_NOT_FOUND", "pharmacy_id does not exist", 404));
    double destLat;
    double destLng;
    if (deliveryAddressId != null) {
      AddressGeo addr =
          lookup
              .findAddress(deliveryAddressId)
              .orElseThrow(
                  () ->
                      new AppException(
                          "ADDRESS_NOT_FOUND", "delivery_address_id does not exist", 404));
      destLat = addr.lat();
      destLng = addr.lng();
    } else if (lat != null && lng != null) {
      destLat = lat;
      destLng = lng;
    } else {
      throw new AppException("VALIDATION_ERROR", "delivery_address_id or lat/lng is required", 422);
    }
    ZoneRow zone =
        zones
            .findContaining(destLat, destLng)
            .orElseThrow(
                () ->
                    new AppException(
                        "ADDRESS_NOT_SERVICEABLE",
                        "Delivery address is outside all active zone polygons",
                        422));
    if (!zone.serviceable()) {
      throw new AppException("ZONE_OFFLINE", "Zone found but is_serviceable = false", 422);
    }
    RouteEstimate route =
        distanceMatrix.estimateDriving(pharmacy.lat(), pharmacy.lng(), destLat, destLng);
    BigDecimal value = orderValue == null ? BigDecimal.ZERO : orderValue;
    Breakdown b =
        DeliveryFeeFormula.breakdown(
            zone.baseFee(),
            zone.perKmFee(),
            route.distanceKm(),
            value,
            zone.freeDeliveryThreshold(),
            zone.surgeActive(),
            zone.surgeMultiplier(),
            pricingConfig.handlingFeeRupees());
    Instant now = clock.instant();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacy.id().toString());
    data.put("pharmacy_name", pharmacy.name());
    data.put("zone_id", zone.id().toString());
    data.put("zone_name", zone.name());
    data.put(
        "distance_km",
        BigDecimal.valueOf(route.distanceKm()).setScale(1, RoundingMode.HALF_UP).doubleValue());
    data.put("delivery_fee", money(b.deliveryFee()));
    data.put("handling_fee", money(b.handlingFee()));
    data.put("is_free_delivery", b.freeDeliveryWaiver());
    data.put("free_delivery_from", money(zone.freeDeliveryThreshold()));
    data.put("is_surge_active", zone.surgeActive());
    data.put("surge_multiplier", money(b.surgeMultiplier()));
    data.put("eta_minutes", route.durationMinutes());
    data.put("sla_minutes", zone.slaMinutes());
    data.put("is_serviceable", true);
    data.put("estimated_at", now.toString());
    return data;
  }

  /**
   * Quote for cart/placement. Empty when pharmacy/coords missing (caller falls back to flat fee).
   */
  @Transactional(readOnly = true)
  public Optional<LockedQuote> quoteForDelivery(
      UUID pharmacyId,
      double deliveryLat,
      double deliveryLng,
      long itemTotalPaise,
      boolean freeDeliveryCoupon) {
    if (pharmacyId == null) {
      return Optional.empty();
    }
    Optional<PharmacyGeo> pharmacy = lookup.findPharmacy(pharmacyId);
    if (pharmacy.isEmpty()) {
      return Optional.empty();
    }
    Optional<ZoneRow> zoneOpt = zones.findContaining(deliveryLat, deliveryLng);
    if (zoneOpt.isEmpty() || !zoneOpt.get().serviceable()) {
      return Optional.empty();
    }
    ZoneRow zone = zoneOpt.get();
    PharmacyGeo ph = pharmacy.get();
    RouteEstimate route =
        distanceMatrix.estimateDriving(ph.lat(), ph.lng(), deliveryLat, deliveryLng);
    BigDecimal orderValue = DeliveryFeeFormula.paiseToRupees(Math.max(itemTotalPaise, 0L));
    Breakdown b =
        DeliveryFeeFormula.breakdown(
            zone.baseFee(),
            zone.perKmFee(),
            route.distanceKm(),
            orderValue,
            zone.freeDeliveryThreshold(),
            zone.surgeActive(),
            zone.surgeMultiplier(),
            pricingConfig.handlingFeeRupees());
    // FREEDEL coupon: waive delivery; handling always charged
    BigDecimal delivery =
        freeDeliveryCoupon ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : b.deliveryFee();
    boolean free = freeDeliveryCoupon || b.freeDeliveryWaiver();
    BigDecimal rider = DeliveryFeeFormula.riderPayout(delivery);
    return Optional.of(
        new LockedQuote(
            zone.id(),
            BigDecimal.valueOf(route.distanceKm()).setScale(2, RoundingMode.HALF_UP),
            b.baseFee(),
            b.distanceCharge(),
            b.surgeMultiplier(),
            delivery,
            b.handlingFee(),
            free,
            rider,
            DeliveryFeeFormula.toPaise(delivery),
            DeliveryFeeFormula.toPaise(b.handlingFee())));
  }

  @Transactional
  public void lockSnapshot(UUID orderId, LockedQuote quote) {
    if (orderId == null || quote == null || quote.zoneId() == null) {
      return;
    }
    snapshots.insert(
        new Snapshot(
            orderId,
            quote.zoneId(),
            quote.distanceKm(),
            quote.baseFee(),
            quote.distanceCharge(),
            quote.surgeMultiplier(),
            quote.deliveryFee(),
            quote.handlingFee(),
            quote.freeDelivery(),
            quote.riderPayout(),
            clock.instant()));
  }

  public BigDecimal handlingFeeRupees() {
    return pricingConfig.handlingFeeRupees();
  }

  private void rateLimitFeeEstimate(String clientIp) {
    String ip = clientIp == null || clientIp.isBlank() ? "0.0.0.0" : clientIp.trim();
    String key = "delivery:fee-estimate:" + ip;
    if (!rateLimiter.tryAcquire(key, FEE_ESTIMATE_LIMIT, FEE_ESTIMATE_WINDOW_SEC)) {
      int retry =
          rateLimiter.secondsUntilAvailable(key, FEE_ESTIMATE_LIMIT, FEE_ESTIMATE_WINDOW_SEC);
      throw new AppException(
          "RATE_LIMIT_EXCEEDED", "30 req/min/IP limit exceeded", 429, Math.max(retry, 1));
    }
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

  private static boolean neg(BigDecimal v) {
    return v != null && v.compareTo(BigDecimal.ZERO) < 0;
  }

  private static BigDecimal scale2(BigDecimal v) {
    return v.setScale(2, RoundingMode.HALF_UP);
  }

  private static double money(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP).doubleValue();
  }
}
