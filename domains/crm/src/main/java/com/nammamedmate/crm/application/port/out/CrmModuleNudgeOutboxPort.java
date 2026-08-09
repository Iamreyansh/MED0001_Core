package com.nammamedmate.crm.application.port.out;

import java.util.Map;
import java.util.UUID;

/** Outbox publisher for module usage nudge campaigns (ids-only payloads). */
public interface CrmModuleNudgeOutboxPort {

  void publish(String type, UUID aggregateId, Map<String, Object> payload);
}
