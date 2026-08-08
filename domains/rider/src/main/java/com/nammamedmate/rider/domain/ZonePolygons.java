package com.nammamedmate.rider.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoJSON Polygon helpers for delivery zones (STORY-005). Coordinates are {@code [lng, lat]} per
 * GeoJSON (unlike STORY-004 geofences which use {@code [lat, lng]}).
 */
public final class ZonePolygons {

  private ZonePolygons() {}

  /** Closed ring: ≥4 positions (3 unique + close), first equals last. */
  public static boolean isValidClosed(List<List<Double>> lngLatRing) {
    if (lngLatRing == null || lngLatRing.size() < 4) {
      return false;
    }
    for (List<Double> p : lngLatRing) {
      if (p == null || p.size() < 2 || p.get(0) == null || p.get(1) == null) {
        return false;
      }
    }
    List<Double> first = lngLatRing.get(0);
    List<Double> last = lngLatRing.get(lngLatRing.size() - 1);
    return first.get(0).doubleValue() == last.get(0).doubleValue()
        && first.get(1).doubleValue() == last.get(1).doubleValue();
  }

  /** WKT POLYGON((lng lat, ...)). */
  public static String toWkt(List<List<Double>> lngLatRing) {
    StringBuilder sb = new StringBuilder("POLYGON((");
    for (int i = 0; i < lngLatRing.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(lngLatRing.get(i).get(0)).append(' ').append(lngLatRing.get(i).get(1));
    }
    sb.append("))");
    return sb.toString();
  }

  /** Convert GeoJSON [lng,lat] ring to [lat,lng] pairs for area helper reuse. */
  public static List<double[]> toLatLng(List<List<Double>> lngLatRing) {
    List<double[]> out = new ArrayList<>(lngLatRing.size());
    for (List<Double> p : lngLatRing) {
      out.add(new double[] {p.get(1), p.get(0)});
    }
    return out;
  }

  public static double approxAreaSqKm(List<List<Double>> lngLatRing) {
    return GeofencePolygons.approxAreaSqKm(toLatLng(lngLatRing));
  }
}
