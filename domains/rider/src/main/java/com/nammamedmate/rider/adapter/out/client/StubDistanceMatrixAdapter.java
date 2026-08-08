package com.nammamedmate.rider.adapter.out.client;

import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import java.util.UUID;

/**
 * Google Distance Matrix stub (EPIC-022 later). Deterministic km from rider id + pharmacy coords.
 */
public class StubDistanceMatrixAdapter implements DistanceMatrixPort {

  private static final double AVG_SPEED_KMH = 22.0;

  @Override
  public double distanceKm(UUID riderId, Double pharmacyLat, Double pharmacyLng) {
    if (riderId == null) {
      return 5.0;
    }
    int salt = Math.floorMod(riderId.hashCode(), 10);
    double base = 0.5 + salt / 20.0;
    if (pharmacyLat != null) {
      base += 0.1;
    }
    return Math.round(base * 10.0) / 10.0;
  }

  @Override
  public RouteEstimate estimateDriving(
      double originLat, double originLng, double destLat, double destLng) {
    double km = haversineKm(originLat, originLng, destLat, destLng);
    km = Math.round(km * 10.0) / 10.0;
    int minutes = Math.max(1, (int) Math.round((km / AVG_SPEED_KMH) * 60.0));
    return new RouteEstimate(km, minutes);
  }

  private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
    double r = 6371.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * r * Math.asin(Math.sqrt(a));
  }
}
