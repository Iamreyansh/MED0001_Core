package com.nammamedmate.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls expired catalogue pauses and restores item visibility. */
@Component
@ConditionalOnProperty(
    name = "medmate.pharmacy.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CataloguePauseResumeScheduler {

  private final CataloguePauseService service;

  public CataloguePauseResumeScheduler(CataloguePauseService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${medmate.pharmacy.catalogue-pause.poll-delay-ms:60000}")
  public void resumeExpiredPauses() {
    service.resumeDuePauses();
  }
}
