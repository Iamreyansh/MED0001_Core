package com.nammamedmate.support.application.port.out;

import java.util.List;
import java.util.UUID;

/** Automation engine stub — in-process SLA bump + outbox notification. */
public interface AutomationEscalatePort {

  /** Bumps ticket SLA level and notifies; does not go through TicketService.escalate. */
  void escalateOnSlaBreach(UUID ticketId, String fromLevel, String toLevel);

  default void notifyL4SeniorOps(UUID ticketId, String team, List<String> channels) {}
}
