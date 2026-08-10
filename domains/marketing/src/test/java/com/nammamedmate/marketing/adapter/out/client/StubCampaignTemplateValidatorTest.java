package com.nammamedmate.marketing.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.marketing.domain.CampaignChannel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StubCampaignTemplateValidatorTest {

  @Test
  void acceptsNonNull() {
    StubCampaignTemplateValidator v = new StubCampaignTemplateValidator();
    assertThat(v.isApproved(CampaignChannel.SMS, UUID.randomUUID())).isTrue();
    assertThat(v.isApproved(CampaignChannel.WHATSAPP, null)).isFalse();
  }
}
