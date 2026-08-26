package com.nammamedmate.marketing.adapter.out.messaging;

import com.nammamedmate.marketing.application.port.out.CampaignDispatchPort;
import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Campaign send via transactional outbox; worker/notification delivers. */
@Component
@Primary
public class OutboxCampaignDispatch implements CampaignDispatchPort {

  private final ObjectProvider<OutboxPublisher> outbox;

  public OutboxCampaignDispatch(ObjectProvider<OutboxPublisher> outbox) {
    this.outbox = outbox;
  }

  @Override
  public DispatchResult dispatch(
      Campaign campaign, List<UUID> recipientIds, long costPerRecipientPaise) {
    List<UUID> ids = recipientIds == null ? List.of() : recipientIds;
    Long cap = campaign.budgetCapPaise();
    long spend = campaign.actualSpendPaise();
    boolean paused = cap != null && spend >= cap;
    if (paused || ids.isEmpty()) {
      return new DispatchResult(0, 0, 0L, paused, List.of());
    }
    OutboxPublisher publisher = outbox == null ? null : outbox.getIfAvailable();
    int sent = 0;
    long spendDelta = 0;
    List<UUID> deliveredIds = new ArrayList<>();
    for (UUID customerId : ids) {
      if (publisher != null) {
        publisher.publish(requestedEvent(campaign, customerId));
      }
      sent++;
      spendDelta += costPerRecipientPaise;
      spend += costPerRecipientPaise;
      deliveredIds.add(customerId);
      if (cap != null && spend >= cap) {
        paused = true;
        break;
      }
    }
    return new DispatchResult(sent, sent, spendDelta, paused, List.copyOf(deliveredIds));
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

  private static DomainEvent requestedEvent(Campaign campaign, UUID customerId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("campaign_id", campaign.id().toString());
    payload.put("customer_id", customerId.toString());
    payload.put("channel", campaign.channel().name());
    return DomainEvent.of(
        "marketing.campaign.dispatch.requested", "campaign", campaign.id(), payload);
  }
}
