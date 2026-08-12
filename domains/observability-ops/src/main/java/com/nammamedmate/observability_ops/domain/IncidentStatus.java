package com.nammamedmate.observability_ops.domain;

public enum IncidentStatus {
  DETECTED,
  INVESTIGATING,
  MITIGATING,
  RESOLVED;

  public boolean canTransitionTo(IncidentStatus next) {
    return next != null && next.ordinal() > this.ordinal() && next != RESOLVED;
  }
}
