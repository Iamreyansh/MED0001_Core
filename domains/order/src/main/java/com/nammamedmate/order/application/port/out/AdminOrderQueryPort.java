package com.nammamedmate.order.application.port.out;

import com.nammamedmate.order.domain.AdminOrderSegment;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderStatus;
import com.nammamedmate.order.domain.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminOrderQueryPort {

  record AdminListFilter(
      AdminOrderSegment segment,
      String search,
      UUID pharmacyId,
      UUID riderId,
      UUID zoneId,
      PaymentMethod paymentMethod,
      Boolean isRxOnly,
      LocalDate fromDate,
      LocalDate toDate,
      Instant now,
      int page,
      int limit) {}

  record AdminOrderListRow(
      Order order,
      String customerName,
      String customerPhone,
      String pharmacyName,
      String area,
      BigDecimal commissionPct,
      boolean disputed) {}

  record SummaryAgg(
      long totalOrders, long liveNow, long slaRisk, long gmvPaise, long commissionPaise) {}

  record PharmacyAdminView(UUID id, String name, String area, BigDecimal commissionPct) {}

  record CustomerAdminView(UUID id, String name, String phone, int orderCount, long ltvPaise) {}

  record AdminStaffName(UUID id, String name) {}

  List<AdminOrderListRow> list(AdminListFilter filter);

  long count(AdminListFilter filter);

  SummaryAgg summary(AdminListFilter filter);

  List<AdminOrderListRow> listAllForExport(AdminListFilter filter, int maxRows);

  List<AdminOrderListRow> liveFeed(Instant now, int limit);

  Optional<PharmacyAdminView> findPharmacy(UUID pharmacyId);

  Optional<CustomerAdminView> findCustomer(UUID customerId);

  Optional<AdminStaffName> findAdminName(UUID adminId);

  Optional<String> findAddressArea(UUID addressId);

  /** Statuses treated as live / in-progress for admin segments. */
  static List<OrderStatus> liveStatuses() {
    return List.of(
        OrderStatus.PENDING_ACCEPTANCE,
        OrderStatus.ACCEPTED,
        OrderStatus.PACKING,
        OrderStatus.READY_FOR_PICKUP,
        OrderStatus.OUT_FOR_DELIVERY);
  }

  static long commissionPaise(long totalPayablePaise, BigDecimal commissionPct) {
    if (commissionPct == null) {
      return 0L;
    }
    return commissionPct
        .multiply(BigDecimal.valueOf(totalPayablePaise))
        .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP)
        .longValue();
  }
}
