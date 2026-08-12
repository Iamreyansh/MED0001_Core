package com.nammamedmate.observability_ops.application.port.out;

import java.util.List;
import java.util.UUID;

/** Alert / incident paging via outbox (ids-only payloads). */
public interface NotificationDispatchPort {

  void pageCritical(UUID alertId, String alertType, List<UUID> adminIds);

  void pageIncident(UUID incidentId, String severity, List<UUID> adminIds);

  void remindPostmortem(UUID incidentId, List<UUID> adminIds);
}
