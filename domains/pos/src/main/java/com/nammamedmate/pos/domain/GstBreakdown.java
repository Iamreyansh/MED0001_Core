package com.nammamedmate.pos.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Intra-state CGST/SGST half-split grouped by GST slab + HSN. */
public final class GstBreakdown {

  private GstBreakdown() {}

  public static List<Map<String, Object>> fromItems(List<InvoiceItem> items) {
    Map<String, long[]> buckets = new LinkedHashMap<>();
    Map<String, Integer> slabs = new LinkedHashMap<>();
    Map<String, String> hsns = new LinkedHashMap<>();
    if (items != null) {
      for (InvoiceItem item : items) {
        String hsn = item.hsnCode() == null ? "" : item.hsnCode();
        String key = item.gstPct() + "|" + hsn;
        long[] agg = buckets.computeIfAbsent(key, k -> new long[2]);
        agg[0] += MoneyMath.taxableFromInclusive(item.lineTotalPaise(), item.gstPct());
        agg[1] += item.gstAmountPaise();
        slabs.putIfAbsent(key, item.gstPct());
        hsns.putIfAbsent(key, hsn.isEmpty() ? null : hsn);
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<String, long[]> e : buckets.entrySet()) {
      long gst = e.getValue()[1];
      long cgst = gst / 2;
      long sgst = gst - cgst;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("slab", slabs.get(e.getKey()) + "%");
      row.put("hsn_code", hsns.get(e.getKey()));
      row.put("taxable_amount", MoneyMath.paiseToRupees(e.getValue()[0]));
      row.put("cgst", MoneyMath.paiseToRupees(cgst));
      row.put("sgst", MoneyMath.paiseToRupees(sgst));
      rows.add(row);
    }
    return rows;
  }

  public static long halfGstPaise(long gstPaise) {
    return gstPaise / 2;
  }
}
