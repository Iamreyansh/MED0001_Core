package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.Map;

/**
 * Admin referral façade into customer ReferralService (bridged in apps/api). No domain→domain
 * compile dependency.
 */
public interface ReferralAdminPort {

  OverviewResult overview(MedmatePrincipal principal, String status, Integer page, Integer limit);

  Map<String, Object> getProgram(MedmatePrincipal principal);

  Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd);

  record OverviewResult(Map<String, Object> data, PaginationMeta meta) {}

  record PatchProgramCommand(
      Number rewardForReferrerRs,
      Number rewardForRefereeRs,
      Boolean isActive,
      Integer rewardExpiryDays,
      String conditions) {}
}
