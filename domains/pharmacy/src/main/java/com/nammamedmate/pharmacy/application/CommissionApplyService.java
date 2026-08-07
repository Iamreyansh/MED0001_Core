package com.nammamedmate.pharmacy.application;

import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore;
import com.nammamedmate.pharmacy.application.port.out.CommissionHistoryStore.CommissionHistoryRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies scheduled commission changes at 00:01 IST on effective_from. */
@Service
public class CommissionApplyService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final CommissionHistoryStore commissionHistory;
  private final AdminPharmacyStore pharmacies;
  private final Clock clock;

  public CommissionApplyService(
      CommissionHistoryStore commissionHistory, AdminPharmacyStore pharmacies, Clock clock) {
    this.commissionHistory = commissionHistory;
    this.pharmacies = pharmacies;
    this.clock = clock;
  }

  @Transactional
  public int applyDueChanges() {
    LocalDate today = LocalDate.now(clock.withZone(IST));
    List<CommissionHistoryRow> due = commissionHistory.findDueForApply(today);
    Instant now = clock.instant();
    for (CommissionHistoryRow row : due) {
      pharmacies.updateCommissionPct(row.pharmacyId(), row.newCommissionPct(), now);
      commissionHistory.markApplied(row.id(), now);
    }
    return due.size();
  }
}
