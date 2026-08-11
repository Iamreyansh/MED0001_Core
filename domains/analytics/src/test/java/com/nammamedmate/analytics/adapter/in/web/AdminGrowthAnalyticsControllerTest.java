package com.nammamedmate.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.GrowthAnalyticsService;
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
class AdminGrowthAnalyticsControllerTest {

  @Mock private GrowthAnalyticsService growth;
  @InjectMocks private AdminGrowthAnalyticsController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    when(growth.growth(principal, "30D", null, null)).thenReturn(Map.of("period", "30D"));
    when(growth.cohort(principal, 12)).thenReturn(Map.of("cohorts", java.util.List.of()));
    when(growth.acquisition(principal, "7D", "a", "b"))
        .thenReturn(Map.of("sources", java.util.List.of()));
    when(growth.orderTrend(principal, "30D", "WEEKLY")).thenReturn(Map.of("granularity", "WEEKLY"));

    assertThat(controller.growth(principal, "30D", null, null).data())
        .containsEntry("period", "30D");
    assertThat(controller.cohort(principal, 12).data()).containsKey("cohorts");
    assertThat(controller.acquisition(principal, "7D", "a", "b").data()).containsKey("sources");
    assertThat(controller.orderTrend(principal, "30D", "WEEKLY").data())
        .containsEntry("granularity", "WEEKLY");

    verify(growth).growth(principal, "30D", null, null);
    verify(growth).cohort(principal, 12);
    verify(growth).acquisition(principal, "7D", "a", "b");
    verify(growth).orderTrend(principal, "30D", "WEEKLY");
  }
}
