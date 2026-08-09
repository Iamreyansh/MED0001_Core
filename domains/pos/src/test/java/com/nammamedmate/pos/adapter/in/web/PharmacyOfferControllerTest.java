package com.nammamedmate.pos.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.pos.application.OfferService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PharmacyOfferControllerTest {

  @Mock OfferService offerService;
  PharmacyOfferController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new PharmacyOfferController(offerService);
  }

  @Test
  void delegatesAllEndpoints() {
    when(offerService.list(any(), any(), any(), any()))
        .thenReturn(
            new OfferService.ListResult(
                Map.of("offers", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    when(offerService.create(any(), any())).thenReturn(Map.of("offer_id", "x"));
    when(offerService.update(any(), any(), any())).thenReturn(Map.of("offer_id", "x"));
    when(offerService.toggle(any(), any())).thenReturn(Map.of("is_active", false));
    when(offerService.delete(any(), any())).thenReturn(Map.of("action", "HARD_DELETED"));
    when(offerService.validate(any(), any())).thenReturn(Map.of("is_valid", true));

    assertThat(controller.list(principal, "ACTIVE", 1, 20).get("success")).isEqualTo(true);
    ResponseEntity<?> created = controller.create(principal, Map.of("title", "t"));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = UUID.randomUUID();
    assertThat(controller.update(principal, id, Map.of()).data().get("offer_id")).isEqualTo("x");
    assertThat(controller.toggle(principal, id).data().get("is_active")).isEqualTo(false);
    assertThat(controller.delete(principal, id).data().get("action")).isEqualTo("HARD_DELETED");
    assertThat(controller.validate(principal, Map.of()).data().get("is_valid")).isEqualTo(true);
    verify(offerService).list(eq(principal), eq("ACTIVE"), eq(1), eq(20));
  }
}
