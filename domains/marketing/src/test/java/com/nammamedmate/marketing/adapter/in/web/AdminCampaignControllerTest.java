package com.nammamedmate.marketing.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.marketing.application.CampaignService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminCampaignControllerTest {

  @Mock CampaignService campaigns;
  @InjectMocks AdminCampaignController controller;

  private final MedmatePrincipal admin =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final UUID id = UUID.fromString("c0130003-0000-4000-8000-000000000001");

  @Test
  void coversAllEndpoints() {
    when(campaigns.list(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new CampaignService.PagedResult(
                Map.of("campaigns", java.util.List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(controller.list(admin, null, null, null, null, null, null).success()).isTrue();

    when(campaigns.costEstimate(any(), eq("WHATSAPP"), eq(id), isNull()))
        .thenReturn(Map.of("estimated_recipients", 1));
    assertThat(
            controller.costEstimate(admin, "WHATSAPP", id, null).data().get("estimated_recipients"))
        .isEqualTo(1);

    when(campaigns.create(any(), any())).thenReturn(Map.of("id", id.toString(), "status", "DRAFT"));
    assertThat(controller.create(admin, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            controller
                .create(
                    admin,
                    new AdminCampaignController.CreateCampaignRequest(
                        "n", "PUSH", id, null, "s", "b", null, null, null, null, null))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    when(campaigns.get(any(), eq(id))).thenReturn(Map.of("id", id.toString()));
    assertThat(controller.get(admin, id).data().get("id")).isEqualTo(id.toString());

    when(campaigns.patch(any(), eq(id), any())).thenReturn(Map.of("id", id.toString()));
    assertThat(controller.patch(admin, id, null).success()).isTrue();
    assertThat(
            controller
                .patch(
                    admin,
                    id,
                    new AdminCampaignController.PatchCampaignRequest(
                        "n", null, null, null, null, null, null, null, null, null, null))
                .success())
        .isTrue();

    when(campaigns.launch(any(), eq(id))).thenReturn(Map.of("status", "RUNNING"));
    assertThat(controller.launch(admin, id).data().get("status")).isEqualTo("RUNNING");

    when(campaigns.pause(any(), eq(id))).thenReturn(Map.of("status", "PAUSED"));
    assertThat(controller.pause(admin, id).data().get("status")).isEqualTo("PAUSED");

    when(campaigns.resume(any(), eq(id))).thenReturn(Map.of("status", "RUNNING"));
    assertThat(controller.resume(admin, id).data().get("status")).isEqualTo("RUNNING");
  }
}
