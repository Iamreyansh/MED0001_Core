package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.PharmacyThrottlePort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class StubPharmacyThrottleAdapter implements PharmacyThrottlePort {

  private final Map<UUID, PharmacyState> pharmacies = new ConcurrentHashMap<>();
  private boolean forceThrottleMiss;

  public StubPharmacyThrottleAdapter() {
    UUID id = UUID.fromString("22222222-2222-4222-8222-222222222222");
    pharmacies.put(
        id, new PharmacyState("Medplus - HSR Layout", new BigDecimal("92"), 0, 0, 20, 20, false));
  }

  @Override
  public boolean pharmacyExists(UUID pharmacyId) {
    return pharmacies.containsKey(pharmacyId);
  }

  @Override
  public String pharmacyName(UUID pharmacyId) {
    PharmacyState s = pharmacies.get(pharmacyId);
    return s == null ? pharmacyId.toString() : s.name();
  }

  @Override
  public Optional<ThrottleResult> throttleByPercent(UUID pharmacyId, int throttlePct) {
    if (forceThrottleMiss) {
      return Optional.empty();
    }
    PharmacyState s = pharmacies.get(pharmacyId);
    if (s == null) {
      return Optional.empty();
    }
    int previous = s.currentCap();
    int reduction =
        BigDecimal.valueOf(previous)
            .multiply(BigDecimal.valueOf(throttlePct))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
            .intValue();
    int neu = Math.max(1, previous - reduction);
    pharmacies.put(
        pharmacyId,
        new PharmacyState(
            s.name(),
            s.fillRatePct(),
            s.consecutiveLowDays(),
            s.consecutiveRecoveryDays(),
            neu,
            previous,
            true));
    return Optional.of(new ThrottleResult(previous, neu, s.name()));
  }

  @Override
  public Optional<ThrottleResult> recoverCap(UUID pharmacyId) {
    PharmacyState s = pharmacies.get(pharmacyId);
    if (s == null || !s.throttled()) {
      return Optional.empty();
    }
    int previous = s.currentCap();
    int recovered = s.preThrottleCap();
    pharmacies.put(
        pharmacyId,
        new PharmacyState(
            s.name(),
            s.fillRatePct(),
            s.consecutiveLowDays(),
            s.consecutiveRecoveryDays(),
            recovered,
            recovered,
            false));
    return Optional.of(new ThrottleResult(previous, recovered, s.name()));
  }

  @Override
  public List<PharmacyFillSnapshot> candidatesForThrottle(
      BigDecimal fillRateMaxPct, int consecutiveDays) {
    List<PharmacyFillSnapshot> out = new ArrayList<>();
    for (Map.Entry<UUID, PharmacyState> e : pharmacies.entrySet()) {
      PharmacyState s = e.getValue();
      if (!s.throttled()
          && s.fillRatePct().compareTo(fillRateMaxPct) < 0
          && s.consecutiveLowDays() >= consecutiveDays) {
        out.add(toSnap(e.getKey(), s));
      }
    }
    return out;
  }

  @Override
  public List<PharmacyFillSnapshot> candidatesForRecovery(
      BigDecimal fillRateMinPct, int consecutiveDays) {
    List<PharmacyFillSnapshot> out = new ArrayList<>();
    for (Map.Entry<UUID, PharmacyState> e : pharmacies.entrySet()) {
      PharmacyState s = e.getValue();
      if (s.throttled()
          && s.fillRatePct().compareTo(fillRateMinPct) > 0
          && s.consecutiveRecoveryDays() >= consecutiveDays) {
        out.add(toSnap(e.getKey(), s));
      }
    }
    return out;
  }

  public void put(
      UUID id,
      String name,
      BigDecimal fillRate,
      int lowDays,
      int recoveryDays,
      int cap,
      boolean throttled) {
    pharmacies.put(
        id, new PharmacyState(name, fillRate, lowDays, recoveryDays, cap, cap, throttled));
  }

  public void setForceThrottleMiss(boolean forceThrottleMiss) {
    this.forceThrottleMiss = forceThrottleMiss;
  }

  private static PharmacyFillSnapshot toSnap(UUID id, PharmacyState s) {
    return new PharmacyFillSnapshot(
        id,
        s.name(),
        s.fillRatePct(),
        s.consecutiveLowDays(),
        s.consecutiveRecoveryDays(),
        s.currentCap(),
        s.throttled());
  }

  private record PharmacyState(
      String name,
      BigDecimal fillRatePct,
      int consecutiveLowDays,
      int consecutiveRecoveryDays,
      int currentCap,
      int preThrottleCap,
      boolean throttled) {}
}
