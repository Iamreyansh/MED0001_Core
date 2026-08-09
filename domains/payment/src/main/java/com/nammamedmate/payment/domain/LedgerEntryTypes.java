package com.nammamedmate.payment.domain;

import java.util.Locale;
import java.util.Set;

/** Canonical ledger entry types (EPIC-012 STORY-008) + legacy aliases. */
public final class LedgerEntryTypes {

  public static final String ORDER_GMV = "ORDER_GMV";
  public static final String COMMISSION = "COMMISSION";
  public static final String TCS = "TCS";

  /** Legacy writer type from STORY-003; exposed as {@link #TCS} in API. */
  public static final String TCS_COLLECTED = "TCS_COLLECTED";

  public static final String PAYOUT_PHARMACY = "PAYOUT_PHARMACY";
  public static final String PAYOUT_RIDER = "PAYOUT_RIDER";
  public static final String REFUND = "REFUND";
  public static final String WALLET_CREDIT = "WALLET_CREDIT";
  public static final String WALLET_DEBIT = "WALLET_DEBIT";
  public static final String COD_DEPOSIT = "COD_DEPOSIT";
  public static final String GATEWAY_FEE = "GATEWAY_FEE";

  private static final Set<String> KNOWN =
      Set.of(
          ORDER_GMV,
          COMMISSION,
          TCS,
          TCS_COLLECTED,
          PAYOUT_PHARMACY,
          PAYOUT_RIDER,
          REFUND,
          WALLET_CREDIT,
          WALLET_DEBIT,
          COD_DEPOSIT,
          GATEWAY_FEE);

  private LedgerEntryTypes() {}

  public static boolean isKnown(String type) {
    return type != null && KNOWN.contains(type.trim().toUpperCase(Locale.ROOT));
  }

  /** Normalize stored type for API responses (TCS_COLLECTED → TCS). */
  public static String toApiType(String stored) {
    if (stored == null) {
      return null;
    }
    if (TCS_COLLECTED.equalsIgnoreCase(stored)) {
      return TCS;
    }
    return stored;
  }

  /** DB entry_type values matching a story filter (TCS includes legacy TCS_COLLECTED). */
  public static String[] storageTypesForFilter(String apiType) {
    if (apiType == null || apiType.isBlank()) {
      return new String[0];
    }
    String t = apiType.trim().toUpperCase(Locale.ROOT);
    if (TCS.equals(t) || TCS_COLLECTED.equals(t)) {
      return new String[] {TCS, TCS_COLLECTED};
    }
    return new String[] {t};
  }
}
