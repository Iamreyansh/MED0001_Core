package com.nammamedmate.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartPharmacySelectionServiceTest {

  private static final UUID CUST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID MED = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID PH1 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID PH2 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000002");
  private static final UUID PH3 = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000003");

  @Mock private PharmacyCandidatePort pharmacies;
  @Mock private InventoryAvailabilityPort inventory;

  private SmartPharmacySelectionService service;
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new SmartPharmacySelectionService(
            pharmacies,
            inventory,
            new InMemoryRateLimiter(
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC)));
  }

  @Test
  void selectsHighestCompositeScore() {
    PharmacyRow near = row(PH1, 12.9350, 77.6130, 90, 4.8, 10.0); // closer
    PharmacyRow farBetterFill = row(PH2, 12.9400, 77.6200, 100, 5.0, 10.0);
    when(pharmacies.findOpenNear(eq(12.9345), eq(77.6125), eq(5.0)))
        .thenReturn(List.of(near, farBetterFill));
    when(inventory.stocksMedicine(any(), eq(MED))).thenReturn(true);

    Map<String, Object> data = service.smartSelect(customer, MED, 12.9345, 77.6125);

    assertThat(data.get("available")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> selected = (Map<String, Object>) data.get("selected_pharmacy");
    assertThat(selected.get("id")).isEqualTo(PH1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> alts = (List<Map<String, Object>>) data.get("alternatives");
    assertThat(alts).hasSize(1);
    assertThat(alts.getFirst().get("id")).isEqualTo(PH2);
  }

  @Test
  void unavailableWhenNoStockInRadius() {
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(row(PH1, 12.9350, 77.6130, 90, 4.5, 10.0)));
    when(inventory.stocksMedicine(PH1, MED)).thenReturn(false);

    Map<String, Object> data = service.smartSelect(customer, MED, 12.9345, 77.6125);

    assertThat(data.get("available")).isEqualTo(false);
    assertThat(data.get("message")).isEqualTo("Currently unavailable near you");
    assertThat(data.get("selected_pharmacy")).isNull();
    assertThat(data.get("alternatives")).isEqualTo(List.of());
  }

  @Test
  void skipsClosedAndNullGeo() {
    PharmacyRow closed =
        new PharmacyRow(
            PH3, "Closed", "Area", "Addr", null, null, 12.935, 77.613, false, false, "ACTIVE", 5, 1,
            100, 10.0);
    PharmacyRow noGeo =
        new PharmacyRow(
            PH2, "NoGeo", "Area", "Addr", null, null, null, null, true, false, "ACTIVE", 5, 1, 100,
            10.0);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(closed, noGeo));
    when(inventory.stocksMedicine(any(), any())).thenReturn(true);

    Map<String, Object> data = service.smartSelect(customer, MED, 12.9345, 77.6125);
    assertThat(data.get("available")).isEqualTo(false);
  }

  @Test
  void validationAndAuth() {
    assertThatThrownBy(() -> service.smartSelect(null, MED, 12.9, 77.6))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal staff =
        new MedmatePrincipal(CUST, AuthRole.PHARMACY_OWNER, PH1, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.smartSelect(staff, MED, 12.9, 77.6))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.smartSelect(customer, null, 12.9, 77.6))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.smartSelect(customer, MED, null, 77.6))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.smartSelect(customer, MED, 91.0, 77.6))
        .isInstanceOf(AppException.class);
  }

  @Test
  void rateLimited() {
    InMemoryRateLimiter tight =
        new InMemoryRateLimiter(Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));
    SmartPharmacySelectionService limited =
        new SmartPharmacySelectionService(pharmacies, inventory, tight);
    when(pharmacies.findOpenNear(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
    for (int i = 0; i < 30; i++) {
      limited.smartSelect(customer, MED, 12.9, 77.6);
    }
    assertThatThrownBy(() -> limited.smartSelect(customer, MED, 12.9, 77.6))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }

  private static PharmacyRow row(
      UUID id, double lat, double lng, double fill, double rating, Double prep) {
    return new PharmacyRow(
        id,
        "Ph-" + id.toString().substring(0, 8),
        "Koramangala, Bengaluru",
        "12 Road",
        "https://cdn/logo.png",
        "Free delivery",
        lat,
        lng,
        true,
        false,
        "ACTIVE",
        rating,
        10,
        fill,
        prep);
  }
}
