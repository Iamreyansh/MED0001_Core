package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.prescription.application.PrescriptionService;
import com.nammamedmate.prescription.application.PrescriptionService.ListResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

class CustomerPrescriptionControllerTest {

  private final PrescriptionService service = mock(PrescriptionService.class);
  private final CustomerPrescriptionController controller =
      new CustomerPrescriptionController(service);
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @Test
  void upload_returns201() throws Exception {
    when(service.upload(eq(customer), any(), any(), any(), any()))
        .thenReturn(Map.of("id", UUID.randomUUID()));
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "a.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    ResponseEntity<ApiResponse<Map<String, Object>>> resp =
        controller.upload(customer, file, "Ravi", "notes");
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(resp.getBody().success()).isTrue();
  }

  @Test
  void list_get_delete_useInCart() {
    when(service.list(eq(customer), any(), any(), any(), any(), any(), any()))
        .thenReturn(new ListResult(List.of(Map.of("id", "x")), PaginationMeta.of(1, 20, 1)));
    ApiResponse<List<Map<String, Object>>> list =
        controller.list(customer, null, null, 1, 20, null, null);
    assertThat(list.data()).hasSize(1);
    assertThat(list.meta().total()).isEqualTo(1);

    UUID id = UUID.randomUUID();
    when(service.get(customer, id)).thenReturn(Map.of("id", id));
    assertThat(controller.get(customer, id).data().get("id")).isEqualTo(id);

    when(service.delete(customer, id)).thenReturn(Map.of("message", "ok"));
    assertThat(controller.delete(customer, id).data().get("message")).isEqualTo("ok");

    UUID cart = UUID.randomUUID();
    when(service.useInCart(customer, id, cart)).thenReturn(Map.of("cart_id", cart));
    assertThat(
            controller
                .useInCart(customer, id, new CustomerPrescriptionController.UseInCartRequest(cart))
                .data()
                .get("cart_id"))
        .isEqualTo(cart);

    controller.useInCart(customer, id, null);
    verify(service).useInCart(customer, id, null);
  }
}
