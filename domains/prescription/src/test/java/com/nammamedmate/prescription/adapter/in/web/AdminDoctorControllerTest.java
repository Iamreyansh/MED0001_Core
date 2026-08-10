package com.nammamedmate.prescription.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.prescription.adapter.in.web.AdminDoctorController.BlacklistRequest;
import com.nammamedmate.prescription.adapter.in.web.AdminDoctorController.VerifyRequest;
import com.nammamedmate.prescription.application.DoctorRegistryService;
import com.nammamedmate.prescription.application.DoctorRegistryService.ListResult;
import com.nammamedmate.prescription.application.DoctorRegistryService.UnverifiedResult;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminDoctorControllerTest {

  @Test
  void delegates() {
    DoctorRegistryService service = mock(DoctorRegistryService.class);
    AdminDoctorController controller = new AdminDoctorController(service);
    MedmatePrincipal admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    UUID id = UUID.randomUUID();

    when(service.list(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new ListResult(List.of(Map.of("id", id)), PaginationMeta.of(1, 20, 1)));
    assertThat(controller.list(admin, null, null, null, 1, 20, null, null).success()).isTrue();

    when(service.listUnverified(any(), any(), any(), any()))
        .thenReturn(
            new UnverifiedResult(
                Map.of("total_unverified", 1, "doctors", List.of()), PaginationMeta.of(1, 20, 1)));
    assertThat(controller.unverified(admin, 1, 20, null).data().get("total_unverified"))
        .isEqualTo(1);

    when(service.get(admin, id)).thenReturn(Map.of("id", id));
    assertThat(controller.get(admin, id).data().get("id")).isEqualTo(id);

    when(service.verify(eq(admin), eq(id), any(), any(), any()))
        .thenReturn(Map.of("status", "VERIFIED"));
    assertThat(
            controller
                .verify(admin, id, new VerifyRequest(true, "MANUAL", "ok"))
                .data()
                .get("status"))
        .isEqualTo("VERIFIED");
    assertThat(controller.verify(admin, id, null).data().get("status")).isEqualTo("VERIFIED");

    when(service.blacklist(eq(admin), eq(id), any()))
        .thenReturn(Map.of("status", "BLACKLISTED", "retroactive_flags_queued", 0));
    assertThat(controller.blacklist(admin, id, new BlacklistRequest("fraud")).data().get("status"))
        .isEqualTo("BLACKLISTED");
    assertThat(controller.blacklist(admin, id, null).data().get("status")).isEqualTo("BLACKLISTED");
  }
}
