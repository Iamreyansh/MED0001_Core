package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.MapsClientPort;
import com.nammamedmate.integration.application.port.out.MapsClientPort.LatLng;
import com.nammamedmate.kernel.error.AppException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MapsClientTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void stubKnownAddressesAndHaversine() {
    StubMapsClient stub = new StubMapsClient();
    assertThat(stub.geocode("Indiranagar Bangalore").lat()).isEqualTo(12.9716);
    assertThat(stub.geocode("MG Road").placeId()).contains("mgroad");
    assertThat(stub.geocode("random place").lat()).isEqualTo(12.9716);
    assertThat(stub.geocode("zzzzz nowhere").status()).isEqualTo("ZERO_RESULTS");
    assertThat(stub.reverseGeocode(12.9716, 77.6412).city()).isEqualTo("Bengaluru");
    assertThat(stub.reverseGeocode(13.0, 77.0).areaLocality()).isEqualTo("MG Road");
    List<MapsClientPort.MatrixCell> cells =
        stub.distanceMatrix(
            List.of(new LatLng(12.97, 77.64)), List.of(new LatLng(12.98, 77.65)), "DRIVING");
    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).status()).isEqualTo("OK");
    MapsClientPort.DirectionsResult dir =
        stub.directions(new LatLng(12.97, 77.64), new LatLng(12.98, 77.65), "DRIVING");
    assertThat(dir.durationInTrafficSeconds()).isGreaterThanOrEqualTo(dir.durationSeconds());
    assertThat(StubMapsClient.haversineKm(12.97, 77.64, 12.97, 77.64)).isEqualTo(0.0);
    assertThat(new MapsClientPort.DirectionsResult("p", 1, 2, 3, null, "OK").steps()).isEmpty();
  }

  @Test
  void liveFallsBackToStubWhenKeysBlank() {
    LiveMapsClient live = new LiveMapsClient("", "", "", mapper, uri -> "");
    assertThat(live.geocode("Indiranagar").status()).isEqualTo("OK");
    assertThat(live.reverseGeocode(12.9716, 77.6412).status()).isEqualTo("OK");
    assertThat(
            live.distanceMatrix(List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), null)
                .get(0)
                .status())
        .isEqualTo("OK");
    assertThat(live.directions(new LatLng(1, 2), new LatLng(3, 4), "  ").durationInTrafficSeconds())
        .isPositive();
  }

  @Test
  void liveGeocodeOkAndZeroAndAmbiguous() {
    AtomicReference<String> url = new AtomicReference<>();
    LiveMapsClient live =
        new LiveMapsClient(
            "gk",
            "dk",
            "dirk",
            mapper,
            uri -> {
              url.set(uri.toString());
              if (uri.toString().contains("ambiguous")) {
                return """
                {"status":"OK","results":[
                  {"formatted_address":"a","place_id":"p1","geometry":{"location":{"lat":1,"lng":2},"location_type":"APPROXIMATE"}},
                  {"formatted_address":"b","place_id":"p2","geometry":{"location":{"lat":1,"lng":2},"location_type":"APPROXIMATE"}}
                ]}
                """;
              }
              if (uri.toString().contains("empty")) {
                return "{\"status\":\"OK\",\"results\":[]}";
              }
              if (uri.toString().contains("zero")) {
                return "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
              }
              if (uri.toString().contains("limit")) {
                return "{\"status\":\"OVER_DAILY_LIMIT\",\"results\":[]}";
              }
              return """
              {"status":"OK","results":[{
                "formatted_address":"Addr","place_id":"pid",
                "geometry":{"location":{"lat":12.97,"lng":77.64},"location_type":"ROOFTOP"}
              }]}
              """;
            });
    assertThat(live.geocode("ok address").placeId()).isEqualTo("pid");
    assertThat(url.get()).contains("key=gk");
    assertThat(live.geocode("zero").status()).isEqualTo("ZERO_RESULTS");
    assertThat(live.geocode("empty").status()).isEqualTo("ZERO_RESULTS");
    assertThatThrownBy(() -> live.geocode("ambiguous"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_AMBIGUOUS");
    assertThatThrownBy(() -> live.geocode("limit"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
  }

  @Test
  void liveReverseAndMatrixAndDirections() {
    LiveMapsClient live =
        new LiveMapsClient(
            "gk",
            "dk",
            "dirk",
            mapper,
            uri -> {
              String u = uri.toString();
              if (u.contains("distancematrix")) {
                if (u.contains("bad")) {
                  return "{\"status\":\"REQUEST_DENIED\"}";
                }
                return """
                {"status":"OK","rows":[{"elements":[
                  {"status":"OK","distance":{"value":100},"duration":{"value":50}},
                  {"status":"ZERO_RESULTS"}
                ]}]}
                """;
              }
              if (u.contains("directions")) {
                if (u.contains("noroute")) {
                  return "{\"status\":\"OK\",\"routes\":[]}";
                }
                if (u.contains("dstatus")) {
                  return "{\"status\":\"ZERO_RESULTS\"}";
                }
                return """
                {"status":"OK","routes":[{
                  "overview_polyline":{"points":"abc"},
                  "legs":[{
                    "distance":{"value":200},
                    "duration":{"value":80},
                    "duration_in_traffic":{"value":100},
                    "steps":[{"html_instructions":"<b>Go</b> north","distance":{"value":50},"duration":{"value":20}}]
                  }]
                }]}
                """;
              }
              if (u.contains("latlng")) {
                if (u.contains("13.0")) {
                  return "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
                }
                if (u.contains("14.0")) {
                  return "{\"status\":\"UNKNOWN_ERROR\"}";
                }
                return """
                {"status":"OK","results":[{
                  "formatted_address":"F","place_id":"p",
                  "address_components":[
                    {"long_name":"Indiranagar","types":["sublocality"]},
                    {"long_name":"Bengaluru","types":["locality"]},
                    {"long_name":"Karnataka","types":["administrative_area_level_1"]},
                    {"long_name":"560038","types":["postal_code"]}
                  ]
                }]}
                """;
              }
              return "{}";
            });
    assertThat(live.reverseGeocode(12.97, 77.64).pincode()).isEqualTo("560038");
    assertThat(live.reverseGeocode(13.0, 77.0).status()).isEqualTo("ZERO_RESULTS");
    assertThatThrownBy(() -> live.reverseGeocode(14.0, 77.0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");

    List<MapsClientPort.MatrixCell> cells =
        live.distanceMatrix(
            List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4), new LatLng(5, 6)), "DRIVING");
    assertThat(cells).hasSize(2);
    assertThat(cells.get(1).status()).isEqualTo("ZERO_RESULTS");
    assertThatThrownBy(
            () -> live.distanceMatrix(List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), "bad"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");

    MapsClientPort.DirectionsResult dir =
        live.directions(new LatLng(1, 2), new LatLng(3, 4), "DRIVING");
    assertThat(dir.durationInTrafficSeconds()).isEqualTo(100);
    assertThat(dir.steps().get(0).instruction()).isEqualTo("Go north");
    assertThatThrownBy(() -> live.directions(new LatLng(1, 2), new LatLng(3, 4), "noroute"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_NO_RESULTS");
    assertThatThrownBy(() -> live.directions(new LatLng(1, 2), new LatLng(3, 4), "dstatus"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_NO_RESULTS");
  }

  @Test
  void liveHttpFailuresAndInvalidJson() {
    LiveMapsClient boom =
        new LiveMapsClient(
            "gk",
            "dk",
            "dirk",
            mapper,
            uri -> {
              throw new RuntimeException("down");
            });
    assertThatThrownBy(() -> boom.geocode("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");

    LiveMapsClient badJson = new LiveMapsClient("gk", "dk", "dirk", mapper, uri -> "not-json");
    assertThatThrownBy(() -> badJson.geocode("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
    assertThatThrownBy(() -> badJson.reverseGeocode(1, 2))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                badJson.distanceMatrix(
                    List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), "DRIVING"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
    assertThatThrownBy(() -> badJson.directions(new LatLng(1, 2), new LatLng(3, 4), "DRIVING"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");

    LiveMapsClient appEx =
        new LiveMapsClient(
            "gk",
            "dk",
            "dirk",
            mapper,
            uri -> {
              throw new AppException("MAPS_API_UNAVAILABLE", "x", 503);
            });
    assertThatThrownBy(() -> appEx.geocode("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
  }

  @Test
  void directionsWithoutTrafficUsesDuration() {
    LiveMapsClient live =
        new LiveMapsClient(
            "gk",
            "dk",
            "dirk",
            mapper,
            uri ->
                """
                {"status":"OK","routes":[{
                  "overview_polyline":{"points":"x"},
                  "legs":[{
                    "distance":{"value":10},
                    "duration":{"value":30},
                    "steps":[]
                  }]
                }]}
                """);
    assertThat(
            live.directions(new LatLng(1, 2), new LatLng(3, 4), "DRIVING")
                .durationInTrafficSeconds())
        .isEqualTo(30);
  }

  @Test
  void liveCoverageEdges() {
    assertThat(
            new LiveMapsClient(null, null, null, mapper, uri -> "{}")
                .geocode("Indiranagar")
                .status())
        .isEqualTo("OK");

    LiveMapsClient live =
        new LiveMapsClient(
            "gk",
            "dk",
            "dirk",
            mapper,
            uri -> {
              String u = uri.toString();
              if (u.contains("geocode") && !u.contains("latlng")) {
                if (u.contains("emptyacc")) {
                  return """
                  {"status":"OK","results":[{
                    "formatted_address":"A","place_id":"p",
                    "geometry":{"location":{"lat":1,"lng":2},"location_type":""}
                  }]}
                  """;
                }
                if (u.contains("noloc")) {
                  return """
                  {"status":"OK","results":[{
                    "formatted_address":"A","place_id":"p",
                    "geometry":{"location":{"lat":1,"lng":2}}
                  }]}
                  """;
                }
                if (u.contains("multiroof")) {
                  return """
                  {"status":"OK","results":[
                    {"formatted_address":"A","place_id":"p1","geometry":{"location":{"lat":1,"lng":2},"location_type":"ROOFTOP"}},
                    {"formatted_address":"B","place_id":"p2","geometry":{"location":{"lat":1,"lng":2},"location_type":"ROOFTOP"}}
                  ]}
                  """;
                }
                if (u.contains("geomcenter")) {
                  return """
                  {"status":"OK","results":[
                    {"formatted_address":"A","place_id":"p1","geometry":{"location":{"lat":1,"lng":2},"location_type":"GEOMETRIC_CENTER"}},
                    {"formatted_address":"B","place_id":"p2","geometry":{"location":{"lat":1,"lng":2},"location_type":"GEOMETRIC_CENTER"}}
                  ]}
                  """;
                }
                if (u.contains("oql")) {
                  return "{\"status\":\"OVER_QUERY_LIMIT\"}";
                }
                if (u.contains("notarray")) {
                  return "{\"status\":\"OK\",\"results\":{}}";
                }
                return "{\"status\":\"OK\",\"results\":[]}";
              }
              if (u.contains("latlng")) {
                if (u.contains("15.0")) {
                  return "{\"status\":\"OK\",\"results\":[]}";
                }
                if (u.contains("16.0")) {
                  return "{\"status\":\"OK\",\"results\":{}}";
                }
                return """
                {"status":"OK","results":[{
                  "formatted_address":null,"place_id":"p",
                  "address_components":[
                    {"long_name":"N1","types":["neighborhood"]},
                    {"long_name":"N2","types":["route"]},
                    {"long_name":"N3","types":["sublocality_level_1"]},
                    {"long_name":"C","types":["locality"]},
                    {"long_name":"S","types":["administrative_area_level_1"]},
                    {"long_name":"1","types":["postal_code"]},
                    {"long_name":"X","types":["country"]}
                  ]
                }]}
                """;
              }
              if (u.contains("distancematrix")) {
                return "{\"status\":\"OK\",\"rows\":[]}";
              }
              if (u.contains("mode=driving") || u.contains("mode%3Ddriving")) {
                return """
                {"status":"OK","routes":[{
                  "overview_polyline":{"points":"x"},
                  "legs":[{"distance":{"value":1},"duration":{"value":1},"steps":[]}]
                }]}
                """;
              }
              return "{\"status\":\"OK\",\"routes\":{}}";
            });

    assertThat(live.geocode(null).status()).isEqualTo("ZERO_RESULTS");
    assertThat(live.geocode("emptyacc").accuracy()).isEqualTo("APPROXIMATE");
    assertThat(live.geocode("noloc").accuracy()).isEqualTo("APPROXIMATE");
    assertThat(live.geocode("multiroof").placeId()).isEqualTo("p1");
    assertThatThrownBy(() -> live.geocode("geomcenter"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_AMBIGUOUS");
    assertThatThrownBy(() -> live.geocode("oql"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPS_API_UNAVAILABLE");
    assertThat(live.geocode("notarray").status()).isEqualTo("ZERO_RESULTS");
    assertThat(live.reverseGeocode(15.0, 77.0).status()).isEqualTo("ZERO_RESULTS");
    assertThat(live.reverseGeocode(16.0, 77.0).status()).isEqualTo("ZERO_RESULTS");
    assertThat(live.reverseGeocode(12.5, 77.5).areaLocality()).isEqualTo("N1");
    assertThat(live.reverseGeocode(12.5, 77.5).formattedAddress()).isEqualTo("");
    assertThat(live.distanceMatrix(List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), "  "))
        .isEmpty();
    assertThat(live.distanceMatrix(List.of(new LatLng(1, 2)), List.of(new LatLng(3, 4)), null))
        .isEmpty();
    assertThat(live.directions(new LatLng(1, 2), new LatLng(3, 4), null).distanceMeters())
        .isEqualTo(1);
    assertThat(live.directions(new LatLng(1, 2), new LatLng(3, 4), "  ").distanceMeters())
        .isEqualTo(1);
    assertThatThrownBy(() -> live.directions(new LatLng(1, 2), new LatLng(3, 4), "BICYCLING"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_NO_RESULTS");

    StubMapsClient stub = new StubMapsClient();
    assertThat(stub.geocode(null).status()).isEqualTo("OK");
    assertThat(stub.geocode("mgroad central").placeId()).contains("mgroad");
    assertThat(stub.geocode("nowhere land").status()).isEqualTo("ZERO_RESULTS");
    assertThat(stub.geocode("zzzzz street").status()).isEqualTo("ZERO_RESULTS");
    assertThat(new StubMapsClient(true, false).reverseGeocode(1, 2).status())
        .isEqualTo("ZERO_RESULTS");
    assertThat(stub.reverseGeocode(12.9716, 77.0).areaLocality()).isEqualTo("MG Road");
  }
}
