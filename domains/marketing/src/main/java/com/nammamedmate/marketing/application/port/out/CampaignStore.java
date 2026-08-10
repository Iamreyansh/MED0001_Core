package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.marketing.domain.Campaign;
import com.nammamedmate.marketing.domain.CampaignChannel;
import com.nammamedmate.marketing.domain.CampaignStatus;
import com.nammamedmate.marketing.domain.CampaignTimelineEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CampaignStore {

  record Interaction(UUID campaignId, Instant interactedAt) {}

  Campaign insert(Campaign campaign);

  Optional<Campaign> findById(UUID id);

  Campaign update(Campaign campaign);

  List<Campaign> list(
      CampaignStatus status,
      CampaignChannel channel,
      String sort,
      String order,
      int offset,
      int limit);

  long count(CampaignStatus status, CampaignChannel channel);

  void appendTimeline(CampaignTimelineEvent event);

  List<CampaignTimelineEvent> timeline(UUID campaignId);

  void insertInteraction(
      UUID id, UUID campaignId, UUID customerId, Instant interactedAt, String interaction);

  /** Most recent interaction for customer (any campaign), or empty. */
  Optional<Interaction> findLatestInteraction(UUID customerId);

  boolean isSegmentReferencedByActiveCampaign(UUID segmentId);

  List<UUID> listSegmentMemberIds(UUID segmentId);

  int countSegmentMembers(UUID segmentId);
}
