package com.nammamedmate.customer.adapter.out.geocode;

import com.nammamedmate.customer.application.port.out.GeocodePort;

/**
 * Offline reverse-geocode for local/CI. Returns Bengaluru for the story AC coordinates (12.9716,
 * 77.5946) and nearby pins; elsewhere a generic India suggestion.
 *
 * <p>ponytail: Google Maps Geocoding API when {@code medmate.maps.geocode.api-key} is set (see
 * {@link GoogleMapsGeocodeClient}); upgrade path is EPIC-022 maps integration.
 */
public final class StubGeocodeClient implements GeocodePort {

  @Override
  public SuggestedAddress reverseGeocode(double latitude, double longitude) {
    if (isBengaluruPin(latitude, longitude)) {
      return new SuggestedAddress(
          "",
          "MG Road",
          "Bengaluru",
          "Karnataka",
          "560001",
          "MG Road, Bengaluru, Karnataka 560001, India",
          latitude,
          longitude);
    }
    return new SuggestedAddress(
        "",
        "Locality",
        "City",
        "State",
        "560000",
        "Locality, City, State 560000, India",
        latitude,
        longitude);
  }

  private static boolean isBengaluruPin(double latitude, double longitude) {
    return Math.abs(latitude - 12.9716) < 0.01 && Math.abs(longitude - 77.5946) < 0.01;
  }
}
