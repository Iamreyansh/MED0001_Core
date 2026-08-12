package com.nammamedmate.automation.application.port.out;

import java.util.Map;
import java.util.UUID;

public interface ActionExecutorPort {

  /**
   * Dispatch one action. Returns activity log id on success. Throws on failure (engine continues).
   */
  UUID execute(String actionId, Map<String, Object> params, Map<String, Object> context);
}
