package com.nammamedmate.observability_ops.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PharmacyThrottlePort {

  record ThrottleResult(int previousCap, int newCap, String pharmacyName) {}

  record PharmacyFillSnapshot(
      UUID pharmacyId,
      String name,
      BigDecimal fillRatePct,
      int consecutiveLowDays,
      int consecutiveRecoveryDays,
      int currentCap,
      boolean throttled) {}

  boolean pharmacyExists(UUID pharmacyId);

  String pharmacyName(UUID pharmacyId);

  Optional<ThrottleResult> throttleByPercent(UUID pharmacyId, int throttlePct);

  Optional<ThrottleResult> recoverCap(UUID pharmacyId);

  List<PharmacyFillSnapshot> candidatesForThrottle(BigDecimal fillRateMaxPct, int consecutiveDays);

  List<PharmacyFillSnapshot> candidatesForRecovery(BigDecimal fillRateMinPct, int consecutiveDays);
}
