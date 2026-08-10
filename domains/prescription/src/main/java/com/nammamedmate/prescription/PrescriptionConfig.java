package com.nammamedmate.prescription;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.CustomerContactPort;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.InventoryBanPort;
import com.nammamedmate.prescription.application.port.out.InventoryBatchPort;
import com.nammamedmate.prescription.application.port.out.InventoryStockPort;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.OrderLinesPort;
import com.nammamedmate.prescription.application.port.out.OrderLinkPort;
import com.nammamedmate.prescription.application.port.out.OrderStatusPort;
import com.nammamedmate.prescription.application.port.out.PharmacyPlanPort;
import com.nammamedmate.prescription.application.port.out.PosDispensePort;
import com.nammamedmate.prescription.application.port.out.PrescriptionInUsePort;
import com.nammamedmate.prescription.application.port.out.ScheduleRegisterWritePort;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrescriptionConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock prescriptionClock() {
    return Clock.systemUTC();
  }

  /** Unit-test / isolated context fallback until apps/api bridge wires carts. */
  @Bean
  @ConditionalOnMissingBean(OrderLinkPort.class)
  OrderLinkPort stubOrderLinkPort() {
    return (customerId, cartId, prescriptionId) -> {
      throw new AppException("CART_NOT_FOUND", "Cart not found or not ACTIVE", 404);
    };
  }

  @Bean
  @ConditionalOnMissingBean(PrescriptionInUsePort.class)
  PrescriptionInUsePort stubPrescriptionInUsePort() {
    return prescriptionId -> false;
  }

  @Bean
  @ConditionalOnMissingBean(PharmacyPlanPort.class)
  PharmacyPlanPort stubPharmacyPlanPort() {
    return pharmacyId -> true;
  }

  @Bean
  @ConditionalOnMissingBean(OrderLinesPort.class)
  OrderLinesPort stubOrderLinesPort() {
    return (orderId, medicines) -> {};
  }

  @Bean
  @ConditionalOnMissingBean(OrderStatusPort.class)
  OrderStatusPort stubOrderStatusPort() {
    return orderId -> {};
  }

  @Bean
  @ConditionalOnMissingBean(PosDispensePort.class)
  PosDispensePort stubPosDispensePort() {
    return new PosDispensePort() {
      @Override
      public boolean available() {
        return true;
      }

      @Override
      public UUID pushToBillingCart(
          UUID pharmacyId, UUID staffId, List<ApprovedMedicine> medicines) {
        return Ids.newId();
      }

      @Override
      public UUID createSaleRecord(
          UUID pharmacyId, UUID staffId, UUID orderId, List<ApprovedMedicine> medicines) {
        return Ids.newId();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(NotificationDispatchPort.class)
  NotificationDispatchPort stubNotificationDispatchPort() {
    return new NotificationDispatchPort() {
      @Override
      public void notifyCustomerRxRejected(
          UUID customerId, UUID rxId, String reason, String customMessage) {}

      @Override
      public void notifyPharmacyOwnerOverdue(UUID pharmacyId, UUID rxId) {}
    };
  }

  @Bean
  @ConditionalOnMissingBean(DoctorCardPort.class)
  DoctorCardPort stubDoctorCardPort() {
    return (rxId, type, doctorName, teleconsultId) -> {
      if ("E_PRESCRIPTION".equals(type)) {
        return Optional.of(
            new DoctorCardPort.DoctorCard(
                doctorName == null ? "Dr. Verified" : doctorName, "MBBS MD", "NMC-STUB-001", true));
      }
      return Optional.of(new DoctorCardPort.DoctorCard(doctorName, null, null, false));
    };
  }

  /** Fallback when ScheduleDrugRegisterService is not on the classpath (isolated unit contexts). */
  @Bean
  @ConditionalOnMissingBean(ScheduleRegisterWritePort.class)
  ScheduleRegisterWritePort stubScheduleRegisterWritePort() {
    return (pharmacyId, rxId, staffId, medicines) -> {};
  }

  @Bean
  @ConditionalOnMissingBean(InventoryBatchPort.class)
  InventoryBatchPort stubInventoryBatchPort() {
    return (pharmacyId, drugName) -> Optional.empty();
  }

  @Bean
  @ConditionalOnMissingBean(InventoryBanPort.class)
  InventoryBanPort stubInventoryBanPort() {
    return (drugName, batchNo) -> new InventoryBanPort.BanResult(0, List.of());
  }

  @Bean
  @ConditionalOnMissingBean(InventoryStockPort.class)
  InventoryStockPort stubInventoryStockPort() {
    return (pharmacyId, medicineName) -> Optional.empty();
  }

  /**
   * ponytail: name heuristics until catalogue schedule bridge (EPIC-005). Unknown → empty (caller
   * falls back to OCR / H1-when-flagged / NONE).
   */
  @Bean
  @ConditionalOnMissingBean(CatalogueSchedulePort.class)
  CatalogueSchedulePort stubCatalogueSchedulePort() {
    return medicineName -> {
      if (medicineName == null || medicineName.isBlank()) {
        return Optional.empty();
      }
      String n = medicineName.toUpperCase(Locale.ROOT);
      if (n.contains("H1") || n.contains("ALPRAZOLAM") || n.contains("CLONAZEPAM")) {
        return Optional.of("H1");
      }
      if (n.contains("SCHEDULE X") || n.contains("SCH-X") || n.contains("MORPHINE")) {
        return Optional.of("X");
      }
      if (n.contains("SCHEDULE H") || n.endsWith(" H") || n.contains("METFORMIN")) {
        return Optional.of("H");
      }
      return Optional.empty();
    };
  }

  @Bean
  @ConditionalOnMissingBean(CustomerContactPort.class)
  CustomerContactPort stubCustomerContactPort() {
    return new CustomerContactPort() {
      @Override
      public Optional<Contact> find(UUID customerId) {
        return Optional.empty();
      }

      @Override
      public int previousOrdersCount(UUID customerId, UUID pharmacyId) {
        return 0;
      }
    };
  }
}
