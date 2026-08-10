package com.nammamedmate.teleconsult.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.adapter.in.web.CustomerConsultController.CancelBody;
import com.nammamedmate.teleconsult.adapter.in.web.CustomerConsultController.RequestBodyDto;
import com.nammamedmate.teleconsult.application.ConsultService;
import com.nammamedmate.teleconsult.application.ConsultService.ListResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerConsultControllerTest {

  @Test
  void delegatesIncludingNullBodies() {
    ConsultService service = mock(ConsultService.class);
    CustomerConsultController controller = new CustomerConsultController(service);
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    UUID id = UUID.randomUUID();

    when(service.request(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("consult_id", id, "status", "REQUESTED"));
    assertThat(
            controller
                .request(
                    customer,
                    new RequestBodyDto(
                        "Ravi", "+91-9", "NOW", List.of("fatigue"), null, null, "GENERAL"))
                .data()
                .get("consult_id"))
        .isEqualTo(id);
    assertThat(
            controller
                .request(
                    customer,
                    new RequestBodyDto(
                        "Ravi",
                        "+91-9",
                        "NOW",
                        null,
                        List.of(Map.of("name", "M", "reason", "REFILL")),
                        null,
                        "GENERAL"))
                .data()
                .get("consult_id"))
        .isEqualTo(id);
    assertThat(controller.request(customer, null).data().get("consult_id")).isEqualTo(id);

    when(service.get(eq(customer), eq(id))).thenReturn(Map.of("consult_id", id));
    assertThat(controller.get(customer, id).data().get("consult_id")).isEqualTo(id);

    when(service.cancel(eq(customer), eq(id), eq("gone")))
        .thenReturn(Map.of("consult_id", id, "status", "CANCELLED"));
    assertThat(controller.cancel(customer, id, new CancelBody("gone")).data().get("status"))
        .isEqualTo("CANCELLED");
    when(service.cancel(eq(customer), eq(id), isNull()))
        .thenReturn(Map.of("consult_id", id, "status", "CANCELLED"));
    assertThat(controller.cancel(customer, id, null).data().get("status")).isEqualTo("CANCELLED");

    when(service.list(eq(customer), eq("ALL"), eq(1), eq(20)))
        .thenReturn(new ListResult(List.of(Map.of("consult_id", id)), PaginationMeta.of(1, 20, 1)));
    assertThat(controller.list(customer, "ALL", 1, 20).success()).isTrue();

    when(service.rate(eq(customer), eq(id), eq(5), eq("Great"))).thenReturn(Map.of("rating", 5));
    assertThat(
            controller
                .rate(customer, id, new CustomerConsultController.RateBody(5, "Great"))
                .data()
                .get("rating"))
        .isEqualTo(5);
    when(service.rate(eq(customer), eq(id), isNull(), isNull())).thenReturn(Map.of("rating", 1));
    assertThat(controller.rate(customer, id, null).data().get("rating")).isEqualTo(1);
  }
}
