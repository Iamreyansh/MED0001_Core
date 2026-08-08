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
import com.nammamedmate.order.adapter.in.web.CustomerPharmacyController.AvailabilityCheckRequest;
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
class OrderServicesCoverageTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000002");
  private static final UUID MED = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private InventoryAvailabilityPort inventory;

  private SmartPharmacySelectionService smart;
  private PharmacyDiscoveryService discovery;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    InMemoryRateLimiter rl = new InMemoryRateLimiter(clock);
    smart = new SmartPharmacySelectionService(pharmacies, inventory, rl);
    discovery = new PharmacyDiscoveryService(pharmacies, inventory, rl);
  }

  @Test
  void smartSelectNullGeoAndEqualScoresAndCoordEdges() {
    PharmacyRow nullLat =
        new PharmacyRow(
            PH1, "A", "a", "addr", null, null, null, 77.6, true, false, "ACTIVE", 5, 1, 100, 10.0);
    PharmacyRow nullLng =
        new PharmacyRow(
            PH2, "B", "a", "addr", null, null, 12.9, null, true, false, "ACTIVE", 5, 1, 100, 10.0);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(nullLat, nullLng));
    when(inventory.stocksMedicine(any(), any())).thenReturn(true);
    assertThat(smart.smartSelect(customer, MED, 12.9, 77.6).get("available")).isEqualTo(false);

    PharmacyRow a =
        new PharmacyRow(
            PH1, "A", "a", "addr", null, null, 12.935, 77.613, true, false, "ACTIVE", 5, 1, 100,
            10.0);
    PharmacyRow b =
        new PharmacyRow(
            PH2, "B", "a", "addr", null, null, 12.935, 77.613, true, false, "ACTIVE", 5, 1, 100,
            10.0);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(a, b));
    Map<String, Object> data = smart.smartSelect(customer, MED, 12.9345, 77.6125);
    assertThat(data.get("available")).isEqualTo(true);
    assertThat(data.get("alternatives")).asList().hasSize(1);

    assertThatThrownBy(() -> smart.smartSelect(customer, MED, 12.9, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> smart.smartSelect(customer, MED, -91.0, 77.6))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> smart.smartSelect(customer, MED, 12.9, 181.0))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> smart.smartSelect(customer, MED, 12.9, -181.0))
        .isInstanceOf(AppException.class);
  }

  @Test
  void discoveryBranches() {
    PharmacyRow nullLng =
        new PharmacyRow(
            PH1, "A", "a", " ", null, null, 12.9, null, true, false, "ACTIVE", 4, 0, 50, null);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(nullLng));
    assertThat(discovery.nearby(customer, 12.9, 77.6, 1.0, 1).data()).isEmpty();

    PharmacyRow row =
        new PharmacyRow(
            PH1, "Sai", "Area", "  ", null, null, 12.935, 77.613, true, false, "ACTIVE", 4.5, 1, 90,
            10.0);
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(row));
    when(pharmacies.categoriesAvailable(PH1)).thenReturn(List.of());
    when(pharmacies.visibleItemsCount(PH1)).thenReturn(0);
    when(pharmacies.openHoursSummary(PH1)).thenReturn(Optional.empty());
    Map<String, Object> storefront = discovery.storefront(customer, PH1, null, null);
    assertThat(storefront.get("address")).isNull();
    assertThat(storefront.get("distance_km")).isNull();

    PharmacyRow partialGeo =
        new PharmacyRow(
            PH1, "Sai", "Area", null, null, null, 12.935, null, true, false, "ACTIVE", 4.5, 1, 90,
            10.0);
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(partialGeo));
    assertThat(discovery.storefront(customer, PH1, 12.9, 77.6).get("distance_km")).isNull();
    when(pharmacies.findById(PH1)).thenReturn(Optional.of(row));
    assertThat(discovery.storefront(customer, PH1, 12.9, null).get("distance_km")).isNull();
    assertThat(discovery.storefront(customer, PH1, null, 77.6).get("distance_km")).isNull();

    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> discovery.products(customer, PH1, null, null, 2, 5))
        .isInstanceOf(AppException.class);

    when(pharmacies.findById(PH1)).thenReturn(Optional.of(row));
    when(inventory.listVisibleProducts(eq(PH1), isNull(), isNull(), eq(2), eq(5)))
        .thenReturn(
            new ProductPage(
                List.of(new ProductRow(MED, "M", "B", "C", "1", 100, 100, false, 1, null)),
                1,
                2,
                5));
    assertThat(discovery.products(customer, PH1, null, null, 2, 5).meta().get("page")).isEqualTo(2);

    when(inventory.listVisibleProducts(eq(PH1), isNull(), isNull(), eq(1), eq(20)))
        .thenReturn(new ProductPage(List.of(), 0, 1, 0));
    assertThat(discovery.products(customer, PH1, null, null, null, null).meta().get("total_pages"))
        .isEqualTo(0);

    when(inventory.checkAvailability(eq(PH1), any()))
        .thenReturn(List.of(new StockLine(MED, "M", 0, 0, 0, false, null)));
    Map<String, Object> check = discovery.availabilityCheck(customer, PH1, List.of(MED));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> unavailable = (List<Map<String, Object>>) check.get("unavailable");
    assertThat(unavailable.getFirst().get("reason")).isEqualTo("OUT_OF_STOCK");

    when(pharmacies.findById(PH1)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> discovery.availabilityCheck(customer, PH1, List.of(MED)))
        .isInstanceOf(AppException.class);

    InMemoryRateLimiter tight = new InMemoryRateLimiter(clock);
    PharmacyDiscoveryService limited = new PharmacyDiscoveryService(pharmacies, inventory, tight);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
    for (int i = 0; i < 30; i++) {
      limited.nearby(customer, 12.9, 77.6, 3.0, 10);
    }
    assertThatThrownBy(() -> limited.nearby(customer, 12.9, 77.6, 3.0, 10))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void resultRecordsCopyNulls() {
    assertThat(new PharmacyDiscoveryService.NearbyResult(null, null).data()).isEmpty();
    assertThat(new PharmacyDiscoveryService.NearbyResult(null, null).meta()).isEmpty();
    assertThat(new PharmacyDiscoveryService.ProductsResult(null, null).data()).isEmpty();
    assertThat(new PharmacyDiscoveryService.ProductsResult(null, null).meta()).isEmpty();
    assertThat(new ProductPage(null, 0, 1, 20).items()).isEmpty();
    assertThat(new AvailabilityCheckRequest(PH1, null).medicineIds()).isNull();
    assertThat(new AvailabilityCheckRequest(PH1, List.of(MED)).medicineIds()).containsExactly(MED);
    assertThat(PharmacyDiscoveryService.discountPct(1000, 1000)).isEqualTo(0);
    assertThat(PharmacyDiscoveryService.discountPct(0, 100)).isEqualTo(0);
    assertThat(PharmacyDiscoveryService.paiseToRupees(2565)).isEqualByComparingTo("25.65");
  }
}
