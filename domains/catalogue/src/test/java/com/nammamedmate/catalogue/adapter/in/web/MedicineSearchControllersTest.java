package com.nammamedmate.catalogue.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.MedicineSearchService;
import com.nammamedmate.catalogue.application.MedicineSearchService.Envelope;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MedicineSearchControllersTest {

  @Mock private MedicineSearchService service;
  private MedicineSearchController controller;
  private PharmacyCatalogueSearchController pharmacyController;
  private final MedmatePrincipal principal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new MedicineSearchController(service);
    pharmacyController = new PharmacyCatalogueSearchController(service);
  }

  @Test
  void searchDetailSubstitutesAvailability() {
    when(service.search(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            any()))
        .thenReturn(new Envelope(Map.of("query", "a"), Map.of("cached", false)));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRemoteAddr()).thenReturn("10.0.0.1");

    Map<String, Object> search =
        controller.search(
            null, "aug", null, null, null, 1.0, 2.0, null, null, null, false, false, false, 1, 20,
            req);
    assertThat(search).containsEntry("success", true);

    UUID id = UUID.randomUUID();
    when(service.getDetail(any(), any(), any(), any(), eq(id), anyBoolean(), eq("10.0.0.1")))
        .thenReturn(new Envelope(Map.of("medicine_id", id.toString()), Map.of()));
    assertThat(controller.detail(id, null, null, null, null, false, req).get("data"))
        .isInstanceOf(Map.class);

    when(service.substitutes(eq(id), eq("10.0.0.1")))
        .thenReturn(new Envelope(Map.of("substitutes", List.of()), null));
    assertThat(controller.substitutes(id, req)).containsEntry("success", true);

    when(service.checkAvailability(isNull(), any(), eq(id), isNull()))
        .thenReturn(new Envelope(Map.of("ok", true), Map.of()));
    assertThat(
            controller
                .checkAvailability(
                    null,
                    new MedicineSearchController.CheckAvailabilityRequest(List.of(id), id),
                    null)
                .get("data"))
        .isEqualTo(Map.of("ok", true));

    when(service.checkAvailability(isNull(), isNull(), isNull(), isNull()))
        .thenReturn(new Envelope(Map.of(), Map.of()));
    controller.checkAvailability(null, null, null);
    verify(service).checkAvailability(isNull(), isNull(), isNull(), isNull());
  }

  @Test
  void clientIpBranchesAndPharmacySearch() {
    HttpServletRequest blank = mock(HttpServletRequest.class);
    when(blank.getRemoteAddr()).thenReturn("  ");
    when(service.search(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            isNull()))
        .thenReturn(new Envelope(Map.of(), Map.of()));
    controller.search(
        null, "ab", null, null, null, null, null, null, null, null, true, false, false, null, null,
        blank);

    HttpServletRequest nullAddr = mock(HttpServletRequest.class);
    when(nullAddr.getRemoteAddr()).thenReturn(null);
    controller.search(
        null, "ab", null, null, null, null, null, null, null, null, true, false, false, null, null,
        nullAddr);

    when(service.pharmacySearch(eq(principal), eq("crocin"), eq("ALL"), eq(false), eq(1), eq(20)))
        .thenReturn(new Envelope(Map.of("results", List.of()), null));
    assertThat(pharmacyController.search(principal, "crocin", "ALL", false, false, 1, 20))
        .containsEntry("success", true)
        .containsEntry("meta", Map.of());

    when(service.pharmacySearch(eq(principal), eq("crocin"), eq("ALL"), eq(false), eq(1), eq(20)))
        .thenReturn(new Envelope(Map.of("results", List.of()), Map.of("page", 1)));
    assertThat(
            pharmacyController.search(principal, "crocin", "ALL", false, false, 1, 20).get("meta"))
        .isEqualTo(Map.of("page", 1));

    when(service.pharmacySearch(eq(principal), eq("dolo"), eq("ALL"), eq(false), eq(1), eq(20)))
        .thenReturn(new Envelope(Map.of("results", List.of()), Map.of("page", 1)));
    pharmacyController.search(principal, "dolo", "ALL", true, true, 1, 20);
    verify(service).pharmacySearch(principal, "dolo", "ALL", false, 1, 20);
  }
}
