package com.nammamedmate.teleconsult.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.teleconsult.adapter.in.web.AdminTeleconsultDoctorController.AvailabilityRequest;
import com.nammamedmate.teleconsult.adapter.in.web.AdminTeleconsultDoctorController.CreateRequest;
import com.nammamedmate.teleconsult.application.TeleconsultDoctorService;
import com.nammamedmate.teleconsult.application.TeleconsultDoctorService.ListResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminTeleconsultDoctorControllerTest {

  @Test
  void delegates() {
    TeleconsultDoctorService service = mock(TeleconsultDoctorService.class);
    AdminTeleconsultDoctorController controller = new AdminTeleconsultDoctorController(service);
    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    UUID id = UUID.randomUUID();

    when(service.list(any(), any(), any(), any(), any()))
        .thenReturn(new ListResult(List.of(Map.of("id", id)), PaginationMeta.of(1, 20, 1)));
    assertThat(controller.list(admin, true, "GP", 1, 20).success()).isTrue();

    when(service.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("id", id, "is_available", false));
    assertThat(
            controller
                .create(
                    admin,
                    new CreateRequest(
                        "Dr X",
                        "MBBS",
                        "KA1",
                        "GP",
                        List.of("English"),
                        5,
                        "https://a",
                        "bio",
                        "+91"))
                .data()
                .get("id"))
        .isEqualTo(id);
    assertThat(controller.create(admin, null).data().get("id")).isEqualTo(id);

    when(service.update(eq(admin), eq(id), any())).thenReturn(Map.of("id", id));
    assertThat(controller.update(admin, id, Map.of("bio", "x")).data().get("id")).isEqualTo(id);

    when(service.setAvailability(eq(admin), eq(id), eq(true)))
        .thenReturn(Map.of("id", id, "is_available", true));
    assertThat(
            controller
                .availability(admin, id, new AvailabilityRequest(true))
                .data()
                .get("is_available"))
        .isEqualTo(true);
    when(service.setAvailability(eq(admin), eq(id), eq(null))).thenReturn(Map.of("id", id));
    assertThat(controller.availability(admin, id, null).data().get("id")).isEqualTo(id);

    when(service.stats(eq(admin), eq(id), eq("7d"))).thenReturn(Map.of("period", "7d"));
    assertThat(controller.stats(admin, id, "7d").data().get("period")).isEqualTo("7d");
  }
}
