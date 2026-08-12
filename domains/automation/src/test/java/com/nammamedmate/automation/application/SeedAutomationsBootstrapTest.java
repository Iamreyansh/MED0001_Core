package com.nammamedmate.automation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;

class SeedAutomationsBootstrapTest {

  @Test
  void logsInitializeResult() throws Exception {
    SeedAutomationsService seeds = mock(SeedAutomationsService.class);
    when(seeds.initialize(null))
        .thenReturn(
            Map.of(
                "created",
                6,
                "already_existed",
                0,
                "workflows_created",
                3,
                "workflows_already_existed",
                0));
    ApplicationRunner runner = new SeedAutomationsBootstrap(seeds);
    runner.run(new DefaultApplicationArguments());
    verify(seeds).initialize(null);
  }

  @Test
  void swallowsFailures() throws Exception {
    SeedAutomationsService seeds = mock(SeedAutomationsService.class);
    when(seeds.initialize(any())).thenThrow(new IllegalStateException("db down"));
    new SeedAutomationsBootstrap(seeds).run(mock(ApplicationArguments.class));
    verify(seeds).initialize(null);
  }
}
