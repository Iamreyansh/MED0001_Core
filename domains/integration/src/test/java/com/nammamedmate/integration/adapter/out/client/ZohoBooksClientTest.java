package com.nammamedmate.integration.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.integration.application.port.out.ZohoBooksClientPort;
import com.nammamedmate.integration.domain.AccountingVoucher;
import com.nammamedmate.kernel.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZohoBooksClientTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC);

  @Test
  void stubDedupesAndFailsInvalidGstin() {
    StubZohoBooksClient stub = new StubZohoBooksClient(CLOCK);
    AccountingVoucher ok =
        new AccountingVoucher(
            UUID.randomUUID(),
            "SALES_INVOICE",
            "1",
            LocalDate.now(),
            "A",
            "29ABCDE1234F1ZW",
            1,
            0,
            1);
    AccountingVoucher bad =
        new AccountingVoucher(
            UUID.randomUUID(), "SALES_INVOICE", "2", LocalDate.now(), "B", "27INVALID", 1, 0, 1);
    assertThat(stub.upsertSalesVoucher("t", "o", ok).created()).isTrue();
    assertThat(stub.upsertSalesVoucher("t", "o", ok).created()).isFalse();
    assertThat(stub.upsertPurchaseVoucher("t", "o", ok).voucherId()).isNotBlank();
    assertThat(stub.upsertGstEntry("t", "o", ok).voucherId()).isNotBlank();
    assertThat(stub.upsertExpense("t", "o", ok).voucherId()).isNotBlank();
    assertThat(stub.upsertSalesVoucher("t", "o", bad).errorCode())
        .isEqualTo("INVALID_CUSTOMER_GSTIN");
    AccountingVoucher nullGst =
        new AccountingVoucher(
            UUID.randomUUID(), "SALES_INVOICE", "3", LocalDate.now(), "C", null, 1, 0, 1);
    assertThat(stub.upsertSalesVoucher("t", "o", nullGst).created()).isTrue();
    assertThat(stub.refreshAccessToken("r").accessToken()).startsWith("stub-access-");
    assertThat(stub.voucherCount()).isEqualTo(5);
  }

  @Test
  void liveClientHappyAndErrorPaths() {
    ObjectMapper mapper = new ObjectMapper();
    LiveZohoBooksClient live =
        new LiveZohoBooksClient(
            "cid",
            "sec",
            "https://accounts.test",
            "https://books.test/v3",
            mapper,
            call -> {
              if (call.uri().getPath().contains("/oauth/v2/token")) {
                return "{\"access_token\":\"a\",\"refresh_token\":\"r2\",\"expires_in\":3600}";
              }
              if (call.uri().toString().contains("/bills")) {
                return "{\"bill\":{\"bill_id\":\"bill-1\"}}";
              }
              if (call.uri().toString().contains("/journals")) {
                return "{\"journal\":{\"journal_id\":\"j-1\"}}";
              }
              if (call.uri().toString().contains("/expenses")) {
                return "{\"expense\":{\"expense_id\":\"e-1\"}}";
              }
              if (call.uri().toString().contains("dup")) {
                return "{\"code\":\"3041\",\"message\":\"duplicate\",\"invoice\":{\"invoice_id\":\"ex\"}}";
              }
              if (call.uri().toString().contains("err")) {
                return "{\"error_code\":\"ZOHO_ERR\",\"message\":\"bad\",\"code\":4000}";
              }
              return "{\"invoice\":{\"invoice_id\":\"inv-1\"}}";
            });
    ZohoBooksClientPort.TokenPair pair = live.refreshAccessToken("r");
    assertThat(pair.accessToken()).isEqualTo("a");
    AccountingVoucher v =
        new AccountingVoucher(
            UUID.randomUUID(),
            "SALES_INVOICE",
            "1",
            LocalDate.of(2026, 7, 1),
            "A",
            "29ABCDE1234F1ZW",
            100,
            12,
            112);
    assertThat(live.upsertSalesVoucher("tok", "org", v).voucherId()).isEqualTo("inv-1");
    assertThat(live.upsertPurchaseVoucher("tok", "org", v).voucherId()).isEqualTo("bill-1");
    assertThat(live.upsertGstEntry("tok", "org", v).voucherId()).isEqualTo("j-1");
    assertThat(live.upsertExpense("tok", "org", v).voucherId()).isEqualTo("e-1");

    LiveZohoBooksClient failingRefresh =
        new LiveZohoBooksClient(
            "c",
            "s",
            "https://a",
            "https://b",
            mapper,
            call -> {
              throw new RuntimeException("down");
            });
    assertThatThrownBy(() -> failingRefresh.refreshAccessToken("r"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZOHO_UNAVAILABLE");

    LiveZohoBooksClient appEx =
        new LiveZohoBooksClient(
            "c",
            "s",
            "https://a",
            "https://b",
            mapper,
            call -> {
              throw new AppException("ZOHO_API_UNAVAILABLE", "x", 503);
            });
    assertThatThrownBy(() -> appEx.refreshAccessToken("r"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZOHO_API_UNAVAILABLE");

    LiveZohoBooksClient emptyToken =
        new LiveZohoBooksClient(
            "c", "s", "https://a", "https://b", mapper, call -> "{\"access_token\":\"\"}");
    assertThatThrownBy(() -> emptyToken.refreshAccessToken("r"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZOHO_UNAVAILABLE");

    LiveZohoBooksClient dupAndErr =
        new LiveZohoBooksClient(
            "c",
            "s",
            "https://a",
            "https://b",
            mapper,
            call -> {
              String uri = call.uri().toString();
              if (uri.contains("organization_id=dup")) {
                return "{\"code\":\"3041\",\"message\":\"duplicate\",\"invoice\":{\"invoice_id\":\"ex\"}}";
              }
              if (uri.contains("organization_id=err")) {
                return "{\"error_code\":\"ZOHO_ERR\",\"message\":\"bad\",\"code\":4000}";
              }
              if (uri.contains("organization_id=empty")) {
                return "{}";
              }
              throw new RuntimeException("down");
            });
    AccountingVoucher v2 =
        new AccountingVoucher(
            UUID.randomUUID(), "SALES_INVOICE", "2", LocalDate.of(2026, 7, 1), "A", null, 1, 0, 1);
    assertThat(dupAndErr.upsertSalesVoucher("t", "dup", v2).created()).isFalse();
    assertThat(dupAndErr.upsertSalesVoucher("t", "err", v2).errorCode()).isEqualTo("ZOHO_ERR");
    assertThat(dupAndErr.upsertSalesVoucher("t", "empty", v2).voucherId()).isEqualTo("zoho-ok");
    assertThatThrownBy(() -> dupAndErr.upsertSalesVoucher("t", "fail", v2))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZOHO_UNAVAILABLE");

    LiveZohoBooksClient slashAndIds =
        new LiveZohoBooksClient(
            "c",
            "s",
            "https://accounts.test/",
            "https://books.test/v3/",
            mapper,
            call -> {
              if (call.uri().toString().contains("/bills")) {
                return "{\"bill\":{\"bill_id\":\"b1\"},\"invoice_id\":\"\"}";
              }
              if (call.uri().toString().contains("/journals")) {
                return "{\"journal\":{\"journal_id\":\"j1\"}}";
              }
              if (call.uri().toString().contains("message")) {
                return "{\"message\":\"DUPLICATE\",\"invoice\":{\"invoice_id\":\"d1\"}}";
              }
              throw new AppException("ZOHO_API_UNAVAILABLE", "x", 503);
            });
    assertThat(slashAndIds.upsertPurchaseVoucher("t", "o", v2).voucherId()).isEqualTo("b1");
    assertThat(slashAndIds.upsertGstEntry("t", "o", v2).voucherId()).isEqualTo("j1");
    assertThat(slashAndIds.upsertSalesVoucher("t", "message", v2).created()).isFalse();
    assertThatThrownBy(() -> slashAndIds.upsertExpense("t", "o", v2))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ZOHO_API_UNAVAILABLE");
  }
}
