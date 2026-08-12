package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.CircuitBreakerState;
import java.util.List;

public interface CircuitBreakerPort {

  /**
   * Record an attempted fire and return whether the action may execute. Returns false when the
   * circuit is OPEN (or just tripped).
   */
  boolean tryAcquire(String actionType);

  List<CircuitBreakerState> list();
}
