package com.nammamedmate.marketing.application.port.out;

import com.nammamedmate.security.MedmatePrincipal;
import java.util.Map;
import java.util.UUID;

/**
 * Admin loyalty façade into customer LoyaltyService (bridged in apps/api). No domain→domain compile
 * dependency.
 */
public interface LoyaltyAdminPort {

  Map<String, Object> getProgram(MedmatePrincipal principal);

  Map<String, Object> patchProgram(MedmatePrincipal principal, PatchProgramCommand cmd);

  Map<String, Object> overview(MedmatePrincipal principal);

  Map<String, Object> adjust(MedmatePrincipal principal, UUID customerId, AdjustCommand cmd);

  record PatchProgramCommand(
      Integer earnRateRsPerPoint,
      Number redemptionRateRsPerPoint,
      Integer tierSilverPts,
      Integer tierGoldPts,
      Integer tierPlatinumPts,
      Integer maxRedemptionPctPerOrder,
      Integer minPointsPerRedemption,
      Integer pointsExpiryDays) {}

  record AdjustCommand(Integer points, String reason, String referenceOrderId) {}
}
