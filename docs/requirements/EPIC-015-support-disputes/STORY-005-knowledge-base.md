# STORY-005: Knowledge Base

| Field        | Value                         |
| ------------ | ----------------------------- |
| Story ID     | EPIC-015-STORY-005            |
| Epic         | EPIC-015 Support and Disputes |
| Title        | Knowledge Base                |
| Priority     | P2                            |
| Status       | Planned                       |
| Created      | 2026-07-24                    |
| Last Updated | 2026-07-24                    |

---

## Overview

The Knowledge Base module reduces support ticket volume and accelerates agent response through two complementary tools: a public self-service Help Center for customers, and an agent-facing library of canned responses for rapid ticket replies. Customers can search and browse categorised help articles, and the system tracks whether articles successfully deflect tickets (via a post-read deflection survey). Agents access canned responses from within the ticket reply composer using a `/` shortcut key search, with template variable interpolation for personalisation. Admins manage both article content and canned response libraries through CRUD endpoints, with usage analytics surfaced on the admin dashboard.

---

## User Roles

| Role               | Capability                                                                            |
| ------------------ | ------------------------------------------------------------------------------------- |
| `admin_super`      | Full CRUD on canned responses and help articles                                       |
| `admin_operations` | Create, edit, delete canned responses and articles; publish articles                  |
| `admin_support`    | Read canned responses; use in ticket replies                                          |
| `customer`         | Read public help articles; submit deflection feedback (no auth required for articles) |

---

## Business Rules

1. **Help articles are public** - the `GET /api/v1/support/help` endpoint does not require authentication; any user (including unauthenticated app visitors) can read help articles.
2. **View count tracking** - `view_count` on an article is incremented each time the article is read via `GET /api/v1/support/help` (one increment per article per request, not de-duplicated in v1).
3. **Deflection tracking** - when a customer resolves their issue via an article without creating a ticket, they can submit a deflection event; `deflection_count` increments when `issue_resolved = true`.
4. **Canned response template variables** - supported variables: `{customer_name}`, `{order_id}`, `{refund_amount}`, `{pharmacy_name}`, `{ticket_id}`; variables are interpolated server-side at the time the canned response is selected in a ticket reply.
5. **Shortcut key search** - agents type `/` in the ticket reply box to trigger a canned response search; this is a frontend behaviour driven by the `GET /api/v1/admin/support/canned-responses` endpoint with a `q` query param.
6. **Category alignment** - canned response categories match ticket categories (`ORDER`, `PAYMENT`, `PHARMACY`, `RIDER`, `ACCOUNT`, `PRODUCT`, `OTHER`); this allows category-filtered search in the ticket context.
7. **Article publishing** - articles with `is_published = false` are not returned by the public help endpoint; they are visible only in the admin article list.
8. **Top deflecting articles** - articles sorted by `deflection_count` descending are surfaced in an admin KPI widget; these drive content investments.
9. **Canned response usage tracking** - `copy_count` and `last_used_at` on each canned response are updated when an agent selects the response for a ticket reply.
10. **Unique shortcut keys** - shortcut keys on canned responses must be unique across the library; duplicate shortcut keys return `SHORTCUT_KEY_EXISTS`.

---

## API Endpoints

### 1. List Canned Responses (Admin)

```
GET /api/v1/admin/support/canned-responses
Authorization: Bearer JWT (admin_super | admin_operations | admin_support)
```

**Query Parameters**

| Parameter  | Type    | Description                                    |
| ---------- | ------- | ---------------------------------------------- |
| `category` | string  | Filter by ticket category                      |
| `q`        | string  | Search title or body (for shortcut "/" search) |
| `page`     | integer | Default 1                                      |
| `limit`    | integer | Default 20                                     |

**Response 200**

```json
{
	"success": true,
	"data": {
		"canned_responses": [
			{
				"id": "cr_uuid_001",
				"title": "Wrong items - apology + replacement",
				"category": "ORDER",
				"body": "Hi {customer_name}, I'm sorry to hear that you received wrong items in your order {order_id}. I've initiated a replacement for you immediately. It will arrive within 2-3 hours.",
				"shortcut_key": "/wrong-items",
				"copy_count": 148,
				"last_used_at": "2026-07-24T10:20:00Z"
			}
		]
	},
	"meta": { "page": 1, "limit": 20, "total": 42 }
}
```

---

### 2. Create Canned Response (Admin)

```
POST /api/v1/admin/support/canned-responses
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**

```json
{
	"title": "Payment refund - processing time",
	"category": "PAYMENT",
	"body": "Hi {customer_name}, your refund of {refund_amount} for order {order_id} has been processed. It will reflect in your account within 5-7 business days.",
	"shortcut_key": "/refund-processing"
}
```

**Response 201**

```json
{
	"success": true,
	"data": {
		"id": "cr_uuid_002",
		"title": "Payment refund - processing time",
		"shortcut_key": "/refund-processing",
		"created_at": "2026-07-24T10:00:00Z"
	}
}
```

**Error Responses**

| HTTP | Error Code                  | Description                             |
| ---- | --------------------------- | --------------------------------------- |
| 409  | `SHORTCUT_KEY_EXISTS`       | Shortcut key already used               |
| 422  | `INVALID_TEMPLATE_VARIABLE` | Body contains unrecognised `{variable}` |

---

### 3. Update Canned Response (Admin)

```
PATCH /api/v1/admin/support/canned-responses/:id
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**

```json
{
	"body": "Hi {customer_name}, your refund of {refund_amount} has been initiated. Please allow 3-5 business days.",
	"shortcut_key": "/refund-status"
}
```

**Response 200**

```json
{
	"success": true,
	"data": { "id": "cr_uuid_002", "updated_at": "2026-07-24T11:00:00Z" }
}
```

---

### 4. Delete Canned Response (Admin)

```
DELETE /api/v1/admin/support/canned-responses/:id
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Response 200**

```json
{
	"success": true,
	"data": { "id": "cr_uuid_002", "deleted": true }
}
```

---

### 5. List Help Articles (Admin)

```
GET /api/v1/admin/support/help-articles
Authorization: Bearer JWT (admin_super | admin_operations)
```

**Query Parameters**

| Parameter      | Type    | Description                |
| -------------- | ------- | -------------------------- |
| `category`     | string  | Filter by category         |
| `is_published` | boolean | Filter by published status |
| `page`         | integer | Default 1                  |

**Response 200**

```json
{
	"success": true,
	"data": {
		"articles": [
			{
				"id": "art_uuid_001",
				"title": "How to track my order",
				"category": "ORDER",
				"view_count": 4820,
				"deflection_count": 1240,
				"is_published": true,
				"last_updated": "2026-07-10T09:00:00Z"
			}
		]
	},
	"meta": { "page": 1, "limit": 20, "total": 28 }
}
```

---

### 6. Create Help Article (Admin)

```
POST /api/v1/admin/support/help-articles
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**

```json
{
	"title": "How to cancel my order",
	"category": "ORDER",
	"content_markdown": "## Cancelling your order\n\nYou can cancel your order before it is dispatched...",
	"tags": ["order", "cancel", "refund"],
	"is_published": true
}
```

**Response 201**

```json
{
	"success": true,
	"data": {
		"id": "art_uuid_002",
		"title": "How to cancel my order",
		"is_published": true,
		"created_at": "2026-07-24T10:00:00Z"
	}
}
```

---

### 7. Update Help Article (Admin)

```
PATCH /api/v1/admin/support/help-articles/:id
Authorization: Bearer JWT (admin_super | admin_operations)
Content-Type: application/json
```

**Request Body**

```json
{
	"content_markdown": "## Cancelling your order\n\nUpdated content with new steps...",
	"is_published": true
}
```

**Response 200**

```json
{
	"success": true,
	"data": { "id": "art_uuid_001", "updated_at": "2026-07-24T11:00:00Z" }
}
```

---

### 8. Public Help Center (Customer-Facing)

```
GET /api/v1/support/help
Authorization: None required
```

**Query Parameters**

| Parameter  | Type   | Description                |
| ---------- | ------ | -------------------------- |
| `category` | string | Filter by category         |
| `q`        | string | Search articles by keyword |

**Response 200**

```json
{
	"success": true,
	"data": {
		"categories": [
			{ "name": "ORDER", "article_count": 12 },
			{ "name": "PAYMENT", "article_count": 8 },
			{ "name": "ACCOUNT", "article_count": 5 }
		],
		"articles": [
			{
				"id": "art_uuid_001",
				"title": "How to track my order",
				"category": "ORDER",
				"summary": "Learn how to track your delivery in real time...",
				"tags": ["order", "tracking", "delivery"]
			}
		]
	},
	"meta": { "total": 12 }
}
```

---

### 9. Read Help Article (Customer-Facing)

```
GET /api/v1/support/help/articles/:id
Authorization: None required
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"id": "art_uuid_001",
		"title": "How to track my order",
		"category": "ORDER",
		"content_markdown": "## How to track your order\n\nOnce your order is confirmed...",
		"tags": ["order", "tracking"],
		"view_count": 4821,
		"last_updated": "2026-07-10T09:00:00Z"
	}
}
```

---

### 10. Log Deflection Event (Customer-Facing)

```
POST /api/v1/support/help/deflection
Authorization: Bearer JWT (customer) [optional]
Content-Type: application/json
```

**Request Body**

```json
{
	"article_id": "art_uuid_001",
	"issue_resolved": true
}
```

**Response 200**

```json
{
	"success": true,
	"data": {
		"article_id": "art_uuid_001",
		"issue_resolved": true,
		"deflection_logged": true
	}
}
```

---

## Data Model

### CannedResponse

| Field          | Type         | Constraints      | Description                          |
| -------------- | ------------ | ---------------- | ------------------------------------ |
| `id`           | UUID v4      | PK               | Internal identifier                  |
| `title`        | VARCHAR(200) | NOT NULL         | Response title                       |
| `category`     | VARCHAR(20)  | NOT NULL         | Ticket category                      |
| `body`         | TEXT         | NOT NULL         | Response body with `{variables}`     |
| `shortcut_key` | VARCHAR(50)  | UNIQUE, NOT NULL | Agent shortcut (e.g. `/wrong-items`) |
| `copy_count`   | INTEGER      | DEFAULT 0        | Times used in replies                |
| `last_used_at` | TIMESTAMPTZ  | NULLABLE         | Last usage timestamp                 |
| `created_by`   | UUID         | FK ? admin_users | Creator                              |
| `created_at`   | TIMESTAMPTZ  | DEFAULT NOW()    | Creation timestamp                   |
| `updated_at`   | TIMESTAMPTZ  | DEFAULT NOW()    | Last update                          |

### HelpArticle

| Field              | Type         | Constraints      | Description            |
| ------------------ | ------------ | ---------------- | ---------------------- |
| `id`               | UUID v4      | PK               | Internal identifier    |
| `title`            | VARCHAR(200) | NOT NULL         | Article title          |
| `category`         | VARCHAR(20)  | NOT NULL         | Category               |
| `content_markdown` | TEXT         | NOT NULL         | Full article content   |
| `tags`             | TEXT[]       | DEFAULT {}       | Search tags            |
| `is_published`     | BOOLEAN      | DEFAULT false    | Visibility flag        |
| `view_count`       | INTEGER      | DEFAULT 0        | Total views            |
| `deflection_count` | INTEGER      | DEFAULT 0        | Successful deflections |
| `created_by`       | UUID         | FK ? admin_users | Creator                |
| `created_at`       | TIMESTAMPTZ  | DEFAULT NOW()    | Creation timestamp     |
| `updated_at`       | TIMESTAMPTZ  | DEFAULT NOW()    | Last update            |

---

## Acceptance Criteria

1. Admin creates a canned response; it is searchable by shortcut key `/refund-processing` and appears in the canned response list.
2. Creating a canned response with a duplicate shortcut key returns HTTP 409 `SHORTCUT_KEY_EXISTS`.
3. Canned response body contains `{customer_name}` and `{order_id}`; server interpolates variables correctly when used in a ticket reply.
4. Public help endpoint returns only `is_published = true` articles; unpublished articles are excluded.
5. `view_count` increments by 1 each time a customer reads an article via the public endpoint.
6. `deflection_count` increments by 1 when a customer submits `issue_resolved = true` for an article.
7. Admin article list sorts by `deflection_count` descending to surface top-performing articles.
8. Keyword search `q = "track"` returns "How to track my order" from the help center.
9. `admin_support` role can read canned responses but cannot create or delete them; attempt returns HTTP 403.
10. Canned responses with `category = ORDER` are returned first when agent opens `/` search in an ORDER category ticket.

---

## Dependencies

| Dependency                    | Description                                                 |
| ----------------------------- | ----------------------------------------------------------- |
| Ticket Management (STORY-001) | `canned_response_id` on ticket replies; `copy_count` update |
| Customer Auth                 | Optional auth on deflection endpoint                        |
| CDN / Storage                 | Markdown content served as-is (no CDN for text)             |

---

## Notes

- Full-text search on help articles (for `q` parameter) uses PostgreSQL `tsvector`/`tsquery` or a dedicated search service (e.g. Typesense) in production.
- Canned response interpolation errors (e.g. `order_id` not available for an ACCOUNT category ticket) should gracefully leave the placeholder unfilled rather than failing.
- In v2, help articles will support rich media (images, embedded videos) via a CDN-backed editor; v1 supports Markdown only.
- The public help endpoint is rate-limited to 60 requests per minute per IP to prevent scraping.
