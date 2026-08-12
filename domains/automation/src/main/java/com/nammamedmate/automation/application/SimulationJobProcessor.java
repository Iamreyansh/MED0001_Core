package com.nammamedmate.automation.application;

import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SimulationNotifyPort;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.domain.AutomationRule;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Async batch simulation + SIMULATING 24h revert + 7d result expiry.
 *
 * <p>ponytail: in-process poller (same pattern as workflow waits / marketing compute). Upgrade to
 * apps/worker SQS when volume warrants it.
 */
@Component
@ConditionalOnProperty(
    name = "medmate.automation.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SimulationJobProcessor {

  private final SimulationStorePort simulations;
  private final RuleSimulationService simulationService;
  private final RuleStorePort rules;
  private final RuleManagementService ruleManagement;
  private final SimulationNotifyPort notify;
  private final Clock clock;

  public SimulationJobProcessor(
      SimulationStorePort simulations,
      RuleSimulationService simulationService,
      RuleStorePort rules,
      RuleManagementService ruleManagement,
      SimulationNotifyPort notify,
      Clock clock) {
    this.simulations = simulations;
    this.simulationService = simulationService;
    this.rules = rules;
    this.ruleManagement = ruleManagement;
    this.notify = notify;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${medmate.automation.simulation-poll-delay-ms:2000}")
  public void pollRunningSimulations() {
    for (UUID id : simulations.listRunning(10)) {
      simulationService.processSimulation(id);
    }
  }

  @Scheduled(fixedDelayString = "${medmate.automation.simulating-revert-delay-ms:60000}")
  public void revertExpiredSimulating() {
    Instant cutoff = clock.instant().minus(RuleSimulationService.SIMULATING_CAP);
    for (UUID ruleId : rules.listSimulatingStartedBefore(cutoff, 50)) {
      AutomationRule rule = rules.findById(ruleId).orElse(null);
      ruleManagement.autoRevertSimulating(ruleId);
      if (rule != null) {
        notify.simulatingAutoReverted(ruleId, rule.createdBy(), rule.name());
      }
    }
  }

  @Scheduled(fixedDelayString = "${medmate.automation.simulation-expiry-delay-ms:3600000}")
  public void expireOldResults() {
    simulations.deleteExpired(clock.instant());
  }
}
