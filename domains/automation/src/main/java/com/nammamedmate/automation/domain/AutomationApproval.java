package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AutomationApproval(
    UUID id,
    UUID ruleId,
    String ruleName,
    UUID triggerEventId,
    String triggerEvent,
    String actionType,
    Map<String, Object> actionParams,
    String entityType,
    UUID entityId,
    String entityName,
    Long amountPaise,
    ApprovalCategory category,
    ApprovalUrgency urgency,
    String whyRequiresApproval,
    Map<String, Object> triggerContext,
    List<Map<String, Object>> conditionsMet,
    String estimatedImpact,
    String onRejectAction,
    ApprovalStatus status,
    UUID approvedBy,
    UUID rejectedBy,
    String approvalNotes,
    String rejectionReason,
    UUID activityLogId,
    Instant triggeredAt,
    Instant expiresAt,
    Instant resolvedAt) {

  public AutomationApproval {
    actionParams = actionParams == null ? Map.of() : Map.copyOf(actionParams);
    triggerContext = triggerContext == null ? Map.of() : Map.copyOf(triggerContext);
    conditionsMet = conditionsMet == null ? List.of() : List.copyOf(conditionsMet);
    category = category == null ? ApprovalCategory.ADMIN : category;
    urgency = urgency == null ? ApprovalUrgency.NORMAL : urgency;
    status = status == null ? ApprovalStatus.PENDING : status;
    entityType = entityType == null || entityType.isBlank() ? "UNKNOWN" : entityType;
    actionType = actionType == null ? "" : actionType;
  }
}
