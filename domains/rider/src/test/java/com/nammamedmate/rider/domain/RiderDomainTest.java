package com.nammamedmate.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiderDomainTest {

  @Test
  void availabilityAndCoverage() {
    assertThat(RiderAvailability.canGoOnline("ACTIVE", "APPROVED")).isTrue();
    assertThat(RiderAvailability.canGoOnline("OFFLINE", "APPROVED")).isTrue();
    assertThat(RiderAvailability.canGoOnline("BLOCKED", "APPROVED")).isFalse();
    assertThat(RiderAvailability.canGoOnline("ACTIVE", "SUBMITTED")).isFalse();
    assertThat(RiderAvailability.displayStatus("ONLINE", true)).isEqualTo("ON_TRIP");
    assertThat(RiderAvailability.displayStatus("ONLINE", false)).isEqualTo("ONLINE");
    assertThat(RiderAvailability.displayStatus("ACTIVE", false)).isEqualTo("OFFLINE");
    assertThat(RiderAvailability.isStaleGps(null, Instant.parse("2026-07-24T10:00:00Z"))).isFalse();
    assertThat(
            RiderAvailability.isStaleGps(
                Instant.parse("2026-07-24T09:57:00Z"), Instant.parse("2026-07-24T10:00:00Z")))
        .isTrue();
    assertThat(
            RiderAvailability.isStaleGps(
                Instant.parse("2026-07-24T09:59:00Z"), Instant.parse("2026-07-24T10:00:00Z")))
        .isFalse();
    assertThat(RiderAvailability.isOnlineForCoverage("ONLINE")).isTrue();
    assertThat(RiderAvailability.isOnlineForCoverage("ON_TRIP")).isTrue();
    assertThat(RiderAvailability.isOnlineForCoverage("OFFLINE")).isFalse();
    assertThat(ZoneCoverage.status(0, 5)).isEqualTo("NO_RIDERS");
    assertThat(ZoneCoverage.status(2, 5)).isEqualTo("UNDER_STRAIN");
    assertThat(ZoneCoverage.underStrain(2, 5)).isTrue();
    assertThat(ZoneCoverage.status(5, 2)).isEqualTo("COVERED");
    assertThat(ZoneCoverage.status(10, 8)).isEqualTo("STRETCHED");
    assertThat(ZoneCoverage.ratio(2, 5)).isEqualTo(2.5);
  }

  @Test
  void vehiclePlatesNormalizeAndValidate() {
    assertThat(VehiclePlates.normalize("ka-01-ab-1234")).isEqualTo("KA01AB1234");
    assertThat(VehiclePlates.isValid("KA01AB1234")).isTrue();
    assertThat(VehiclePlates.isValid("MH12DE1234")).isTrue();
    assertThat(VehiclePlates.isValid("KA-123")).isFalse();
    assertThat(VehiclePlates.normalize(null)).isNull();
    assertThat(VehiclePlates.normalize("   ")).isNull();
    assertThat(VehiclePlates.isValid(null)).isFalse();
  }

  @Test
  void geofencePolygonsValidateAndWkt() {
    List<double[]> ring =
        List.of(
            new double[] {12.92, 77.61},
            new double[] {12.945, 77.61},
            new double[] {12.945, 77.64},
            new double[] {12.92, 77.64},
            new double[] {12.92, 77.61});
    assertThat(GeofencePolygons.isValidClosed(ring)).isTrue();
    assertThat(GeofencePolygons.isValidClosed(List.of(new double[] {1, 2}))).isFalse();
    assertThat(GeofencePolygons.isValidClosed(null)).isFalse();
    java.util.ArrayList<double[]> nullFirst = new java.util.ArrayList<>();
    nullFirst.add(null);
    nullFirst.add(new double[] {1, 2});
    nullFirst.add(new double[] {2, 3});
    nullFirst.add(new double[] {1, 2});
    assertThat(GeofencePolygons.isValidClosed(nullFirst)).isFalse();
    java.util.ArrayList<double[]> nullLast = new java.util.ArrayList<>();
    nullLast.add(new double[] {1, 2});
    nullLast.add(new double[] {2, 3});
    nullLast.add(new double[] {3, 4});
    nullLast.add(null);
    assertThat(GeofencePolygons.isValidClosed(nullLast)).isFalse();
    assertThat(
            GeofencePolygons.isValidClosed(
                List.of(
                    new double[] {1}, new double[] {1, 2}, new double[] {2, 3}, new double[] {1})))
        .isFalse();
    assertThat(
            GeofencePolygons.isValidClosed(
                List.of(
                    new double[] {1, 2},
                    new double[] {1, 2},
                    new double[] {2, 3},
                    new double[] {1})))
        .isFalse();
    assertThat(
            GeofencePolygons.isValidClosed(
                List.of(
                    new double[] {1, 2},
                    new double[] {2, 3},
                    new double[] {3, 4},
                    new double[] {9, 9})))
        .isFalse();
    assertThat(
            GeofencePolygons.isValidClosed(
                List.of(
                    new double[] {1, 2},
                    new double[] {2, 3},
                    new double[] {3, 4},
                    new double[] {1, 9})))
        .isFalse();
    assertThat(GeofencePolygons.toWkt(ring)).startsWith("POLYGON((");
    assertThat(GeofencePolygons.approxAreaSqKm(ring)).isPositive();
    assertThat(GeofencePolygons.approxAreaSqKm(null)).isZero();
  }

  @Test
  void zonePolygonsAndDeliveryFee() {
    List<List<Double>> ring =
        List.of(
            List.of(77.61, 12.92),
            List.of(77.64, 12.92),
            List.of(77.64, 12.945),
            List.of(77.61, 12.945),
            List.of(77.61, 12.92));
    assertThat(ZonePolygons.isValidClosed(ring)).isTrue();
    assertThat(ZonePolygons.isValidClosed(null)).isFalse();
    assertThat(ZonePolygons.isValidClosed(List.of(List.of(1.0)))).isFalse();
    java.util.ArrayList<List<Double>> nullFirst = new java.util.ArrayList<>();
    nullFirst.add(null);
    nullFirst.add(List.of(1.0, 2.0));
    nullFirst.add(List.of(2.0, 3.0));
    nullFirst.add(List.of(1.0, 2.0));
    assertThat(ZonePolygons.isValidClosed(nullFirst)).isFalse();
    assertThat(
            ZonePolygons.isValidClosed(
                List.of(List.of(1.0), List.of(1.0, 2.0), List.of(2.0, 3.0), List.of(1.0))))
        .isFalse();
    java.util.ArrayList<List<Double>> nullCoord = new java.util.ArrayList<>();
    nullCoord.add(List.of(1.0, 2.0));
    nullCoord.add(java.util.Arrays.asList(null, 2.0));
    nullCoord.add(List.of(2.0, 3.0));
    nullCoord.add(List.of(1.0, 2.0));
    assertThat(ZonePolygons.isValidClosed(nullCoord)).isFalse();
    assertThat(
            ZonePolygons.isValidClosed(
                List.of(
                    List.of(1.0, 2.0), List.of(2.0, 3.0), List.of(3.0, 4.0), List.of(9.0, 9.0))))
        .isFalse();
    java.util.ArrayList<List<Double>> shortLast = new java.util.ArrayList<>();
    shortLast.add(List.of(1.0, 2.0));
    shortLast.add(List.of(2.0, 3.0));
    shortLast.add(List.of(3.0, 4.0));
    shortLast.add(List.of(1.0));
    assertThat(ZonePolygons.isValidClosed(shortLast)).isFalse();
    java.util.ArrayList<List<Double>> nullY = new java.util.ArrayList<>();
    nullY.add(List.of(1.0, 2.0));
    nullY.add(List.of(2.0, 3.0));
    nullY.add(List.of(3.0, 4.0));
    nullY.add(java.util.Arrays.asList(1.0, null));
    assertThat(ZonePolygons.isValidClosed(nullY)).isFalse();
    assertThat(
            ZonePolygons.isValidClosed(
                List.of(
                    List.of(1.0, 2.0), List.of(2.0, 3.0), List.of(3.0, 4.0), List.of(1.0, 9.0))))
        .isFalse();
    assertThat(ZonePolygons.toWkt(ring)).startsWith("POLYGON((");
    assertThat(ZonePolygons.approxAreaSqKm(ring)).isPositive();
    assertThat(
            DeliveryFeeFormula.estimateRupees(
                new java.math.BigDecimal("25"),
                new java.math.BigDecimal("5"),
                2.0,
                new java.math.BigDecimal("100"),
                new java.math.BigDecimal("199"),
                true,
                new java.math.BigDecimal("1.5")))
        .isEqualByComparingTo("53.00");
    assertThat(
            DeliveryFeeFormula.estimateRupees(
                new java.math.BigDecimal("25"),
                new java.math.BigDecimal("5"),
                2.0,
                new java.math.BigDecimal("200"),
                new java.math.BigDecimal("199"),
                true,
                new java.math.BigDecimal("1.5")))
        .isEqualByComparingTo("0.00");
    assertThat(
            DeliveryFeeFormula.estimateRupees(
                new java.math.BigDecimal("25"),
                new java.math.BigDecimal("5"),
                1.0,
                new java.math.BigDecimal("50"),
                new java.math.BigDecimal("199"),
                false,
                new java.math.BigDecimal("1.5")))
        .isEqualByComparingTo("30.00");
    assertThat(
            DeliveryFeeFormula.estimateRupees(
                null, null, -1.0, null, null, true, new java.math.BigDecimal("0.5")))
        .isEqualByComparingTo("0.00");
    assertThat(
            DeliveryFeeFormula.estimateRupees(
                null, null, 1.0, new java.math.BigDecimal("10"), null, true, null))
        .isEqualByComparingTo("0.00");
    assertThat(DeliveryFeeFormula.effectiveSurge(false, new java.math.BigDecimal("2.0")))
        .isEqualByComparingTo("1.00");
    assertThat(DeliveryFeeFormula.riderPayout(new java.math.BigDecimal("10.00")))
        .isEqualByComparingTo("15.00");
    assertThat(DeliveryFeeFormula.riderPayout(null)).isEqualByComparingTo("15.00");
    assertThat(DeliveryFeeFormula.riderPayout(new java.math.BigDecimal("41.00")))
        .isEqualByComparingTo("40.30");
    assertThat(
            DeliveryFeeFormula.riderPayoutNote(
                java.math.BigDecimal.ZERO, new java.math.BigDecimal("15")))
        .contains("Free delivery");
    assertThat(DeliveryFeeFormula.riderPayoutNote(null, new java.math.BigDecimal("15")))
        .contains("Free delivery");
    assertThat(
            DeliveryFeeFormula.riderPayoutNote(
                new java.math.BigDecimal("10"), new java.math.BigDecimal("15")))
        .contains("minimum");
    assertThat(
            DeliveryFeeFormula.riderPayoutNote(
                new java.math.BigDecimal("41"), new java.math.BigDecimal("40.30")))
        .contains("above");
    assertThat(DeliveryFeeFormula.roundRupee(null)).isEqualByComparingTo("0.00");
    assertThat(DeliveryFeeFormula.toPaise(new java.math.BigDecimal("40.00"))).isEqualTo(4000L);
    assertThat(DeliveryFeeFormula.toPaise(null)).isZero();
    assertThat(DeliveryFeeFormula.paiseToRupees(4000L)).isEqualByComparingTo("40.00");
    var free =
        DeliveryFeeFormula.breakdown(
            new java.math.BigDecimal("25"),
            new java.math.BigDecimal("5"),
            2.0,
            new java.math.BigDecimal("200"),
            new java.math.BigDecimal("199"),
            false,
            java.math.BigDecimal.ONE,
            null);
    assertThat(free.freeDeliveryWaiver()).isTrue();
    assertThat(free.riderPayout()).isEqualByComparingTo("15.00");
  }

  @Test
  void riderPhonesNormalize() {
    assertThat(RiderPhones.normalize("9876543210")).isEqualTo("+919876543210");
    assertThat(RiderPhones.normalize("+919876543210")).isEqualTo("+919876543210");
    assertThat(RiderPhones.normalize("919876543210")).isEqualTo("+919876543210");
    assertThat(RiderPhones.normalize("123")).isNull();
    assertThat(RiderPhones.normalize(null)).isNull();
    assertThat(RiderPhones.isValid("+919876543210")).isTrue();
    assertThat(RiderPhones.isValid("9876543210")).isFalse();
  }
}
