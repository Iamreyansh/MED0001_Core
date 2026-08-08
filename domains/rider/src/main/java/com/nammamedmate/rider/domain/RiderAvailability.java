package com.nammamedmate.rider.domain;

import java.time.Duration;
import java.time.Instant;

public final class RiderAvailability {

  public static final Duration STALE_GPS_AFTER = Duration.ofMinutes(2);

  private RiderAvailability() {}

  /** KYC approved and not blocked — may go ONLINE (AC-001/002). */
  public static boolean canGoOnline(String accountStatus, String kycStatus) {
    if (!"APPROVED".equals(kycStatus)) {
      return false;
    }
    if ("BLOCKED".equals(accountStatus) || "PENDING_KYC".equals(accountStatus)) {
      return false;
    }
    return true;
  }

  /**
   * Fleet display status: ON_TRIP when active delivery; else ONLINE/OFFLINE (ACTIVE counts
   * offline).
   */
  public static String displayStatus(String accountStatus, boolean hasActiveDelivery) {
    if (hasActiveDelivery) {
      return "ON_TRIP";
    }
    if ("ONLINE".equals(accountStatus) || "ON_TRIP".equals(accountStatus)) {
      return "ONLINE";
    }
    return "OFFLINE";
  }

  public static boolean isStaleGps(Instant lastLocationAt, Instant now) {
    if (lastLocationAt == null) {
      // fleet: unset GPS is not "stale"; live-location endpoints treat missing separately
      return false;
    }
    return Duration.between(lastLocationAt, now).compareTo(STALE_GPS_AFTER) > 0;
  }

  public static boolean isOnlineForCoverage(String displayStatus) {
    return "ONLINE".equals(displayStatus) || "ON_TRIP".equals(displayStatus);
  }
}
