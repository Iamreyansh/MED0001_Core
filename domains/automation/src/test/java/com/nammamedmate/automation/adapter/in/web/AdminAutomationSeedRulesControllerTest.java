package com.nammamedmate.automation.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.SeedAutomationsService;
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
class AdminAutomationSeedRulesControllerTest {

  @Mock SeedAutomationsService seeds;
  @InjectMocks AdminAutomationSeedRulesController controller;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void delegatesListAndInitialize() {
    when(seeds.list(any())).thenReturn(Map.of("seed_rules", java.util.List.of()));
    when(seeds.initialize(any())).thenReturn(Map.of("created", 6));

    assertThat(controller.list(principal).success()).isTrue();
    assertThat(controller.initialize(principal, null).data()).containsEntry("created", 6);
    assertThat(controller.initialize(principal, Map.of()).data()).containsEntry("created", 6);
    assertThat(controller.initialize(principal, Map.of("ignored", true)).data())
        .containsEntry("created", 6);
    verify(seeds).list(principal);
  }
}
