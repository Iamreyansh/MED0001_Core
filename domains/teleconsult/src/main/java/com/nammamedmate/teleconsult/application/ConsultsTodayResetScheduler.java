package com.nammamedmate.teleconsult.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Midnight IST daily: reset teleconsult_doctors.consults_today to 0. */
@Component
@ConditionalOnProperty(
    name = "medmate.teleconsult.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ConsultsTodayResetScheduler {

  private static final Logger log = LoggerFactory.getLogger(ConsultsTodayResetScheduler.class);

  private final TeleconsultDoctorService service;

  public ConsultsTodayResetScheduler(TeleconsultDoctorService service) {
    this.service = service;
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Kolkata")
  public void resetConsultsToday() {
    int n = service.resetConsultsToday();
    if (n > 0) {
      log.info("Reset consults_today for {} teleconsult doctors", n);
    }
  }
}
