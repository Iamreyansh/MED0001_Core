package com.nammamedmate.rider.domain;

/**
 * Zone coverage from live orders / online riders.
 *
 * <p>STORY-002 used STRETCHED when ratio &gt; 0.7. STORY-005 BR-006 adds UNDER_STRAIN when {@code
 * live_orders > (online_riders - 2)}.
 */
public final class ZoneCoverage {

  private ZoneCoverage() {}

  public static boolean underStrain(int onlineRiders, int liveOrders) {
    return liveOrders > (onlineRiders - 2);
  }

  public static String status(int onlineRiders, int liveOrders) {
    if (onlineRiders <= 0) {
      return "NO_RIDERS";
    }
    if (underStrain(onlineRiders, liveOrders)) {
      return "UNDER_STRAIN";
    }
    double ratio = liveOrders / (double) Math.max(onlineRiders, 1);
    return ratio > 0.7 ? "STRETCHED" : "COVERED";
  }

  public static double ratio(int onlineRiders, int liveOrders) {
    return liveOrders / (double) Math.max(onlineRiders, 1);
  }
}
