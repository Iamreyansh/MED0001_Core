package com.nammamedmate.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks stale penny-drop verifications as FAILED after 24h (STORY-005). */
@Component
@ConditionalOnProperty(
    name = "medmate.pharmacy.profile.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PennyDropMaintenanceScheduler {

  private final PharmacyProfileService profileService;

  public PennyDropMaintenanceScheduler(PharmacyProfileService profileService) {
    this.profileService = profileService;
  }

  @Scheduled(fixedDelayString = "${medmate.pharmacy.profile.penny-drop-delay-ms:3600000}")
  public void expireStalePennyDrops() {
    profileService.expireStalePennyDrops();
  }
}
