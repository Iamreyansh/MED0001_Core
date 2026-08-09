# Admin RBAC permission matrix (EPIC-021 STORY-005)

Source of truth for **API-facing** lists: `com.nammamedmate.auth.domain.AdminRoleDefinitions` (kept in `domains/auth` with `AdminRolesController`).

Roles are fixed and non-customisable. Write attempts on `/api/v1/admin/roles/**` return `405 METHOD_NOT_ALLOWED`.

## API matrix (GET `/api/v1/admin/roles`)

| Role | Permissions |
|------|-------------|
| `admin_super` | `*:*` (implicit wildcard; `permission_count` null; see `notes`) |
| `admin_operations` | `orders:read`, `orders:write`, `orders:cancel`, `orders:assign-rider`, `pharmacies:read`, `pharmacies:update`, `riders:read`, `riders:write`, `riders:assign`, `riders:suspend`, `logistics:read`, `logistics:update`, `catalogue:read` (13) |
| `admin_finance` | `finance:read`, `finance:write`, `finance:release-payout`, `settlements:read`, `settlements:process`, `refunds:read`, `refunds:approve`, `refunds:reject`, `taxes:read`, `taxes:export`, `analytics:finance`, `customers:read`, `wallet:credit` (13) |
| `admin_support` | `tickets:read`, `tickets:write`, `tickets:close`, `disputes:read`, `disputes:write`, `disputes:resolve`, `customers:read`, `customers:notify`, `customers:flag`, `orders:read` (10) |
| `admin_compliance` | `prescriptions:read`, `prescriptions:review`, `prescriptions:approve`, `prescriptions:reject`, `compliance:read`, `compliance:audit`, `compliance:flag`, `catalogue:read`, `catalogue:update`, `pharmacies:read`, `kyc:read`, `kyc:approve`, `kyc:reject` (13) |

Expanded objects: `GET /api/v1/admin/roles/{role}/permissions`.

Catalog (EPIC-001): `GET /api/v1/admin/permissions` remains available.

## Enforcement union (live extras)

`RbacPermissionService.hasPermission` uses `enforcementPermissionsFor(role)` = story API list ∪ live extras so shipped `@RequiresPermission` checks do not regress after the matrix was expanded to exact lists.

| Role | Live extras (not in GET /roles response) | Why |
|------|------------------------------------------|-----|
| `admin_operations` | `pharmacies:suspend`, `orders:dispatch`, `customers:read`, `finance:read`, `orders:*`, `riders:*`, `logistics:*` | Pharmacy suspend, dispatch, prior wildcard coverage |
| `admin_finance` | `finance:update`, `finance:*`, `pharmacies:read` | Commission/COD/payout update + wallet `finance:*` gate |
| `admin_support` | `pharmacies:read`, `finance:read` | Prior support pharmacy read; EPIC-012 refund queue read |
| `admin_compliance` | `pharmacies:update`, `customers:read` | Live KYC admin uses `pharmacies:update` |

`admin_super` still short-circuits to allow-all in middleware.

## Error codes

| HTTP | Code | When |
|------|------|------|
| 403 | `INSUFFICIENT_PERMISSIONS` | Admin lacks required permission; `error.details.required_permission` set |
| 403 | `FORBIDDEN` | Non-admin calling roles APIs |
| 404 | `ROLE_NOT_FOUND` | Unknown role slug on GET …/permissions |
| 405 | `METHOD_NOT_ALLOWED` | POST/PUT/PATCH/DELETE on roles endpoints (Spring method-not-supported → `GlobalExceptionHandler`) |
