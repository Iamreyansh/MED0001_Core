package com.nammamedmate.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.GeographyAnalyticsService;
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
class AdminGeographyAnalyticsControllerTest {

  @Mock private GeographyAnalyticsService geography;
  @InjectMocks private AdminGeographyAnalyticsController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    when(geography.geography(principal, "7D", "gmv", "desc")).thenReturn(Map.of("period", "7D"));
    when(geography.supplyGap(principal, "7D", "CRITICAL")).thenReturn(Map.of("summary", Map.of()));
    when(geography.demandHeatmap(principal, null)).thenReturn(Map.of("computed_over_days", 28));

    assertThat(controller.geography(principal, "7D", "gmv", "desc").data())
        .containsEntry("period", "7D");
    assertThat(controller.supplyGap(principal, "7D", "CRITICAL").data()).containsKey("summary");
    assertThat(controller.demandHeatmap(principal, null).data())
        .containsEntry("computed_over_days", 28);

    verify(geography).geography(principal, "7D", "gmv", "desc");
    verify(geography).supplyGap(principal, "7D", "CRITICAL");
    verify(geography).demandHeatmap(principal, null);
  }
}
