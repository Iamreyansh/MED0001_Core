package com.nammamedmate.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPharmacyPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
import com.nammamedmate.pos.domain.ShareChannel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PosConfigDefaultsTest {

  @Test
  void defaultBeans() {
    PosConfig config = new PosConfig();
    PosPlanPort plan = config.posPlanPort(true, false);
    assertThat(plan.starterFeaturesEnabled()).isTrue();
    assertThat(plan.growthFeaturesEnabled()).isFalse();

    PosFefoPort fefo = config.noOpPosFefoPort();
    assertThat(fefo.selectFefoBatch(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    assertThat(fefo.listEligibleBatches(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    assertThat(fefo.findBatch(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())).isEmpty();

    StockDeductionPort stock = config.noOpStockDeductionPort();
    assertThatThrownBy(
            () ->
                stock.deductSale(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    UUID.randomUUID(),
                    Instant.now()))
        .isInstanceOf(AppException.class);

    ProductLookupPort products = config.noOpProductLookupPort();
    assertThat(products.findById(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    assertThat(products.findByBarcode(UUID.randomUUID(), "x")).isEmpty();
    assertThat(products.searchByText(UUID.randomUUID(), "x", 10)).isEmpty();
    assertThat(products.searchByRack(UUID.randomUUID(), "A1", 10)).isEmpty();

    PosCustomerPort customers = config.noOpPosCustomerPort();
    assertThatThrownBy(() -> customers.findOrCreate("+91", "A")).isInstanceOf(AppException.class);

    PosKhataPort khata = config.stubPosKhataPort();
    UUID c = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    assertThat(khata.outstandingPaise(pharmacy, c)).isZero();
    assertThat(khata.creditLimitPaise(pharmacy, c)).isEqualTo(Long.MAX_VALUE / 2);
    khata.ensureCustomerKnown(pharmacy, c);
    khata.postCreditSale(c, UUID.randomUUID(), 100, pharmacy);
    assertThat(
            khata.recordCreditRepayment(
                c, UUID.randomUUID(), 100, pharmacy, "CASH", null, null, UUID.randomUUID()))
        .startsWith("RCPT-");

    PosPharmacyPort pharmacyPort = config.stubPosPharmacyPort();
    assertThat(pharmacyPort.findById(UUID.randomUUID())).isPresent();

    PosNotificationPort notify = config.stubPosNotificationPort(true);
    assertThat(
            notify
                .shareInvoice(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "INV-1",
                    ShareChannel.WHATSAPP,
                    "+91",
                    "data:pdf")
                .messageId())
        .startsWith("whatsapp_msg_");
    assertThat(
            notify
                .sendKhataReminder(UUID.randomUUID(), c, ShareChannel.SMS, "POLITE", "+91", 100L)
                .messageId())
        .startsWith("sms_msg_");
    PosNotificationPort disabled = config.stubPosNotificationPort(false);
    assertThatThrownBy(
            () ->
                disabled.shareInvoice(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "INV-1",
                    ShareChannel.SMS,
                    "+91",
                    "data:pdf"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                disabled.sendKhataReminder(
                    UUID.randomUUID(), c, ShareChannel.WHATSAPP, "FIRM", "+91", 1L))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");

    SimpleXlsxExporter xlsx = config.posSimpleXlsxExporter();
    assertThat(SimpleXlsxExporter.looksLikeXlsx(xlsx.exportSheet("S", new String[] {"a"}, null)))
        .isTrue();
  }
}
