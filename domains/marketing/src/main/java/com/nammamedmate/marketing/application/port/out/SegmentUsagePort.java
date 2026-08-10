package com.nammamedmate.marketing.application.port.out;

import java.util.UUID;

/** Whether a segment is referenced by an active coupon or campaign (STORY-001/003). */
public interface SegmentUsagePort {

  boolean isReferencedByActiveCouponOrCampaign(UUID segmentId);
}
