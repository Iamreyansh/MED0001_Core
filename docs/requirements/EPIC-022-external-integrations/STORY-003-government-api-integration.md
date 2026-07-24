# STORY-003: Government API Integration

| Field | Value |
|-------|-------|
| Story ID | EPIC-022-STORY-003 |
| Epic | EPIC-022 External Integrations |
| Title | Government API Integration |
| Priority | P1 |
| Status | In Development |
| Role | Internal service + admin_compliance |
| Last Updated | 2026-07-24 |

## Overview

The Government API Integration story covers all public government database integrations used for pharmacy KYC verification: GSTN API for GSTIN number validation, DigiLocker OAuth2 for Aadhaar-based eKYC of pharmacy owners, the Central Drugs Standard Control Organisation (CDSCO) drug licence registry for licence verification, and the FSSAI portal for food safety licence verification. All results are cached for 7 days to avoid repeated API calls on re-submission. Integration results feed the pharmacy KYC review workflow (EPIC-006).

## User Roles

| Role | Access |
|------|--------|
| Internal services | Call verification endpoints (service-to-service) |
| admin_compliance | View verification results; trigger re-verification |

## Business Rules

1. **Result Caching**: All government API responses are cached for 7 days by the verified identifier (GSTIN, licence number). Subsequent KYC submissions within 7 days use the cached result without re-calling the API.
2. **GSTIN Checksum Pre-Validation**: Before calling the GSTN API, the platform validates the GSTIN checksum locally (the standard MOD-36 algorithm). Invalid checksums return `422 INVALID_GSTIN_FORMAT` without an API call.
3. **GSTN API Rate Limit**: GSTN API allows 100 calls/minute. The platform uses a token bucket rate limiter with 80 calls/minute (conservative buffer). Requests exceeding the limit return 429 with a retry-after header.
4. **Drug Licence API Async**: State drug licence registries vary in API quality. Some are synchronous; others require async polling. The `verify-licence` endpoint initiates a check and returns `status: PENDING` if async. A callback/poll pattern is supported.
5. **DigiLocker OAuth2 Flow**: DigiLocker uses standard OAuth2 authorization code flow. The `initiate` endpoint returns a redirect URL. After user authentication in DigiLocker, the platform receives a callback at the `/callback` endpoint with the authorization code, which is exchanged for an access token and Aadhaar-linked documents.
6. **Manual Review Fallback**: If any government API is unavailable (timeout, rate limit, HTTP error), the KYC document is queued for manual admin review. The integration result shows `status: MANUAL_REVIEW_REQUIRED`.
7. **Audit Logging**: All government API calls are logged with request parameters (minus PII beyond required identifiers), response status, latency, and whether result was cached.
8. **Credential Security**: Government API credentials (GSTN API key, DigiLocker client_id/secret, drug registry API tokens) are stored in AWS Secrets Manager and never logged.
9. **FSSAI Optional**: FSSAI licence is optional for pharmacies (only required for pharmacies that also sell food/supplements). The verification is called only when the pharmacy submits an FSSAI number.
10. **Expiry Alert**: If a verified licence is approaching expiry (within 30 days), the platform flags it in the KYC record and triggers a compliance reminder to the pharmacy (via EPIC-019 automation trigger `register_due`).

## API Endpoints

### POST /api/v1/integrations/gstn/verify

Verify a GSTIN (GST Identification Number).

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "gstin": "29ABCDE1234F1Z5"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "gstin": "29ABCDE1234F1Z5",
    "valid": true,
    "trade_name": "Apollo Pharmacy India Ltd",
    "legal_name": "Apollo Hospitals Enterprise Limited",
    "registration_status": "ACTIVE",
    "filing_status": "REGULAR",
    "state": "Karnataka",
    "state_code": "29",
    "registered_at": "2018-04-01",
    "cache_hit": false,
    "verified_at": "2026-07-24T10:20:00Z"
  },
  "meta": {}
}
```

**Error Table**

| HTTP Code | Error Code | Condition |
|-----------|-----------|-----------|
| 422 | INVALID_GSTIN_FORMAT | Checksum validation failed locally |
| 422 | GSTIN_NOT_FOUND | GSTN returns no record |
| 429 | GSTN_RATE_LIMIT | Rate limit exceeded |
| 503 | GSTN_API_UNAVAILABLE | GSTN portal unreachable |

---

### POST /api/v1/integrations/digilocker/initiate

Initiate DigiLocker OAuth2 authentication for Aadhaar eKYC.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "phone": "+919876543210",
  "purpose": "PHARMACY_KYC",
  "entity_id": "uuid-ph-1",
  "redirect_uri": "https://app.nammamedmate.in/kyc/digilocker/callback"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "auth_url": "https://api.digitallocker.gov.in/public/oauth2/1/authorize?response_type=code&client_id=NM_CLIENT&state=random_state_xyz&redirect_uri=...",
    "state": "random_state_xyz",
    "expires_in_seconds": 600
  },
  "meta": {}
}
```

---

### POST /api/v1/integrations/digilocker/callback

Handle DigiLocker OAuth2 callback and fetch documents.

**Auth**: Internal (callback from DigiLocker server; verified via `state` parameter)

**Request Body** (from DigiLocker)
```json
{
  "code": "auth_code_from_digilocker",
  "state": "random_state_xyz"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "entity_id": "uuid-ph-1",
    "aadhaar_verified": true,
    "name_on_aadhaar": "Rajesh Kumar",
    "dob": "1985-03-15",
    "address": "12, MG Road, Bangalore - 560001",
    "documents_fetched": ["AADHAAR", "DRIVING_LICENCE"],
    "verified_at": "2026-07-24T10:25:00Z"
  },
  "meta": {}
}
```

---

### POST /api/v1/integrations/drug-registry/verify-licence

Verify a drug licence number with the state drug control authority.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "licence_number": "KA/DRUG/2019/0042",
  "state": "Karnataka",
  "licence_type": "RETAIL"
}
```

**Response 200 (synchronous)**
```json
{
  "success": true,
  "data": {
    "licence_number": "KA/DRUG/2019/0042",
    "valid": true,
    "holder_name": "Apollo Pharmacy India Ltd",
    "issued_date": "2019-06-15",
    "expiry_date": "2024-06-14",
    "is_expired": true,
    "drugs_permitted": ["SCHEDULE_H", "SCHEDULE_H1", "OTC"],
    "state": "Karnataka",
    "licence_type": "RETAIL",
    "status": "EXPIRED",
    "cache_hit": false,
    "verified_at": "2026-07-24T10:22:00Z"
  },
  "meta": {}
}
```

**Response 202 (async for states with polling-based APIs)**
```json
{
  "success": true,
  "data": {
    "verification_id": "uuid-ver-1",
    "status": "PENDING",
    "poll_after_seconds": 30,
    "poll_url": "/api/v1/integrations/drug-registry/verification/uuid-ver-1"
  },
  "meta": {}
}
```

---

### POST /api/v1/integrations/fssai/verify

Verify an FSSAI (food safety) licence.

**Auth**: Service-to-service JWT (internal only)

**Request Body**
```json
{
  "licence_number": "10019011001234"
}
```

**Response 200**
```json
{
  "success": true,
  "data": {
    "licence_number": "10019011001234",
    "valid": true,
    "business_name": "Apollo Health & Lifestyle Ltd",
    "category": "CENTRAL_LICENCE",
    "expiry_date": "2027-01-31",
    "is_expired": false,
    "status": "ACTIVE",
    "verified_at": "2026-07-24T10:23:00Z"
  },
  "meta": {}
}
```

---

## Data Models

### government_verification_cache

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| verification_type | VARCHAR(20) | GSTIN, DRUG_LICENCE, FSSAI, DIGILOCKER |
| identifier | VARCHAR(100) | GSTIN, licence number, etc. |
| state | VARCHAR(50) | Nullable; for state-specific APIs |
| result_json | JSONB | Full verification result |
| is_valid | BOOLEAN | Quick validity flag |
| expiry_date | DATE | Nullable; licence expiry from result |
| verified_at | TIMESTAMPTZ | |
| expires_at | TIMESTAMPTZ | verified_at + 7 days (cache TTL) |

### government_api_call_log

| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| api_type | VARCHAR(20) | GSTN, DRUG_REGISTRY, FSSAI, DIGILOCKER |
| identifier | VARCHAR(100) | Verified identifier |
| http_status | SMALLINT | HTTP response status |
| result_status | VARCHAR(20) | OK, NOT_FOUND, RATE_LIMITED, ERROR |
| latency_ms | INTEGER | |
| was_cache_hit | BOOLEAN | |
| entity_type | VARCHAR(20) | Caller entity type |
| entity_id | UUID | Caller entity ID |
| called_at | TIMESTAMPTZ | |

## Acceptance Criteria

1. **AC-001**: POST /gstn/verify with an invalid GSTIN checksum returns `422 INVALID_GSTIN_FORMAT` without calling the GSTN API.
2. **AC-002**: POST /gstn/verify for a previously verified GSTIN within 7 days returns `cache_hit: true` and uses cached data.
3. **AC-003**: POST /drug-registry/verify-licence returns `is_expired: true` and `status: EXPIRED` for licences past their expiry date.
4. **AC-004**: POST /digilocker/initiate returns a valid `auth_url` that redirects the user to DigiLocker's authorization page.
5. **AC-005**: POST /digilocker/callback with an invalid `state` parameter returns `400 INVALID_STATE`.
6. **AC-006**: A pharmacy with a drug licence expiring within 30 days triggers the `register_due` automation trigger (EPIC-019).
7. **AC-007**: POST /fssai/verify returns `422 FSSAI_LICENCE_NOT_FOUND` for a licence number that does not exist in the FSSAI database.
8. **AC-008**: All government API calls are logged in `government_api_call_log`; no API credentials or Aadhaar numbers appear in logs.

## Dependencies

| Dependency | Type | Notes |
|-----------|------|-------|
| GSTN API (GST Portal) | External | GSTIN verification |
| DigiLocker API | External | Aadhaar eKYC |
| State Drug Registry APIs | External | Drug licence verification |
| FSSAI API | External | Food safety licence |
| EPIC-006 Pharmacy KYC | Consumer | KYC document submission |
| EPIC-019 Automation | Consumer | register_due trigger |
| Redis | Infrastructure | 7-day result cache |
| AWS Secrets Manager | Credential store | API keys |

## Notes

- Drug licence API availability varies by state. Karnataka, Maharashtra, and Tamil Nadu have REST APIs. Other states may require manual verification (admin review). The `MANUAL_REVIEW_REQUIRED` status handles these cases.
- DigiLocker integration requires the platform to be registered as a requester entity with the Ministry of Electronics and IT (MeitY). Approval takes 2-4 weeks. Plan this in the integration timeline.
- GSTIN format: 15 characters - 2-digit state code + 10-char PAN + 1 entity number + 1 check digit + 1 default 'Z'. Checksum uses MOD-36 of the 14th character.
