package com.nammamedmate.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Quote bill: no coupons; handling + delivery rules match cart. */
public final class RxQuotePricing {

  public record QuoteBill(
      long itemTotalPaise, long deliveryFeePaise, long handlingFeePaise, long totalPayablePaise) {}

  private RxQuotePricing() {}

  public static QuoteBill compute(List<QuotedMedicine> medicines) {
    long item = 0L;
    if (medicines != null) {
      for (QuotedMedicine m : medicines) {
        item += m.pricePaise();
      }
    }
    boolean empty = item <= 0;
    long handling = empty ? 0L : CartPricing.HANDLING_FEE_PAISE;
    long delivery =
        empty
            ? 0L
            : (item < CartPricing.FREE_DELIVERY_THRESHOLD_PAISE
                ? CartPricing.DELIVERY_FEE_PAISE
                : 0L);
    return new QuoteBill(item, delivery, handling, item + delivery + handling);
  }

  public static long rupeesToPaise(Object rupees) {
    if (rupees == null) {
      throw new IllegalArgumentException("price required");
    }
    BigDecimal bd = rupees instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(rupees));
    return bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }
}
