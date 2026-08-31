package com.nammamedmate.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.adapter.in.web.dto.InvitePharmacyStaffRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SetPharmacyPosPinRequest;
import com.nammamedmate.auth.application.PharmacyStaffService;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyStaffControllerTest {

  private final PharmacyStaffService service = mock(PharmacyStaffService.class);
  private final PharmacyStaffController controller = new PharmacyStaffController(service);
  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, UUID.randomUUID(), TokenScope.FULL, "j");

  @Test
  void delegatesListInviteDeactivateAndPin() {
    UUID staffId = UUID.randomUUID();
    when(service.list(eq(owner), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new PharmacyStaffService.StaffListResult(
                List.of(Map.of("staff_id", staffId.toString())), PaginationMeta.of(1, 20, 1)));
    assertThat(controller.list(owner, null, null, null, null).data()).hasSize(1);

    when(service.invite(owner, "A", "a@x.com", null, "pharmacist"))
        .thenReturn(Map.of("staff_id", staffId.toString()));
    assertThat(
            controller
                .invite(owner, new InvitePharmacyStaffRequest("A", "a@x.com", null, "pharmacist"))
                .data())
        .containsEntry("staff_id", staffId.toString());
    when(service.invite(owner, null, null, null, null)).thenReturn(Map.of("ok", false));
    assertThat(controller.invite(owner, null).data()).containsEntry("ok", false);

    when(service.deactivate(owner, staffId)).thenReturn(Map.of("status", "SUSPENDED"));
    assertThat(controller.deactivate(owner, staffId).data()).containsEntry("status", "SUSPENDED");

    when(service.setPosPin(owner, staffId, "1234")).thenReturn(Map.of("pos_pin_set", true));
    assertThat(controller.setPosPin(owner, staffId, new SetPharmacyPosPinRequest("1234")).data())
        .containsEntry("pos_pin_set", true);
    when(service.setPosPin(owner, staffId, null)).thenReturn(Map.of("pos_pin_set", false));
    assertThat(controller.setPosPin(owner, staffId, null).data())
        .containsEntry("pos_pin_set", false);

    when(service.issuePasswordReset(owner, staffId)).thenReturn(Map.of("reset_token", "tok"));
    assertThat(controller.resetPassword(owner, staffId).data()).containsEntry("reset_token", "tok");
  }
}
