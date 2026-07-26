package com.nammamedmate.customer.application.port.out;

public interface GeocodePort {

  SuggestedAddress reverseGeocode(double latitude, double longitude);

  record SuggestedAddress(
      String flatBuilding,
      String areaLocality,
      String city,
      String state,
      String pincode,
      String formattedAddress,
      double latitude,
      double longitude) {}
}
