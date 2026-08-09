package com.nammamedmate.integration.application.port.out;

import java.util.List;

/** Google Maps Platform client (stub when API keys blank). */
public interface MapsClientPort {

  record LatLng(double lat, double lng) {}

  record GeocodeResult(
      double lat,
      double lng,
      String formattedAddress,
      String placeId,
      String accuracy,
      String status) {}

  record ReverseGeocodeResult(
      String formattedAddress,
      String areaLocality,
      String city,
      String state,
      String pincode,
      String placeId,
      String status) {}

  record MatrixCell(
      int originIndex,
      int destinationIndex,
      int distanceMeters,
      int durationSeconds,
      String status) {}

  record DirectionStep(String instruction, int distanceMeters, int durationSeconds) {}

  record DirectionsResult(
      String routePolyline,
      int distanceMeters,
      int durationSeconds,
      int durationInTrafficSeconds,
      List<DirectionStep> steps,
      String status) {
    public DirectionsResult {
      steps = steps == null ? List.of() : List.copyOf(steps);
    }
  }

  GeocodeResult geocode(String addressQuery);

  ReverseGeocodeResult reverseGeocode(double lat, double lng);

  List<MatrixCell> distanceMatrix(List<LatLng> origins, List<LatLng> destinations, String mode);

  DirectionsResult directions(LatLng origin, LatLng destination, String mode);
}
