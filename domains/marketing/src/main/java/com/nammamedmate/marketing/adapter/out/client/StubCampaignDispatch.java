package com.nammamedmate.marketing.adapter.out.client;

import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stub dispatch: treats all recipients as sent+delivered; pauses when cumulative spend hits budget
 * cap. ponytail: real FCM/SMS/WA/Email providers later.
 */
@Component
public class StubCampaignDispatch implements CampaignDispatchPort {

  @Override
  public DispatchResult dispatch(
      Campaign campaign, List<UUID> recipientIds, long costPerRecipientPaise) {
    List<UUID> ids = recipientIds == null ? List.of() : recipientIds;
    long budgetCap = campaign.budgetCapPaise() == null ? Long.MAX_VALUE : campaign.budgetCapPaise();
    long spend = campaign.actualSpendPaise();
    int sent = 0;
    int delivered = 0;
    long spendDelta = 0;
    boolean paused = false;
    List<UUID> deliveredIds = new ArrayList<>();
    for (UUID customerId : ids) {
      sent++;
      delivered++;
      spendDelta += costPerRecipientPaise;
      spend += costPerRecipientPaise;
      deliveredIds.add(customerId);
      if (campaign.budgetCapPaise() != null && spend >= budgetCap) {
        paused = true;
        break;
      }
    }
    return new DispatchResult(sent, delivered, spendDelta, paused, List.copyOf(deliveredIds));
  }

  @Override
  public String rateCardLabel(CampaignChannel channel) {
    return switch (channel) {
      case PUSH -> "Rs 0.01 per push (FCM)";
      case SMS -> "Rs 0.20 per SMS (DLT)";
      case EMAIL -> "Rs 0.05 per email";
      case WHATSAPP -> "Rs 0.85 per message (utility template)";
    };
  }
}
