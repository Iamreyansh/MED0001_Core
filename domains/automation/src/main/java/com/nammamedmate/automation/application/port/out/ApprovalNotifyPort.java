package com.nammamedmate.automation.application.port.out;

import java.util.UUID;

/** HIGH-priority push/outbox to eligible approvers (stub until EPIC-017 transport). */
public interface ApprovalNotifyPort {

  void approvalRequested(UUID approvalId, String actionType, String urgency, String deepLink);

  void approvalExpired(UUID approvalId, String actionType);
}
