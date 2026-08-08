package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.order.application.PharmacyDiscoveryService.NearbyResult;
import com.nammamedmate.order.application.PharmacyDiscoveryService.ProductsResult;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.ProductPage;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.ProductRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PharmacyDiscoveryServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID MED1 = UUID.fromString("22222222-2222-4222-8222-222222222221");
  private static final UUID MED2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID MED3 = UUID.fromString("22222222-2222-4222-8222-222222222223");

  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private InventoryAvailabilityPort inventory;

  private PharmacyDiscoveryService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new PharmacyDiscoveryService(
            pharmacies,
            inventory,
            new InMemoryRateLimiter(
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)));
  }

  @Test
  void nearbyClampsRadiusAndSortsByDistance() {
    PharmacyRow far = open(PH1, 12.9500, 77.6300);
    PharmacyRow near =
        open(UUID.fromString("aaaaaaaa-0001-4000-8000-000000000099"), 12.9350, 77.6130);
    when(pharmacies.findOpenNear(eq(12.9345), eq(77.6125), eq(10.0)))
        .thenReturn(List.of(far, near));
    when(pharmacies.categoriesAvailable(any())).thenReturn(List.of("OTC"));
    when(pharmacies.visibleItemsCount(any())).thenReturn(5);

    NearbyResult result = service.nearby(customer, 12.9345, 77.6125, 15.0, 50);

    assertThat(result.meta().get("radius_km")).isEqualTo(10.0);
    assertThat(result.data()).hasSize(2);
    assertThat(result.data().getFirst().get("id")).isEqualTo(near.id());
    assertThat(((Number) result.data().getFirst().get("distance_km")).doubleValue())
        .isLessThan(((Number) result.data().get(1).get("distance_km")).doubleValue());
  }

  @Test
  void storefrontAndProductsAndAvailability() {
    PharmacyRow row = open(PH1, 12.9350, 77.6130);
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(row));
    when(pharmacies.categoriesAvailable(PH1)).thenReturn(List.of("Antibiotics"));
    when(pharmacies.visibleItemsCount(PH1)).thenReturn(12);
    when(pharmacies.openHoursSummary(PH1)).thenReturn(Optional.of("08:00 AM - 10:00 PM"));

    Map<String, Object> storefront = service.storefront(customer, PH1, 12.9345, 77.6125);
    assertThat(storefront.get("id")).isEqualTo(PH1);
    assertThat(storefront.get("open_hours")).isEqualTo("08:00 AM - 10:00 PM");
    assertThat(storefront.get("distance_km")).isNotNull();

    when(inventory.listVisibleProducts(eq(PH1), isNull(), isNull(), eq(1), eq(20)))
        .thenReturn(
            new ProductPage(
                List.of(
                    new ProductRow(
                        MED1,
                        "Metformin",
                        "USV",
                        "Diabetic Care",
                        "10 tablet",
                        2850,
                        2565,
                        true,
                        200,
                        null)),
                1,
                1,
                20));
    ProductsResult products = service.products(customer, PH1, null, null, null, null);
    assertThat(products.data()).hasSize(1);
    assertThat(products.data().getFirst().get("discount_pct")).isEqualTo(10.0);
    assertThat(products.meta().get("total_pages")).isEqualTo(1);

    when(inventory.checkAvailability(eq(PH1), any()))
        .thenReturn(
            List.of(
                new StockLine(MED1, "A", 10, 2500, 2800, true, null),
                new StockLine(MED2, "B", 5, 1000, 1200, true, null),
                new StockLine(MED3, "C", 0, 0, 1000, false, "OUT_OF_STOCK")));
    Map<String, Object> check = service.availabilityCheck(customer, PH1, List.of(MED1, MED2, MED3));
    assertThat((List<?>) check.get("available")).hasSize(2);
    assertThat((List<?>) check.get("unavailable")).hasSize(1);
  }

  @Test
  void storefrontWithoutGeoAndNotFound() {
    PharmacyRow noGeo =
        new PharmacyRow(
            PH1, "P", "A", "", null, null, null, null, true, false, "ACTIVE", 4, 1, 50, null);
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(noGeo));
    when(pharmacies.categoriesAvailable(PH1)).thenReturn(List.of());
    when(pharmacies.visibleItemsCount(PH1)).thenReturn(0);
    when(pharmacies.openHoursSummary(PH1)).thenReturn(Optional.empty());

    Map<String, Object> data = service.storefront(customer, PH1, 12.9, 77.6);
    assertThat(data.get("distance_km")).isNull();

    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.storefront(customer, PH1, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void availabilityValidation() {
    assertThatThrownBy(() -> service.availabilityCheck(customer, null, List.of(MED1)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.availabilityCheck(customer, PH1, List.of()))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.availabilityCheck(customer, PH1, null))
        .isInstanceOf(AppException.class);
  }

  @Test
  void discountAndPaiseHelpers() {
    assertThat(PharmacyDiscoveryService.discountPct(1000, 1000)).isEqualTo(0);
    assertThat(PharmacyDiscoveryService.discountPct(0, 100)).isEqualTo(0);
    assertThat(PharmacyDiscoveryService.paiseToRupees(2565)).isEqualByComparingTo("25.65");
  }

  @Test
  void nearbySkipsNullGeoAndDefaults() {
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), eq(3.0)))
        .thenReturn(
            List.of(
                new PharmacyRow(
                    PH1, "P", "A", "", null, null, null, null, true, false, "ACTIVE", 4, 0, 0,
                    null)));
    NearbyResult result = service.nearby(customer, 12.9345, 77.6125, null, null);
    assertThat(result.data()).isEmpty();
    assertThat(result.meta().get("radius_km")).isEqualTo(3.0);
  }

  private static PharmacyRow open(UUID id, double lat, double lng) {
    return new PharmacyRow(
        id,
        "Sai Medicals",
        "Koramangala, Bengaluru",
        "12, 80 Feet Road",
        "https://cdn/logo.png",
        "Free delivery on orders above ₹199",
        lat,
        lng,
        true,
        false,
        "ACTIVE",
        4.6,
        312,
        95,
        10.0);
  }
}
