package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyStorefrontController;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyStorefrontController.CataloguePauseRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyStorefrontController.StorefrontRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyStorefrontController.ZoneRequest;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyStorefrontController;
import com.nammamedmate.pharmacy.application.CataloguePauseService;
import com.nammamedmate.pharmacy.application.PharmacyStorefrontService;
import com.nammamedmate.security.MedmatePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyStorefrontAdapterCoverageTest {

  private static final UUID PID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void adminStorefrontController() {
    PharmacyStorefrontService storefront = mock(PharmacyStorefrontService.class);
    CataloguePauseService pause = mock(CataloguePauseService.class);
    AdminPharmacyStorefrontController controller =
        new AdminPharmacyStorefrontController(storefront, pause);
    MedmatePrincipal principal = mock(MedmatePrincipal.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    when(storefront.adminToggleStorefront(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("is_online", false));
    when(storefront.reassignZone(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("new_zone_id", "z"));
    when(pause.pauseCatalogue(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("catalogue_paused", true));

    ApiResponse<Map<String, Object>> toggle =
        controller.toggleStorefront(
            principal, PID, new StorefrontRequest(false, "reason"), request);
    assertThat(toggle.data().get("is_online")).isEqualTo(false);

    ApiResponse<Map<String, Object>> zone =
        controller.reassignZone(principal, PID, new ZoneRequest(UUID.randomUUID(), null), request);
    assertThat(zone.data()).containsKey("new_zone_id");

    ApiResponse<Map<String, Object>> pauseResp =
        controller.pauseCatalogue(principal, PID, new CataloguePauseRequest(60, "audit"), request);
    assertThat(pauseResp.data().get("catalogue_paused")).isEqualTo(true);

    controller.toggleStorefront(principal, PID, null, request);
    controller.reassignZone(principal, PID, null, request);
    controller.pauseCatalogue(principal, PID, null, request);
  }

  @Test
  void pharmacyStorefrontController() {
    PharmacyStorefrontService service = mock(PharmacyStorefrontService.class);
    PharmacyStorefrontController controller = new PharmacyStorefrontController(service);
    when(service.ownerToggleStorefront(any(), any())).thenReturn(Map.of("is_online", true));

    ApiResponse<Map<String, Object>> resp =
        controller.toggleStorefront(
            mock(MedmatePrincipal.class), new PharmacyStorefrontController.StorefrontRequest(true));
    assertThat(resp.data().get("is_online")).isEqualTo(true);
    controller.toggleStorefront(mock(MedmatePrincipal.class), null);
  }

  @Test
  void adminStorefrontControllerClientIpNull() {
    PharmacyStorefrontService storefront = mock(PharmacyStorefrontService.class);
    CataloguePauseService pause = mock(CataloguePauseService.class);
    AdminPharmacyStorefrontController controller =
        new AdminPharmacyStorefrontController(storefront, pause);
    MedmatePrincipal principal = mock(MedmatePrincipal.class);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(storefront.adminToggleStorefront(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("is_online", true));

    when(request.getRemoteAddr()).thenReturn(null);
    controller.toggleStorefront(principal, PID, new StorefrontRequest(true, null), request);

    when(request.getRemoteAddr()).thenReturn("");
    controller.toggleStorefront(principal, PID, new StorefrontRequest(true, null), request);

    when(request.getRemoteAddr()).thenReturn(" 127.0.0.1 ");
    when(storefront.adminToggleStorefront(any(), any(), any(), any(), eq("127.0.0.1")))
        .thenReturn(Map.of("is_online", true));
    controller.toggleStorefront(principal, PID, new StorefrontRequest(true, null), request);
  }
}
