package com.nammamedmate.support.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface NotificationDispatchPort {

  void notifyEscalation(UUID ticketId, UUID customerId, String slaLevel);

  void notifyCsatSurvey(UUID ticketId, UUID customerId, String channel);

  void notifySupervisorEscalation(UUID ticketId, String reason);

  /** Escalation / L4 senior-ops notify with matrix channels. */
  default void notifyEscalationChannels(
      UUID ticketId, UUID customerId, String slaLevel, String team, List<String> channels) {
    notifyEscalation(ticketId, customerId, slaLevel);
  }

  /** Weekly agent performance email to ops (Mon 08:00 IST). */
  default void notifyWeeklyAgentPerformance(LocalDate weekStart, int agentCount) {}
}
