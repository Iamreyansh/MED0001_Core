package com.nammamedmate.automation.application.port.out;

import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalStorePort {

  void insert(AutomationApproval approval);

  Optional<AutomationApproval> findById(UUID id);

  Optional<AutomationApproval> findPending(UUID ruleId, UUID entityId, String actionType);

  List<AutomationApproval> list(
      ApprovalStatus status, ApprovalUrgency urgency, int offset, int limit);

  long count(ApprovalStatus status, ApprovalUrgency urgency);

  long countPending();

  Chips chips(Instant now);

  ApprovalQueueStats stats(Instant now);

  int markResolved(
      UUID id,
      ApprovalStatus expected,
      ApprovalStatus next,
      UUID actorId,
      String notes,
      String reason,
      UUID activityLogId,
      Instant resolvedAt);

  List<AutomationApproval> listExpired(Instant now, int limit);

  record Chips(long pendingCount, long urgentCount, long approvedToday, long rejectedToday) {}

  record ApprovalQueueStats(
      double avgResponseTimeMinutes,
      double approvalRatePct,
      double rejectionRatePct,
      double expiryRatePct,
      List<Map<String, Object>> topPendingCategories) {

    public ApprovalQueueStats {
      topPendingCategories =
          topPendingCategories == null ? List.of() : List.copyOf(topPendingCategories);
    }
  }
}
