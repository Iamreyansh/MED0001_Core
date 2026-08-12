-- EPIC-019 / STORY-001: rules engine core registries
-- Rollback: DROP TABLE IF EXISTS trigger_events;
--           DROP TABLE IF EXISTS automation_health_config;
--           DROP TABLE IF EXISTS action_registry;
--           DROP TABLE IF EXISTS trigger_registry;
-- Notes: seed 34 triggers (BR-8, category-disambiguated) + 16 actions
--        (BR-9 + mass_payout + flag_prescription); thin kill_switch_status
--        in automation_health_config (full STORY-007 later).

CREATE TABLE trigger_registry (
    trigger_id              VARCHAR(60) PRIMARY KEY,
    category                VARCHAR(20) NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    description             TEXT NOT NULL,
    parameters_schema       JSONB NOT NULL DEFAULT '[]'::jsonb,
    available_conditions    TEXT[] NOT NULL DEFAULT '{}',
    available_context_vars  TEXT[] NOT NULL DEFAULT '{}',
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_trigger_registry_category
        CHECK (category IN (
            'ORDERS', 'DISPATCH', 'PHARMACY', 'RIDER', 'FINANCE',
            'CRM', 'SUPPORT', 'COMPLIANCE', 'GROWTH'))
);

CREATE TABLE action_registry (
    action_id                  VARCHAR(60) PRIMARY KEY,
    category                   VARCHAR(20) NOT NULL,
    name                       VARCHAR(100) NOT NULL,
    description                TEXT NOT NULL,
    required_params_schema     JSONB NOT NULL DEFAULT '[]'::jsonb,
    optional_params_schema     JSONB NOT NULL DEFAULT '[]'::jsonb,
    is_reversible              BOOLEAN NOT NULL DEFAULT FALSE,
    always_require_approval    BOOLEAN NOT NULL DEFAULT FALSE,
    auto_approval_limit_paise  BIGINT,
    CONSTRAINT chk_action_registry_category
        CHECK (category IN ('DISPATCH', 'FINANCE', 'NOTIFICATION', 'ADMIN', 'CRM'))
);

CREATE TABLE trigger_events (
    id                UUID PRIMARY KEY,
    trigger_id        VARCHAR(60) NOT NULL REFERENCES trigger_registry (trigger_id),
    entity_type       VARCHAR(30) NOT NULL,
    entity_id         UUID NOT NULL,
    payload           JSONB NOT NULL DEFAULT '{}'::jsonb,
    fired_at          TIMESTAMPTZ NOT NULL,
    processed_at      TIMESTAMPTZ,
    rules_evaluated   INTEGER NOT NULL DEFAULT 0,
    rules_fired       INTEGER NOT NULL DEFAULT 0,
    outcome           VARCHAR(40),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trigger_events_trigger_fired
    ON trigger_events (trigger_id, fired_at DESC);
CREATE INDEX idx_trigger_events_entity
    ON trigger_events (entity_type, entity_id, fired_at DESC);

CREATE TABLE automation_health_config (
    config_key    VARCHAR(60) PRIMARY KEY,
    config_value  VARCHAR(60) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO automation_health_config (config_key, config_value) VALUES
    ('kill_switch_status', 'ACTIVE');

INSERT INTO trigger_registry (
    trigger_id, category, name, description, parameters_schema,
    available_conditions, available_context_vars, is_active
) VALUES
('order_placed', 'ORDERS', 'Order Placed', 'Fires when a customer places an order.', '[{"name":"priority","type":"string","required":false}]'::jsonb, ARRAY['zone_in','priority_eq','amount_gt','time_of_day_between','day_of_week_in'], ARRAY['order.id','order.zone_id','order.placed_at','order.priority','order.amount_paise'], TRUE),
('order_accepted', 'ORDERS', 'Order Accepted', 'Fires when a pharmacy accepts an order.', '[{"name":"pharmacy_id","type":"uuid","required":false}]'::jsonb, ARRAY['zone_in','plan_tier_eq','priority_eq'], ARRAY['order.id','order.pharmacy_id','order.zone_id','order.priority'], TRUE),
('order_stuck_in_stage', 'ORDERS', 'Order Stuck In Stage', 'Fires when an order remains in a stage beyond threshold.', '[{"name":"stage","type":"string","required":true},{"name":"duration_minutes","type":"integer","required":true}]'::jsonb, ARRAY['zone_in','priority_eq','count_gt'], ARRAY['order.id','order.stage','order.minutes_in_stage','order.zone_id'], TRUE),
('order_sla_breaching', 'ORDERS', 'Order SLA Breaching', 'Fires when an order is about to breach delivery SLA.', '[{"name":"minutes_to_breach","type":"integer","required":true}]'::jsonb, ARRAY['zone_in','priority_eq','time_of_day_between'], ARRAY['order.id','order.zone_id','order.sla_deadline','order.priority'], TRUE),
('order_cancelled', 'ORDERS', 'Order Cancelled', 'Fires when an order is cancelled.', '[{"name":"reason_code","type":"string","required":false}]'::jsonb, ARRAY['zone_in','segment_in'], ARRAY['order.id','order.zone_id','order.cancel_reason','order.amount_paise'], TRUE),
('order_delivered', 'ORDERS', 'Order Delivered', 'Fires when an order is marked delivered.', '[]'::jsonb, ARRAY['zone_in','plan_tier_eq'], ARRAY['order.id','order.zone_id','order.delivered_at','order.rider_id'], TRUE),
('order_unassigned', 'DISPATCH', 'Order Unassigned', 'Fires when an order has been placed but no rider assigned for N minutes.', '[{"name":"duration_minutes","type":"integer","required":true,"description":"Minutes without assignment before trigger fires"}]'::jsonb, ARRAY['zone_in','time_of_day_between','day_of_week_in'], ARRAY['order.id','order.zone_id','order.placed_at','order.priority'], TRUE),
('rider_no_show', 'DISPATCH', 'Rider No Show', 'Fires when an assigned rider fails to pick up within threshold.', '[{"name":"wait_minutes","type":"integer","required":true}]'::jsonb, ARRAY['zone_in','priority_eq'], ARRAY['order.id','rider.id','order.zone_id'], TRUE),
('rider_went_offline_mid_trip', 'DISPATCH', 'Rider Went Offline Mid Trip', 'Fires when a rider goes offline while carrying an active order.', '[]'::jsonb, ARRAY['zone_in','risk_score_gt'], ARRAY['order.id','rider.id','order.zone_id','rider.last_seen_at'], TRUE),
('pharmacy_kyc_submitted', 'PHARMACY', 'Pharmacy KYC Submitted', 'Fires when a pharmacy submits KYC documents.', '[]'::jsonb, ARRAY['plan_tier_eq','zone_in'], ARRAY['pharmacy.id','pharmacy.plan_tier','pharmacy.zone_id'], TRUE),
('fill_rate_below_threshold', 'PHARMACY', 'Fill Rate Below Threshold', 'Fires when pharmacy fill rate drops below threshold.', '[{"name":"below_pct","type":"number","required":true}]'::jsonb, ARRAY['plan_tier_eq','zone_in','count_gt'], ARRAY['pharmacy.id','pharmacy.fill_rate_pct','pharmacy.plan_tier'], TRUE),
('storefront_offline_in_peak_hours', 'PHARMACY', 'Storefront Offline In Peak Hours', 'Fires when pharmacy storefront is offline during peak hours.', '[{"name":"peak_window","type":"string","required":false}]'::jsonb, ARRAY['time_of_day_between','day_of_week_in','zone_in'], ARRAY['pharmacy.id','pharmacy.zone_id','pharmacy.offline_since'], TRUE),
('payout_due', 'PHARMACY', 'Payout Due', 'Fires when a pharmacy payout becomes due.', '[{"name":"amount_paise","type":"integer","required":false}]'::jsonb, ARRAY['amount_gt','plan_tier_eq'], ARRAY['pharmacy.id','payout.amount_paise','pharmacy.plan_tier'], TRUE),
('rider_kyc_submitted', 'RIDER', 'Rider KYC Submitted', 'Fires when a rider submits KYC documents.', '[]'::jsonb, ARRAY['zone_in'], ARRAY['rider.id','rider.zone_id'], TRUE),
('on_time_pct_drop', 'RIDER', 'On-Time Pct Drop', 'Fires when rider on-time percentage drops below threshold.', '[{"name":"below_pct","type":"number","required":true}]'::jsonb, ARRAY['zone_in','count_gt','risk_score_gt'], ARRAY['rider.id','rider.on_time_pct','rider.zone_id'], TRUE),
('cod_in_hand_above_limit', 'RIDER', 'COD In Hand Above Limit', 'Fires when rider COD cash-in-hand exceeds limit.', '[{"name":"limit_paise","type":"integer","required":true}]'::jsonb, ARRAY['amount_gt','zone_in','risk_score_gt'], ARRAY['rider.id','rider.cod_in_hand_paise','rider.zone_id'], TRUE),
('payout_cycle_reached', 'FINANCE', 'Payout Cycle Reached', 'Fires when a scheduled payout cycle is reached.', '[{"name":"cycle_id","type":"string","required":false}]'::jsonb, ARRAY['amount_gt','plan_tier_eq'], ARRAY['payout.cycle_id','payout.total_paise','entity.type','entity.id'], TRUE),
('payment_failed', 'FINANCE', 'Payment Failed', 'Fires when a payment attempt fails.', '[{"name":"reason_code","type":"string","required":false}]'::jsonb, ARRAY['amount_gt','segment_in'], ARRAY['payment.id','payment.amount_paise','order.id','customer.id'], TRUE),
('refund_queued', 'FINANCE', 'Refund Queued', 'Fires when a refund is queued for processing.', '[]'::jsonb, ARRAY['amount_gt','priority_eq'], ARRAY['refund.id','refund.amount_paise','order.id'], TRUE),
('invoice_overdue', 'FINANCE', 'Invoice Overdue', 'Fires when an invoice becomes overdue.', '[{"name":"days_overdue","type":"integer","required":false}]'::jsonb, ARRAY['amount_gt','plan_tier_eq','count_gt'], ARRAY['invoice.id','invoice.amount_paise','pharmacy.id','pharmacy.plan_tier'], TRUE),
('health_score_drop', 'CRM', 'Health Score Drop', 'Fires when a pharmacys CRM health score drops below a threshold.', '[{"name":"below_value","type":"integer","required":true}]'::jsonb, ARRAY['plan_tier_eq','health_band_eq'], ARRAY['pharmacy.id','pharmacy.plan_tier','pharmacy.health_score'], TRUE),
('near_seat_cap', 'CRM', 'Near Seat Cap', 'Fires when a pharmacy approaches seat capacity.', '[{"name":"remaining_seats","type":"integer","required":true}]'::jsonb, ARRAY['plan_tier_eq','count_gt'], ARRAY['pharmacy.id','pharmacy.plan_tier','pharmacy.seats_used','pharmacy.seats_cap'], TRUE),
('trial_ending', 'CRM', 'Trial Ending', 'Fires when a pharmacy trial is ending soon.', '[{"name":"days_remaining","type":"integer","required":true}]'::jsonb, ARRAY['plan_tier_eq','segment_in'], ARRAY['pharmacy.id','pharmacy.trial_ends_at','pharmacy.plan_tier'], TRUE),
('renewal_approaching', 'CRM', 'Renewal Approaching', 'Fires when a subscription renewal is approaching.', '[{"name":"days_remaining","type":"integer","required":true}]'::jsonb, ARRAY['plan_tier_eq','amount_gt'], ARRAY['pharmacy.id','pharmacy.renewal_at','pharmacy.plan_tier'], TRUE),
('usage_dip', 'CRM', 'Usage Dip', 'Fires when pharmacy module usage dips below baseline.', '[{"name":"dip_pct","type":"number","required":true}]'::jsonb, ARRAY['plan_tier_eq','health_band_eq','count_gt'], ARRAY['pharmacy.id','pharmacy.usage_pct','pharmacy.health_band'], TRUE),
('ticket_created', 'SUPPORT', 'Ticket Created', 'Fires when a support ticket is created.', '[{"name":"priority","type":"string","required":false}]'::jsonb, ARRAY['priority_eq','segment_in','zone_in'], ARRAY['ticket.id','ticket.priority','ticket.category','customer.id'], TRUE),
('support_sla_breaching', 'SUPPORT', 'Support SLA Breaching', 'Fires when a support ticket is about to breach SLA.', '[{"name":"minutes_to_breach","type":"integer","required":true}]'::jsonb, ARRAY['priority_eq','time_of_day_between'], ARRAY['ticket.id','ticket.priority','ticket.sla_deadline'], TRUE),
('negative_csat', 'SUPPORT', 'Negative CSAT', 'Fires when a CSAT score is negative/low.', '[{"name":"below_score","type":"integer","required":true}]'::jsonb, ARRAY['segment_in','plan_tier_eq'], ARRAY['ticket.id','csat.score','pharmacy.id'], TRUE),
('rx_uploaded', 'COMPLIANCE', 'Rx Uploaded', 'Fires when a prescription image is uploaded.', '[]'::jsonb, ARRAY['zone_in'], ARRAY['prescription.id','order.id','customer.id'], TRUE),
('schedule_x_sale', 'COMPLIANCE', 'Schedule X Sale', 'Fires when a Schedule X medicine is sold.', '[]'::jsonb, ARRAY['zone_in','plan_tier_eq'], ARRAY['sale.id','pharmacy.id','sku.id'], TRUE),
('register_due', 'COMPLIANCE', 'Register Due', 'Fires when a compliance register submission is due.', '[{"name":"register_type","type":"string","required":true}]'::jsonb, ARRAY['plan_tier_eq','day_of_week_in'], ARRAY['pharmacy.id','register.type','register.due_at'], TRUE),
('coupon_budget_exhausted', 'GROWTH', 'Coupon Budget Exhausted', 'Fires when a coupon campaign budget is exhausted.', '[]'::jsonb, ARRAY['amount_gt','segment_in'], ARRAY['coupon.id','coupon.budget_paise','coupon.spent_paise'], TRUE),
('campaign_underperforming', 'GROWTH', 'Campaign Underperforming', 'Fires when a campaign underperforms targets.', '[{"name":"metric","type":"string","required":true},{"name":"below_value","type":"number","required":true}]'::jsonb, ARRAY['segment_in','count_gt'], ARRAY['campaign.id','campaign.metric_value','campaign.target'], TRUE),
('segment_threshold_crossed', 'GROWTH', 'Segment Threshold Crossed', 'Fires when a customer segment size crosses a threshold.', '[{"name":"segment","type":"string","required":true},{"name":"threshold","type":"integer","required":true}]'::jsonb, ARRAY['segment_in','count_gt'], ARRAY['segment.id','segment.size','segment.threshold'], TRUE);

INSERT INTO action_registry (
    action_id, category, name, description, required_params_schema,
    optional_params_schema, is_reversible, always_require_approval, auto_approval_limit_paise
) VALUES
('auto_assign_rider', 'DISPATCH', 'Auto-Assign Rider', 'Automatically assigns the best available rider to the order using the dispatch algorithm.', '["order_id"]'::jsonb, '["zone_preference","priority_override"]'::jsonb, FALSE, FALSE, NULL),
('auto_reassign_rider', 'DISPATCH', 'Auto-Reassign Rider', 'Reassigns an order to another available rider.', '["order_id"]'::jsonb, '["exclude_rider_id"]'::jsonb, FALSE, FALSE, NULL),
('release_payout', 'FINANCE', 'Release Payout', 'Releases a pending payout to a pharmacy or rider bank account.', '["entity_type","entity_id","amount_paise"]'::jsonb, '["mode"]'::jsonb, FALSE, FALSE, 5000000),
('process_refund', 'FINANCE', 'Process Refund', 'Processes a queued customer refund.', '["refund_id"]'::jsonb, '["amount_paise"]'::jsonb, FALSE, FALSE, NULL),
('send_notification', 'NOTIFICATION', 'Send Notification', 'Sends a push/SMS/email/WhatsApp notification.', '["channel","template_id","recipient_id"]'::jsonb, '["payload"]'::jsonb, FALSE, FALSE, NULL),
('escalate_ticket', 'ADMIN', 'Escalate Ticket', 'Escalates a support ticket to a higher tier.', '["ticket_id"]'::jsonb, '["tier","assignee_id"]'::jsonb, FALSE, FALSE, NULL),
('apply_wallet_credit', 'FINANCE', 'Apply Wallet Credit', 'Credits the customer wallet.', '["customer_id","amount_paise"]'::jsonb, '["reason"]'::jsonb, TRUE, FALSE, NULL),
('suspend_entity', 'ADMIN', 'Suspend Entity', 'Suspends a pharmacy or rider account.', '["entity_type","entity_id","reason"]'::jsonb, '[]'::jsonb, TRUE, TRUE, NULL),
('reactivate_entity', 'ADMIN', 'Reactivate Entity', 'Reactivates a suspended pharmacy or rider account.', '["entity_type","entity_id"]'::jsonb, '["reason"]'::jsonb, TRUE, FALSE, NULL),
('change_plan', 'CRM', 'Change Plan', 'Changes a pharmacy SaaS plan.', '["pharmacy_id","plan_id"]'::jsonb, '["effective_at"]'::jsonb, TRUE, FALSE, NULL),
('open_csm_task', 'CRM', 'Open CSM Task', 'Opens a customer-success management task.', '["pharmacy_id","title"]'::jsonb, '["priority","due_at"]'::jsonb, FALSE, FALSE, NULL),
('page_human', 'NOTIFICATION', 'Page Human', 'Pages an on-call human operator.', '["severity","message"]'::jsonb, '["channel"]'::jsonb, FALSE, FALSE, NULL),
('set_feature_flag', 'ADMIN', 'Set Feature Flag', 'Sets a feature flag for an entity or globally.', '["flag_key","enabled"]'::jsonb, '["entity_type","entity_id"]'::jsonb, TRUE, FALSE, NULL),
('update_order_status', 'DISPATCH', 'Update Order Status', 'Updates an order to a target status.', '["order_id","status"]'::jsonb, '["reason"]'::jsonb, FALSE, FALSE, NULL),
('mass_payout', 'FINANCE', 'Mass Payout', 'Triggers a mass payout batch (always requires approval).', '["cycle_id"]'::jsonb, '["entity_type"]'::jsonb, FALSE, TRUE, NULL),
('flag_prescription', 'ADMIN', 'Flag Prescription', 'Flags a prescription for compliance review (stub).', '["prescription_id"]'::jsonb, '["reason"]'::jsonb, TRUE, FALSE, NULL);

