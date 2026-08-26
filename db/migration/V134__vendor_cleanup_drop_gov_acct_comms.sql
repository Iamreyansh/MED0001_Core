-- Vendor cleanup: drop government KYC APIs, e-invoicing, accounting, communication control-plane,
-- WhatsApp, and email delivery tables. Keep Maps/Razorpay/Twilio/FCM/manual pharmacy KYC.
-- Rollback: restore from prior migrations V076–V079, V103–V104 (not automated).

-- Government verification / DigiLocker / Drug / FSSAI call logs
DROP TABLE IF EXISTS government_api_call_log CASCADE;
DROP TABLE IF EXISTS government_verification_cache CASCADE;

-- E-invoicing / GSP
DROP TABLE IF EXISTS einvoice_api_call_log CASCADE;
DROP TABLE IF EXISTS einvoice_irn_records CASCADE;

-- Accounting (Zoho / Tally)
DROP TABLE IF EXISTS accounting_sync_jobs CASCADE;
DROP TABLE IF EXISTS accounting_integrations CASCADE;

-- Communication control plane (EPIC-022 STORY-006)
DROP TABLE IF EXISTS communication_config_audit CASCADE;
DROP TABLE IF EXISTS communication_cost_daily CASCADE;
DROP TABLE IF EXISTS communication_channel_configs CASCADE;

-- WhatsApp (V103)
DROP TABLE IF EXISTS whatsapp_delivery_logs CASCADE;
DROP TABLE IF EXISTS whatsapp_optouts CASCADE;
DROP TABLE IF EXISTS whatsapp_sessions CASCADE;
DROP TABLE IF EXISTS whatsapp_templates CASCADE;

-- Email (V104)
DROP TABLE IF EXISTS email_delivery_logs CASCADE;
DROP TABLE IF EXISTS email_bounces CASCADE;
DROP TABLE IF EXISTS email_unsubscribes CASCADE;
DROP TABLE IF EXISTS email_templates CASCADE;

-- Pharmacy e-invoicing flag
ALTER TABLE pharmacies DROP COLUMN IF EXISTS e_invoicing_enabled;

-- SMS delivery provider CHECK: keep MSG91 allowed for historical rows; new writes are TWILIO-only.
-- Safe alter would require rewriting existing MSG91 rows first — leave CHECK as MSG91|TWILIO (V102).
-- Comment retained for Twilio agent: tighten to TWILIO-only after backfill/purge of MSG91 logs.
