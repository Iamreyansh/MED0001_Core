package com.nammamedmate.rider.application.port.out;

import java.util.UUID;

/** Google Distance Matrix — stub until EPIC-022 maps integration. */
public interface DistanceMatrixPort {

  record RouteEstimate(double distanceKm, int durationMinutes) {}

  /**
   * Distance in km from rider to pharmacy. Stub uses pharmacy coords + rider id salt when GPS
   * absent.
   */
  double distanceKm(UUID riderId, Double pharmacyLat, Double pharmacyLng);

  /** Driving ETA from origin → destination (STORY-004). */
  default RouteEstimate estimateDriving(
      double originLat, double originLng, double destLat, double destLng) {
    return new RouteEstimate(1.0, 5);
  }
}
