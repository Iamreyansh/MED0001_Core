package com.nammamedmate.analytics.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.PlatformOverviewService;
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
class AdminPlatformOverviewControllerTest {

  @Mock private PlatformOverviewService overview;
  @InjectMocks private AdminPlatformOverviewController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesAllEndpoints() {
    when(overview.overview(principal, "7D", null, null)).thenReturn(Map.of("period", "7D"));
    when(overview.charts(principal, "7D", "a", "b"))
        .thenReturn(Map.of("gmv_trend", java.util.List.of()));
    when(overview.leaderboards(principal, "30D", 10, "csv"))
        .thenReturn(Map.of("export_url", "https://example/x"));

    assertThat(controller.overview(principal, "7D", null, null).data())
        .containsEntry("period", "7D");
    assertThat(controller.charts(principal, "7D", "a", "b").data()).containsKey("gmv_trend");
    assertThat(controller.leaderboards(principal, "30D", 10, "csv").data())
        .containsEntry("export_url", "https://example/x");

    verify(overview).overview(principal, "7D", null, null);
    verify(overview).charts(principal, "7D", "a", "b");
    verify(overview).leaderboards(principal, "30D", 10, "csv");
  }
}
