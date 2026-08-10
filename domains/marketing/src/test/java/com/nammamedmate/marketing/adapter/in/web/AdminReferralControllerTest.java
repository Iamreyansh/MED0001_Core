package com.nammamedmate.marketing.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.marketing.adapter.in.web.AdminReferralController.PatchProgramRequest;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort.OverviewResult;
import com.nammamedmate.marketing.application.port.out.ReferralAdminPort.PatchProgramCommand;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminReferralControllerTest {

  @Mock private ReferralAdminPort port;
  private AdminReferralController controller;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminReferralController(port);
  }

  @Test
  void overview_delegates() {
    when(port.overview(eq(admin), eq("PENDING"), eq(1), eq(20)))
        .thenReturn(
            new OverviewResult(
                Map.of("referrals", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(controller.overview(admin, "PENDING", 1, 20).data()).containsKey("referrals");
  }

  @Test
  void getAndPatchProgram() {
    when(port.getProgram(admin)).thenReturn(Map.of("is_active", true));
    assertThat(controller.getProgram(admin).data()).containsEntry("is_active", true);

    when(port.patchProgram(eq(admin), any(PatchProgramCommand.class)))
        .thenReturn(Map.of("updated_by", admin.subject()));
    assertThat(
            controller
                .patchProgram(admin, new PatchProgramRequest(150, 100, true, 365, "x"))
                .data())
        .containsKey("updated_by");

    ArgumentCaptor<PatchProgramCommand> captor = ArgumentCaptor.forClass(PatchProgramCommand.class);
    org.mockito.Mockito.verify(port).patchProgram(eq(admin), captor.capture());
    assertThat(captor.getValue().rewardForReferrerRs()).isEqualTo(150);

    when(port.patchProgram(eq(admin), any(PatchProgramCommand.class)))
        .thenReturn(Map.of("updated_at", "t"));
    controller.patchProgram(admin, null);
    org.mockito.Mockito.verify(port, org.mockito.Mockito.times(2))
        .patchProgram(eq(admin), any(PatchProgramCommand.class));
  }
}
