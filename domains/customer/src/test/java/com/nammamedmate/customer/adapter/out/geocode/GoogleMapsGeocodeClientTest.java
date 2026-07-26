package com.nammamedmate.customer.adapter.out.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.customer.application.port.out.GeocodePort.SuggestedAddress;
import com.nammamedmate.kernel.error.AppException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GoogleMapsGeocodeClientTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void parse_okResult() {
    String body =
        """
        {
          "status": "OK",
          "results": [{
            "formatted_address": "MG Road, Bengaluru, Karnataka 560001, India",
            "address_components": [
              {"long_name": "MG Road", "types": ["route"]},
              {"long_name": "Bengaluru", "types": ["locality"]},
              {"long_name": "Karnataka", "types": ["administrative_area_level_1"]},
              {"long_name": "560001", "types": ["postal_code"]}
            ]
          }]
        }
        """;
    GoogleMapsGeocodeClient client = new GoogleMapsGeocodeClient("key", mapper, uri -> body);

    SuggestedAddress address = client.reverseGeocode(12.97, 77.59);

    assertThat(address.city()).isEqualTo("Bengaluru");
    assertThat(address.state()).isEqualTo("Karnataka");
    assertThat(address.pincode()).isEqualTo("560001");
    assertThat(address.areaLocality()).isEqualTo("MG Road");
    assertThat(address.formattedAddress()).contains("Bengaluru");
  }

  @Test
  void parse_neighborhoodAndEmptyAreaBranches() {
    String body =
        """
        {
          "status": "OK",
          "results": [{
            "formatted_address": "N",
            "address_components": [
              {"long_name": "First", "types": ["neighborhood"]},
              {"long_name": "Second", "types": ["route"]},
              {"long_name": "Bengaluru", "types": ["locality"]},
              {"long_name": "Karnataka", "types": ["administrative_area_level_1"]},
              {"long_name": "560001", "types": ["postal_code"]}
            ]
          }]
        }
        """;
    GoogleMapsGeocodeClient client = new GoogleMapsGeocodeClient("key", mapper, uri -> body);

    assertThat(client.reverseGeocode(1, 2).areaLocality()).isEqualTo("First");
  }

  @Test
  void parse_sublocalityPreferred() {
    String body =
        """
        {
          "status": "OK",
          "results": [{
            "formatted_address": "Whitefield",
            "address_components": [
              {"long_name": "Whitefield", "types": ["sublocality", "sublocality_level_1"]},
              {"long_name": "Bengaluru", "types": ["locality"]},
              {"long_name": "Karnataka", "types": ["administrative_area_level_1"]},
              {"long_name": "560066", "types": ["postal_code"]}
            ]
          }]
        }
        """;
    GoogleMapsGeocodeClient client = new GoogleMapsGeocodeClient("key", mapper, uri -> body);

    assertThat(client.reverseGeocode(1, 2).areaLocality()).isEqualTo("Whitefield");
  }

  @Test
  void parse_errorStatus_throws() {
    GoogleMapsGeocodeClient client =
        new GoogleMapsGeocodeClient("key", mapper, uri -> "{\"status\":\"REQUEST_DENIED\"}");

    assertThatThrownBy(() -> client.reverseGeocode(1, 2))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_SERVICE_ERROR");
  }

  @Test
  void parse_emptyResults_throws() {
    GoogleMapsGeocodeClient client =
        new GoogleMapsGeocodeClient(
            "key", mapper, uri -> "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");

    assertThatThrownBy(() -> client.reverseGeocode(1, 2))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_SERVICE_ERROR");
  }

  @Test
  void httpFailure_throws() {
    Function<URI, String> failing =
        uri -> {
          throw new IllegalStateException("boom");
        };
    GoogleMapsGeocodeClient client = new GoogleMapsGeocodeClient("key", mapper, failing);

    assertThatThrownBy(() -> client.reverseGeocode(1, 2))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_SERVICE_ERROR");
  }

  @Test
  void invalidJson_throws() {
    GoogleMapsGeocodeClient client = new GoogleMapsGeocodeClient("key", mapper, uri -> "not-json");

    assertThatThrownBy(() -> client.reverseGeocode(1, 2))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_SERVICE_ERROR");
  }

  @Test
  void cityFallback_andUriBuilt() {
    AtomicReference<URI> seen = new AtomicReference<>();
    String body =
        """
        {
          "status": "OK",
          "results": [{
            "formatted_address": "Somewhere",
            "address_components": [
              {"long_name": "Fallback", "types": ["political"]}
            ]
          }]
        }
        """;
    GoogleMapsGeocodeClient client =
        new GoogleMapsGeocodeClient(
            "key",
            mapper,
            uri -> {
              seen.set(uri);
              return body;
            });

    SuggestedAddress address = client.reverseGeocode(12.5, 77.5);
    assertThat(address.city()).isEqualTo("Fallback");
    assertThat(seen.get().toString()).contains("latlng=");
  }

  @Test
  void parse_resultsNotArray_throws() {
    GoogleMapsGeocodeClient client =
        new GoogleMapsGeocodeClient("key", mapper, uri -> "{\"status\":\"OK\",\"results\":{}}");

    assertThatThrownBy(() -> client.reverseGeocode(1, 2))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_SERVICE_ERROR");
  }

  @Test
  void text_missingAndNullFields() {
    String body =
        """
        {
          "status": "OK",
          "results": [{
            "formatted_address": null,
            "address_components": [
              {"long_name": null, "types": ["locality"]},
              {"types": ["administrative_area_level_1"]},
              {"long_name": "560001", "types": ["postal_code"]},
              {"long_name": "OnlyLevel", "types": ["sublocality_level_1"]}
            ]
          }]
        }
        """;
    GoogleMapsGeocodeClient client = new GoogleMapsGeocodeClient("key", mapper, uri -> body);

    SuggestedAddress address = client.reverseGeocode(1, 2);
    assertThat(address.areaLocality()).isEqualTo("OnlyLevel");
    assertThat(address.pincode()).isEqualTo("560001");
    assertThat(address.formattedAddress()).isEmpty();
  }

  @Test
  void rethrowsAppExceptionFromParse() {
    // ZERO_RESULTS with empty results already AppException; also cover AppException catch path
    // by ensuring AppException from status is not wrapped.
    GoogleMapsGeocodeClient client =
        new GoogleMapsGeocodeClient("key", mapper, uri -> "{\"status\":\"OVER_QUERY_LIMIT\"}");

    assertThatThrownBy(() -> client.reverseGeocode(1, 2))
        .isInstanceOf(AppException.class)
        .hasMessageContaining("Google Maps API returned an error");
  }
}
