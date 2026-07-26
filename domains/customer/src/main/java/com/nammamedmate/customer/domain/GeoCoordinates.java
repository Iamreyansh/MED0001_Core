package com.nammamedmate.customer.domain;

public final class GeoCoordinates {

  private GeoCoordinates() {}

  public static double requireLatitude(Double latitude) {
    if (latitude == null || latitude < -90.0 || latitude > 90.0) {
      throw new IllegalArgumentException("latitude must be between -90 and 90");
    }
    return latitude;
  }

  public static double requireLongitude(Double longitude) {
    if (longitude == null || longitude < -180.0 || longitude > 180.0) {
      throw new IllegalArgumentException("longitude must be between -180 and 180");
    }
    return longitude;
  }
}
