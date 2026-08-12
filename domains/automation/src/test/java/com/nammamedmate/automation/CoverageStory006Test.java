package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.adapter.out.persistence.StubApprovalNotifyAdapter;
import com.nammamedmate.automation.application.ActivityLogService;
import com.nammamedmate.automation.application.ApprovalExpiryScheduler;
import com.nammamedmate.automation.application.ApprovalQueueService;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.domain.ActivityStats;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CoverageStory006Test {

  @Test
  void notifySchedulerAndActivityPendingFromQueue() {
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    OutboxPublisher publisher = mock(OutboxPublisher.class);
    when(provider.getIfAvailable()).thenReturn(publisher);
    UUID id = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    StubApprovalNotifyAdapter stub = new StubApprovalNotifyAdapter(provider);
    stub.approvalRequested(id, "release_payout", "URGENT", "/admin/automation/approvals/" + id);
    stub.approvalExpired(id, "release_payout");
    verify(publisher, org.mockito.Mockito.times(2)).publish(any());
    new StubApprovalNotifyAdapter(provider).approvalRequested(null, "x", "NORMAL", "/x");
    new StubApprovalNotifyAdapter(mock(ObjectProvider.class)).approvalExpired(id, "x");

    ApprovalQueueService queue = mock(ApprovalQueueService.class);
    when(queue.expireDue(100)).thenReturn(2);
    new ApprovalExpiryScheduler(queue).expireDue();
    verify(queue).expireDue(100);

    Instant now = Instant.parse("2026-07-24T09:45:00Z");
    ActivityLogPort activity = mock(ActivityLogPort.class);
    RuleStorePort rules = mock(RuleStorePort.class);
    ActionExecutorPort actions = mock(ActionExecutorPort.class);
    ApprovalStorePort approvals = mock(ApprovalStorePort.class);
    when(activity.stats(now)).thenReturn(new ActivityStats(1, 2, 3, 0, 99, now));
    when(approvals.countPending()).thenReturn(7L);
    when(rules.countByStatus(RuleStatus.ACTIVE)).thenReturn(1L);
    when(rules.countByStatus(RuleStatus.SIMULATING)).thenReturn(0L);
    when(rules.countByStatus(RuleStatus.INACTIVE)).thenReturn(0L);
    ActivityLogService svc =
        new ActivityLogService(
            activity, rules, actions, approvals, Clock.fixed(now, ZoneOffset.UTC));
    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThat(svc.stats(admin).get("pending_approvals_count")).isEqualTo(7L);
  }
}
