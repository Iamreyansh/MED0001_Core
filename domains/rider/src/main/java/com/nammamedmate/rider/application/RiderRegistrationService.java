package com.nammamedmate.rider.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.application.port.out.RiderStore;
import com.nammamedmate.rider.application.port.out.RiderStore.RiderRecord;
import com.nammamedmate.rider.application.port.out.ZoneLookupPort;
import com.nammamedmate.rider.domain.RiderPhones;
import com.nammamedmate.rider.domain.VehiclePlates;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RiderRegistrationService {

  private static final Set<String> VEHICLE_TYPES = Set.of("BIKE", "BICYCLE", "SCOOTER");

  private final RiderStore riders;
  private final ZoneLookupPort zones;
  private final Clock clock;

  public RiderRegistrationService(RiderStore riders, ZoneLookupPort zones, Clock clock) {
    this.riders = riders;
    this.zones = zones;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> register(
      String name,
      String phoneRaw,
      String email,
      String vehicleType,
      String plateRaw,
      UUID zoneId) {
    if (name == null || name.isBlank() || name.trim().length() > 100) {
      throw new AppException("VALIDATION_ERROR", "name is required (max 100 chars)", 400);
    }
    String phone = RiderPhones.normalize(phoneRaw);
    if (!RiderPhones.isValid(phone)) {
      throw new AppException("VALIDATION_ERROR", "Invalid Indian mobile number", 400);
    }
    if (vehicleType == null || !VEHICLE_TYPES.contains(vehicleType.trim().toUpperCase())) {
      throw new AppException(
          "VALIDATION_ERROR", "vehicle_type must be BIKE, BICYCLE, or SCOOTER", 400);
    }
    String plate = VehiclePlates.normalize(plateRaw);
    if (!VehiclePlates.isValid(plate)) {
      throw new AppException(
          "INVALID_VEHICLE_PLATE", "vehicle_plate_number does not match Indian RTO format", 422);
    }
    if (email != null && email.length() > 255) {
      throw new AppException("VALIDATION_ERROR", "email max 255 chars", 400);
    }
    if (zoneId != null) {
      zones
          .findById(zoneId)
          .filter(ZoneLookupPort.ZoneInfo::active)
          .orElseThrow(
              () -> new AppException("INVALID_ZONE", "preferred_zone_id does not exist", 422));
    }
    if (riders.existsByPhone(phone)) {
      throw new AppException("PHONE_ALREADY_REGISTERED", "Phone number already registered", 409);
    }

    Instant now = clock.instant();
    UUID id = Ids.newId();
    String type = vehicleType.trim().toUpperCase();
    RiderRecord rider =
        new RiderRecord(
            id,
            name.trim(),
            phone,
            blankToNull(email),
            type,
            plate,
            zoneId,
            "PENDING_KYC",
            "NOT_SUBMITTED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            0,
            null,
            0L,
            0L,
            0,
            null,
            null,
            null,
            now,
            now);
    riders.insert(rider);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rider_id", id.toString());
    data.put("name", rider.name());
    data.put("phone", phone);
    data.put("status", "PENDING_KYC");
    data.put("kyc_status", "NOT_SUBMITTED");
    data.put("created_at", now.toString());
    return data;
  }

  private static String blankToNull(String v) {
    if (v == null || v.isBlank()) {
      return null;
    }
    return v.trim();
  }
}
