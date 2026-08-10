package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.marketing.domain.CampaignChannel;
import java.util.UUID;

/** Validates DLT / Meta / push templates for campaign create. */
public interface CampaignTemplatePort {

  /**
   * @return true when template exists and is approved for the channel
   */
  boolean isApproved(CampaignChannel channel, UUID templateId);
}
