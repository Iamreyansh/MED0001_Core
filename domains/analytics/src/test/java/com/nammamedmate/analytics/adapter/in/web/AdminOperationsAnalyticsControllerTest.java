package com.nammamedmate.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.OperationsAnalyticsService;
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

@ExtendWith(MockitoExtension.class)
class AdminOperationsAnalyticsControllerTest {

  @Mock private OperationsAnalyticsService ops;
  @InjectMocks private AdminOperationsAnalyticsController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    when(ops.operations(principal, "TODAY", null, null, null))
        .thenReturn(Map.of("period", "TODAY"));
    when(ops.fulfilmentFunnel(principal, "7D", null, null, "z"))
        .thenReturn(Map.of("funnel", java.util.List.of()));
    when(ops.deliveryBreakdown(principal, "7D", "a", "b"))
        .thenReturn(Map.of("platform_aggregate", Map.of()));
    when(ops.cancellations(principal, "30D", null, null, null))
        .thenReturn(Map.of("summary", Map.of()));

    assertThat(controller.operations(principal, "TODAY", null, null, null).data())
        .containsEntry("period", "TODAY");
    assertThat(controller.fulfilmentFunnel(principal, "7D", null, null, "z").data())
        .containsKey("funnel");
    assertThat(controller.deliveryBreakdown(principal, "7D", "a", "b").data())
        .containsKey("platform_aggregate");
    assertThat(controller.cancellations(principal, "30D", null, null, null).data())
        .containsKey("summary");

    verify(ops).operations(principal, "TODAY", null, null, null);
    verify(ops).fulfilmentFunnel(principal, "7D", null, null, "z");
    verify(ops).deliveryBreakdown(principal, "7D", "a", "b");
    verify(ops).cancellations(principal, "30D", null, null, null);
  }
}
