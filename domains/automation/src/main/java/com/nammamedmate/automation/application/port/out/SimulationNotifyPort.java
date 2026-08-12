package com.nammamedmate.automation.application.port.out;

import java.util.UUID;

/** Notify admin when SIMULATING auto-reverts after 24h (stub/outbox until STORY-005). */
public interface SimulationNotifyPort {

  void simulatingAutoReverted(UUID ruleId, UUID adminUserId, String ruleName);
}
