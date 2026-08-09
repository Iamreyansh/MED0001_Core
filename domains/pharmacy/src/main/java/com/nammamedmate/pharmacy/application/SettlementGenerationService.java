package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore;
import com.nammamedmate.pharmacy.application.port.out.SettlementStore.SettlementRow;
import com.nammamedmate.pharmacy.domain.SettlementCalculator;
import com.nammamedmate.pharmacy.domain.SettlementPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Weekly Mon–Sun settlement record generation (Monday morning IST). */
@Service
public class SettlementGenerationService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final AdminPharmacyStore pharmacies;
  private final SettlementStore settlements;
  private final PharmacyOrderMetricsPort orderMetrics;
  private final Clock clock;

  public SettlementGenerationService(
      AdminPharmacyStore pharmacies,
      SettlementStore settlements,
      PharmacyOrderMetricsPort orderMetrics,
      Clock clock) {
    this.pharmacies = pharmacies;
    this.settlements = settlements;
    this.orderMetrics = orderMetrics;
    this.clock = clock;
  }

  @Transactional
  public int generateWeeklySettlements() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    LocalDate periodStart = SettlementPeriod.previousWeekMonday(today);
    LocalDate periodEnd = SettlementPeriod.previousWeekSunday(today);
    Instant now = clock.instant();
    int created = 0;

    for (UUID pharmacyId : pharmacies.listActivePharmacyIds()) {
      if (settlements.existsForPeriod(pharmacyId, periodStart, periodEnd)) {
        continue;
      }
      AdminDetailRow pharmacy = pharmacies.findDetail(pharmacyId).orElse(null);
      if (pharmacy == null) {
        continue;
      }

      long gmvPaise = orderMetrics.gmvForPeriodPaise(pharmacyId, periodStart, periodEnd);
      long annualGmvYtd = orderMetrics.annualGmvYtdPaise(pharmacyId);
      var amounts = SettlementCalculator.compute(gmvPaise, pharmacy.commissionPct(), annualGmvYtd);
      long carryPaise = settlements.sumUnconsumedCarryForwardPaise(pharmacyId);
      long netPaid = amounts.netPaidPaise() + carryPaise;

      // Skip zero-GMV pharmacies with nothing carried forward (STORY-003 notes).
      if (gmvPaise == 0L && carryPaise == 0L) {
        continue;
      }

      settlements.insert(
          new SettlementRow(
              Ids.newId(),
              pharmacyId,
              periodStart,
              periodEnd,
              gmvPaise,
              pharmacy.commissionPct(),
              amounts.commissionEarnedPaise(),
              amounts.tcsRatePct(),
              amounts.tcsDeductedPaise(),
              netPaid,
              "PENDING_RELEASE",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              now,
              now));
      if (carryPaise > 0) {
        settlements.markCarryForwardConsumed(pharmacyId, now);
      }
      created++;
    }
    return created;
  }
}
