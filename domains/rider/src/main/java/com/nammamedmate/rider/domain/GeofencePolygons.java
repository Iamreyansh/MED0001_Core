package com.nammamedmate.rider.domain;

import java.util.List;

/** Polygon validation + WKT for PostGIS geofences (STORY-004). */
public final class GeofencePolygons {

  private GeofencePolygons() {}

  /** Closed ring: ≥4 vertices (3 unique + close), first equals last. */
  public static boolean isValidClosed(List<double[]> ring) {
    if (ring == null || ring.size() < 4) {
      return false;
    }
    double[] first = ring.get(0);
    double[] last = ring.get(ring.size() - 1);
    if (first == null || last == null) {
      return false;
    }
    if (first.length < 2 || last.length < 2) {
      return false;
    }
    return first[0] == last[0] && first[1] == last[1];
  }

  /** WKT POLYGON((lng lat, ...)) — GeoJSON-ish input is [lat, lng] per story. */
  public static String toWkt(List<double[]> latLngRing) {
    StringBuilder sb = new StringBuilder("POLYGON((");
    for (int i = 0; i < latLngRing.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      double lat = latLngRing.get(i)[0];
      double lng = latLngRing.get(i)[1];
      sb.append(lng).append(' ').append(lat);
    }
    sb.append("))");
    return sb.toString();
  }

  /** Approximate spherical area in km² (shoelace on equirectangular projection). */
  public static double approxAreaSqKm(List<double[]> latLngRing) {
    if (latLngRing == null || latLngRing.size() < 4) {
      return 0;
    }
    double lat0 = latLngRing.get(0)[0];
    double cos = Math.cos(Math.toRadians(lat0));
    double area = 0;
    for (int i = 0; i < latLngRing.size() - 1; i++) {
      double x1 = latLngRing.get(i)[1] * cos * 111.32;
      double y1 = latLngRing.get(i)[0] * 110.574;
      double x2 = latLngRing.get(i + 1)[1] * cos * 111.32;
      double y2 = latLngRing.get(i + 1)[0] * 110.574;
      area += x1 * y2 - x2 * y1;
    }
    return Math.round(Math.abs(area) / 2.0 * 1000.0) / 1000.0;
  }
}
