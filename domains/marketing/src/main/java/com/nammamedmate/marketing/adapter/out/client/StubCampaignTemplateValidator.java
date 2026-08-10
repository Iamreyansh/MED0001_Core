package com.nammamedmate.marketing.adapter.out.client;

import com.nammamedmate.marketing.application.port.out.CampaignTemplatePort;
import com.nammamedmate.marketing.domain.CampaignChannel;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** ponytail: accepts any non-null template id until DLT/Meta registry is wired. */
@Component
public class StubCampaignTemplateValidator implements CampaignTemplatePort {

  @Override
  public boolean isApproved(CampaignChannel channel, UUID templateId) {
    return templateId != null;
  }
}
