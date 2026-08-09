package com.nammamedmate.integration.domain;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Tally Prime VOUCHER XML 1.0 (ENVELOPE/BODY/IMPORTDATA). */
public final class TallyXmlBuilder {

  private static final DateTimeFormatter TALLY_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final String NL = System.lineSeparator();

  private TallyXmlBuilder() {}

  public static String buildSales(List<AccountingVoucher> vouchers) {
    StringBuilder vouchersXml = new StringBuilder();
    for (AccountingVoucher v : vouchers) {
      String date = v.voucherDate().format(TALLY_DATE);
      String amountRupees = paiseToRupees(v.totalPaise());
      vouchersXml
          .append("      <VOUCHER VCHTYPE=\"Sales\" ACTION=\"Create\">")
          .append(NL)
          .append("        <DATE>")
          .append(date)
          .append("</DATE>")
          .append(NL)
          .append("        <VOUCHERNUMBER>")
          .append(escape(v.voucherNumber()))
          .append("</VOUCHERNUMBER>")
          .append(NL)
          .append("        <REFERENCE>")
          .append(escape(v.platformId().toString()))
          .append("</REFERENCE>")
          .append(NL)
          .append("        <PARTYLEDGERNAME>")
          .append(escape(nullToEmpty(v.partyName())))
          .append("</PARTYLEDGERNAME>")
          .append(NL)
          .append("        <NARRATION>Platform ID ")
          .append(escape(v.platformId().toString()))
          .append("</NARRATION>")
          .append(NL)
          .append("        <ALLLEDGERENTRIES.LIST>")
          .append(NL)
          .append("          <LEDGERNAME>")
          .append(escape(nullToEmpty(v.partyName())))
          .append("</LEDGERNAME>")
          .append(NL)
          .append("          <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE>")
          .append(NL)
          .append("          <AMOUNT>-")
          .append(amountRupees)
          .append("</AMOUNT>")
          .append(NL)
          .append("        </ALLLEDGERENTRIES.LIST>")
          .append(NL)
          .append("        <ALLLEDGERENTRIES.LIST>")
          .append(NL)
          .append("          <LEDGERNAME>Sales</LEDGERNAME>")
          .append(NL)
          .append("          <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>")
          .append(NL)
          .append("          <AMOUNT>")
          .append(amountRupees)
          .append("</AMOUNT>")
          .append(NL)
          .append("        </ALLLEDGERENTRIES.LIST>")
          .append(NL)
          .append("      </VOUCHER>")
          .append(NL);
    }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + System.lineSeparator()
        + "<ENVELOPE>"
        + System.lineSeparator()
        + "  <HEADER>"
        + System.lineSeparator()
        + "    <TALLYREQUEST>Import Data</TALLYREQUEST>"
        + System.lineSeparator()
        + "    <TYPE>Data</TYPE>"
        + System.lineSeparator()
        + "    <ID>Vouchers</ID>"
        + System.lineSeparator()
        + "    <VERSION>1</VERSION>"
        + System.lineSeparator()
        + "  </HEADER>"
        + System.lineSeparator()
        + "  <BODY>"
        + System.lineSeparator()
        + "    <IMPORTDATA>"
        + System.lineSeparator()
        + "      <REQUESTDESC>"
        + System.lineSeparator()
        + "        <REPORTNAME>Vouchers</REPORTNAME>"
        + System.lineSeparator()
        + "      </REQUESTDESC>"
        + System.lineSeparator()
        + "      <REQUESTDATA>"
        + System.lineSeparator()
        + vouchersXml
        + "      </REQUESTDATA>"
        + System.lineSeparator()
        + "    </IMPORTDATA>"
        + System.lineSeparator()
        + "  </BODY>"
        + System.lineSeparator()
        + "</ENVELOPE>"
        + System.lineSeparator();
  }

  private static String paiseToRupees(long paise) {
    long rupees = paise / 100;
    long fraction = Math.abs(paise % 100);
    return String.format(Locale.ROOT, "%d.%02d", rupees, fraction);
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
