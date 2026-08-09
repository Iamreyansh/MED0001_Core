package com.nammamedmate.integration.domain;

/**
 * Ray-casting point-in-polygon (even-odd rule). Coordinates are [lat, lng] pairs matching the story
 * contract.
 */
public final class PointInPolygon {

  private PointInPolygon() {}

  public static boolean contains(double lat, double lng, double[][] polygon) {
    if (polygon == null || polygon.length < 3) {
      return false;
    }
    boolean inside = false;
    int n = polygon.length;
    for (int i = 0, j = n - 1; i < n; j = i++) {
      double yi = polygon[i][0];
      double xi = polygon[i][1];
      double yj = polygon[j][0];
      double xj = polygon[j][1];
      boolean intersect =
          ((yi > lat) != (yj > lat)) && (lng < (xj - xi) * (lat - yi) / (yj - yi + 0.0) + xi);
      if (intersect) {
        inside = !inside;
      }
    }
    return inside;
  }
}
