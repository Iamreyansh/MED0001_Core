package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.prescription.adapter.in.web.EPrescriptionController.LinkBody;
import com.nammamedmate.prescription.application.EPrescriptionService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class EPrescriptionControllerTest {

  @Test
  void delegates() {
    EPrescriptionService service = mock(EPrescriptionService.class);
    EPrescriptionController controller = new EPrescriptionController(service);
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    UUID id = UUID.randomUUID();
    UUID cart = UUID.randomUUID();

    when(service.get(customer, id)).thenReturn(Map.of("prescription_id", id));
    assertThat(controller.get(customer, id).data().get("prescription_id")).isEqualTo(id);

    when(service.linkToCart(eq(customer), eq(id), eq(cart))).thenReturn(Map.of("cart_id", cart));
    assertThat(controller.linkToCart(customer, id, new LinkBody(cart)).data().get("cart_id"))
        .isEqualTo(cart);
    when(service.linkToCart(eq(customer), eq(id), isNull())).thenReturn(Map.of("ok", true));
    assertThat(controller.linkToCart(customer, id, null).success()).isTrue();

    when(service.downloadUrl(customer, id)).thenReturn("https://s3.example/x.pdf");
    ResponseEntity<Void> redirect = controller.download(customer, id);
    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(redirect.getHeaders().getLocation()).hasToString("https://s3.example/x.pdf");
  }
}
