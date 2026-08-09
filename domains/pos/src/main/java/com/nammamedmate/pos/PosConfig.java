package com.nammamedmate.pos;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pos.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPharmacyPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PosConfig {

  @Bean
  @ConditionalOnMissingBean(PosPlanPort.class)
  public PosPlanPort posPlanPort(
      @Value("${medmate.pos.starter-features-enabled:false}") boolean starterFeaturesEnabled,
      @Value("${medmate.pos.growth-features-enabled:false}") boolean growthFeaturesEnabled) {
    return new PosPlanPort() {
      @Override
      public boolean starterFeaturesEnabled() {
        return starterFeaturesEnabled;
      }

      @Override
      public boolean growthFeaturesEnabled() {
        return growthFeaturesEnabled;
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(PosFefoPort.class)
  public PosFefoPort noOpPosFefoPort() {
    return new PosFefoPort() {
      @Override
      public Optional<BatchSnapshot> selectFefoBatch(UUID pharmacyId, UUID productId) {
        return Optional.empty();
      }

      @Override
      public List<BatchSnapshot> listEligibleBatches(UUID pharmacyId, UUID productId) {
        return List.of();
      }

      @Override
      public Optional<BatchSnapshot> findBatch(UUID pharmacyId, UUID productId, UUID batchId) {
        return Optional.empty();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(StockDeductionPort.class)
  public StockDeductionPort noOpStockDeductionPort() {
    return (pharmacyId, productId, batchId, quantity, staffId, now) -> {
      throw new AppException("INSUFFICIENT_STOCK", "Inventory bridge not configured", 400);
    };
  }

  @Bean
  @ConditionalOnMissingBean(ProductLookupPort.class)
  public ProductLookupPort noOpProductLookupPort() {
    return new ProductLookupPort() {
      @Override
      public Optional<ProductSnapshot> findById(UUID pharmacyId, UUID productId) {
        return Optional.empty();
      }

      @Override
      public Optional<ProductSnapshot> findByBarcode(UUID pharmacyId, String barcode) {
        return Optional.empty();
      }

      @Override
      public List<SearchHit> searchByText(UUID pharmacyId, String query, int limit) {
        return List.of();
      }

      @Override
      public List<SearchHit> searchByRack(UUID pharmacyId, String rackCode, int limit) {
        return List.of();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(PosCustomerPort.class)
  public PosCustomerPort noOpPosCustomerPort() {
    return (phone, name) -> {
      throw new AppException("VALIDATION_ERROR", "Customer bridge not configured", 400);
    };
  }

  @Bean
  @ConditionalOnMissingBean(PosKhataPort.class)
  public PosKhataPort stubPosKhataPort() {
    return new PosKhataPort() {
      @Override
      public long outstandingPaise(UUID pharmacyId, UUID customerId) {
        return 0L;
      }

      @Override
      public long creditLimitPaise(UUID pharmacyId, UUID customerId) {
        return Long.MAX_VALUE / 2;
      }

      @Override
      public void ensureCustomerKnown(UUID pharmacyId, UUID customerId) {
        // no-op stub
      }

      @Override
      public void postCreditSale(
          UUID customerId, UUID invoiceId, long amountPaise, UUID pharmacyId) {
        // no-op when JDBC KhataStore absent
      }

      @Override
      public String recordCreditRepayment(
          UUID customerId,
          UUID invoiceId,
          long amountPaise,
          UUID pharmacyId,
          String paymentMode,
          String referenceNumber,
          String note,
          UUID collectedBy) {
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        int n = Math.floorMod(invoiceId == null ? 1 : invoiceId.hashCode(), 1_000_000);
        return String.format(
            Locale.ROOT, "RCPT-%04d-%02d-%06d", d.getYear(), d.getMonthValue(), n == 0 ? 1 : n);
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(PosPharmacyPort.class)
  public PosPharmacyPort stubPosPharmacyPort() {
    return pharmacyId ->
        Optional.of(new PosPharmacyPort.PharmacyInfo("Pharmacy", null, null, null, null));
  }

  @Bean
  @ConditionalOnMissingBean(PosNotificationPort.class)
  public PosNotificationPort stubPosNotificationPort(
      @Value("${medmate.pos.notifications-enabled:true}") boolean notificationsEnabled) {
    return new PosNotificationPort() {
      @Override
      public ShareResult shareInvoice(
          UUID pharmacyId,
          UUID invoiceId,
          String invoiceNumber,
          com.nammamedmate.pos.domain.ShareChannel channel,
          String recipient,
          String pdfUrl) {
        return share(channel);
      }

      @Override
      public ShareResult sendKhataReminder(
          UUID pharmacyId,
          UUID customerId,
          com.nammamedmate.pos.domain.ShareChannel channel,
          String template,
          String recipient,
          long outstandingPaise) {
        return share(channel);
      }

      private ShareResult share(com.nammamedmate.pos.domain.ShareChannel channel) {
        if (!notificationsEnabled) {
          throw new AppException(
              "CHANNEL_UNAVAILABLE", "Notification channel temporarily unavailable", 503);
        }
        Instant now = Instant.now();
        String messageId = channel.name().toLowerCase(Locale.ROOT) + "_msg_" + Ids.newId();
        return new ShareResult(messageId, now);
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(SimpleXlsxExporter.class)
  public SimpleXlsxExporter posSimpleXlsxExporter() {
    return new SimpleXlsxExporter();
  }
}
