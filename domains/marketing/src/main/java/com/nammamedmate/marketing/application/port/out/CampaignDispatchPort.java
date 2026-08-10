package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import java.util.List;
import java.util.UUID;

/** Async campaign message dispatch (stub increments counters until provider wiring). */
public interface CampaignDispatchPort {

  /**
   * Dispatch to audience snapshot. Implementations may update sent/delivered/spend and pause on
   * budget.
   */
  DispatchResult dispatch(Campaign campaign, List<UUID> recipientIds, long costPerRecipientPaise);

  record DispatchResult(
      int sentDelta,
      int deliveredDelta,
      long spendDeltaPaise,
      boolean budgetPaused,
      List<UUID> deliveredCustomerIds) {}

  /** Human-readable rate card line for a channel. */
  String rateCardLabel(CampaignChannel channel);
}
