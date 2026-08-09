package com.nammamedmate.integration.adapter.out.client;

import com.nammamedmate.integration.application.port.out.MapsClientPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline maps client for local/CI. Known Bangalore addresses → Indiranagar / MG Road coords;
 * distance via haversine; directions include both duration fields.
 */
public final class StubMapsClient implements MapsClientPort {

  private static final double AVG_SPEED_KMH = 22.0;
  private static final double TRAFFIC_FACTOR = 1.22;

  private final boolean forceZeroResults;
  private final boolean forceUnavailable;

  public StubMapsClient() {
    this(false, false);
  }

  public StubMapsClient(boolean forceZeroResults, boolean forceUnavailable) {
    this.forceZeroResults = forceZeroResults;
    this.forceUnavailable = forceUnavailable;
  }

  @Override
  public GeocodeResult geocode(String addressQuery) {
    if (forceUnavailable) {
      throw unavailable();
    }
    String q = addressQuery == null ? "" : addressQuery.toLowerCase(Locale.ROOT);
    if (forceZeroResults || q.contains("nowhere") || q.contains("zzzzz")) {
      return new GeocodeResult(0, 0, "", "", "", "ZERO_RESULTS");
    }
    if (q.contains("indiranagar")) {
      return new GeocodeResult(
          12.9716,
          77.6412,
          "12, 5th Cross Rd, Indiranagar Stage 1, Indiranagar, Bengaluru, Karnataka 560038, India",
          "ChIJ_stub_indir",
          "ROOFTOP",
          "OK");
    }
    if (q.contains("mg road") || q.contains("mgroad")) {
      return new GeocodeResult(
          12.9750,
          77.6063,
          "MG Road, Bengaluru, Karnataka 560001, India",
          "ChIJ_stub_mgroad",
          "GEOMETRIC_CENTER",
          "OK");
    }
    return new GeocodeResult(
        12.9716, 77.5946, "Bengaluru, Karnataka, India", "ChIJ_stub_blr", "APPROXIMATE", "OK");
  }

  @Override
  public ReverseGeocodeResult reverseGeocode(double lat, double lng) {
    if (forceUnavailable) {
      throw unavailable();
    }
    if (forceZeroResults) {
      return new ReverseGeocodeResult("", "", "", "", "", "", "ZERO_RESULTS");
    }
    if (Math.abs(lat - 12.9716) < 0.02 && Math.abs(lng - 77.6412) < 0.02) {
      return new ReverseGeocodeResult(
          "12, 5th Cross Rd, Indiranagar, Bengaluru, Karnataka 560038, India",
          "Indiranagar",
          "Bengaluru",
          "Karnataka",
          "560038",
          "ChIJ_stub_indir",
          "OK");
    }
    return new ReverseGeocodeResult(
        "MG Road, Bengaluru, Karnataka 560001, India",
        "MG Road",
        "Bengaluru",
        "Karnataka",
        "560001",
        "ChIJ_stub_mgroad",
        "OK");
  }

  @Override
  public List<MatrixCell> distanceMatrix(
      List<LatLng> origins, List<LatLng> destinations, String mode) {
    if (forceUnavailable) {
      throw unavailable();
    }
    List<MatrixCell> cells = new ArrayList<>();
    for (int i = 0; i < origins.size(); i++) {
      for (int j = 0; j < destinations.size(); j++) {
        LatLng o = origins.get(i);
        LatLng d = destinations.get(j);
        double km = haversineKm(o.lat(), o.lng(), d.lat(), d.lng());
        int meters = Math.max(1, (int) Math.round(km * 1000));
        int seconds = Math.max(1, (int) Math.round((km / AVG_SPEED_KMH) * 3600));
        cells.add(new MatrixCell(i, j, meters, seconds, "OK"));
      }
    }
    return cells;
  }

  @Override
  public DirectionsResult directions(LatLng origin, LatLng destination, String mode) {
    if (forceUnavailable) {
      throw unavailable();
    }
    double km = haversineKm(origin.lat(), origin.lng(), destination.lat(), destination.lng());
    int meters = Math.max(1, (int) Math.round(km * 1000));
    int duration = Math.max(1, (int) Math.round((km / AVG_SPEED_KMH) * 3600));
    int inTraffic = Math.max(duration, (int) Math.round(duration * TRAFFIC_FACTOR));
    List<DirectionStep> steps =
        List.of(
            new DirectionStep("Head toward destination", meters / 2, duration / 2),
            new DirectionStep(
                "Continue to destination", meters - meters / 2, duration - duration / 2));
    return new DirectionsResult("a~l~Fjk~uOwHJy@P", meters, duration, inTraffic, steps, "OK");
  }

  private static RuntimeException unavailable() {
    return new com.nammamedmate.kernel.error.AppException(
        "MAPS_API_UNAVAILABLE", "Google Maps API unreachable", 503);
  }

  static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
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
