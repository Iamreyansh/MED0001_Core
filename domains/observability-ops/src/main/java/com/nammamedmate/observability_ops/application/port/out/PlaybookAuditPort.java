package com.nammamedmate.observability_ops.application.port.out;

import java.util.Map;
import java.util.UUID;

public interface PlaybookAuditPort {

  void record(
      UUID playbookId, UUID updatedBy, Map<String, Object> before, Map<String, Object> after);
}
