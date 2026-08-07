package com.nammamedmate.catalogue.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly monthly_demand refresh at 02:00 IST (OrderDemandPort stub until EPIC-010). */
@Component
@ConditionalOnProperty(
    name = "medmate.catalogue.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MedicineDemandScheduler {

  private final MedicineService medicineService;

  public MedicineDemandScheduler(MedicineService medicineService) {
    this.medicineService = medicineService;
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
  public void refreshDemand() {
    medicineService.refreshMonthlyDemand();
  }
}
