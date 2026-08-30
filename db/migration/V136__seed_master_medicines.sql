-- Seed a small India OTC/generic master so pharmacy catalogue search is usable.
-- Rollback: DELETE FROM medicine_master WHERE id BETWEEN
--           'a0000001-0000-4000-8000-000000000001' AND
--           'a0000001-0000-4000-8000-00000000000c';
-- Notes: categories/HSN already seeded in V022/V023. Identity unique is
--        (salt_composition, manufacturer, form, pack_size, pack_unit).

INSERT INTO medicine_master (
  id, name, salt_composition, manufacturer, category_id, form, pack_size,
  pack_unit, schedule, hsn_code, gst_pct, mrp_paise, mrp_ceiling_paise,
  is_rx_only, is_banned, ban_reason, monthly_demand, mapped_pharmacy_count,
  substitutes, description, created_by, created_at, updated_at
) VALUES
  (
    'a0000001-0000-4000-8000-000000000001',
    'Crocin 500mg Tablet',
    'Paracetamol (500mg)',
    'GSK',
    'c0000001-0000-4000-8000-000000000004',
    'TABLET', 15, 'TABLET', 'OTC', '30049029', 12, 3000, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'OTC paracetamol 500mg',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000002',
    'Paracetamol 500mg Tablet',
    'Paracetamol (500mg)',
    'Generic Labs',
    'c0000001-0000-4000-8000-000000000004',
    'TABLET', 10, 'TABLET', 'OTC', '30049029', 12, 1800, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Generic paracetamol 500mg',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000003',
    'Dolo 650 Tablet',
    'Paracetamol (650mg)',
    'Micro Labs',
    'c0000001-0000-4000-8000-000000000004',
    'TABLET', 15, 'TABLET', 'OTC', '30049029', 12, 3200, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'OTC paracetamol 650mg',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000004',
    'Calpol 500mg Tablet',
    'Paracetamol (500mg)',
    'GSK Consumer',
    'c0000001-0000-4000-8000-000000000012',
    'TABLET', 16, 'TABLET', 'OTC', '30049029', 12, 2800, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Fever and flu paracetamol',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000005',
    'Cetirizine 10mg Tablet',
    'Cetirizine (10mg)',
    'Cipla',
    'c0000001-0000-4000-8000-00000000000a',
    'TABLET', 10, 'TABLET', 'OTC', '30049029', 12, 2200, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Allergy antihistamine',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000006',
    'Vitamin C 500mg Tablet',
    'Ascorbic acid (500mg)',
    'Abbott',
    'c0000001-0000-4000-8000-000000000005',
    'TABLET', 15, 'TABLET', 'OTC', '30045090', 12, 4500, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Vitamin C supplement',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000007',
    'Combiflam Tablet',
    'Ibuprofen (400mg) + Paracetamol (325mg)',
    'Sanofi',
    'c0000001-0000-4000-8000-000000000004',
    'TABLET', 20, 'TABLET', 'OTC', '30049029', 12, 4800, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Ibuprofen paracetamol combination',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000008',
    'Pantoprazole 40mg Tablet',
    'Pantoprazole (40mg)',
    'Sun Pharma',
    'c0000001-0000-4000-8000-000000000003',
    'TABLET', 15, 'TABLET', 'H', '30049039', 12, 8500, NULL,
    TRUE, FALSE, NULL, 0, 0, '{}',
    'Proton pump inhibitor',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-000000000009',
    'Azithromycin 500mg Tablet',
    'Azithromycin (500mg)',
    'Cipla Antibiotics',
    'c0000001-0000-4000-8000-000000000001',
    'TABLET', 3, 'TABLET', 'H', '30042019', 12, 9900, NULL,
    TRUE, FALSE, NULL, 0, 0, '{}',
    'Macrolide antibiotic',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-00000000000a',
    'ORS Powder',
    'Oral rehydration salts',
    'WHO Generic',
    'c0000001-0000-4000-8000-000000000013',
    'POWDER', 21.80, 'SACHET', 'OTC', '30049069', 12, 1500, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Oral rehydration sachet',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-00000000000b',
    'Dolo 650 Oral Suspension',
    'Paracetamol (250mg/5ml)',
    'Micro Labs Paediatric',
    'c0000001-0000-4000-8000-000000000012',
    'SYRUP', 60, 'ML', 'OTC', '30049029', 12, 4200, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Paediatric paracetamol syrup',
    NULL, NOW(), NOW()
  ),
  (
    'a0000001-0000-4000-8000-00000000000c',
    'Crocin Advance Tablet',
    'Paracetamol (500mg) + Caffeine',
    'Haleon',
    'c0000001-0000-4000-8000-000000000004',
    'TABLET', 15, 'TABLET', 'OTC', '30049029', 12, 3600, NULL,
    FALSE, FALSE, NULL, 0, 0, '{}',
    'Crocin advance paracetamol',
    NULL, NOW(), NOW()
  )
ON CONFLICT (id) DO NOTHING;
