-- EPIC-005 / STORY-002: medicine_category + seed (~48 categories)
-- Rollback: DROP TABLE IF EXISTS medicine_category;
-- Notes: soft-delete via deleted_at; unique name/slug include soft-deleted rows;
--        medicine_count is computed at query time (0 until medicine_master exists in STORY-001)

CREATE TABLE medicine_category (
    id            UUID PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    slug          VARCHAR(100) NOT NULL,
    icon_url      TEXT NOT NULL,
    is_visible    BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL,
    deleted_at    TIMESTAMPTZ NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_medicine_category_name UNIQUE (name),
    CONSTRAINT uq_medicine_category_slug UNIQUE (slug)
);

CREATE INDEX idx_medicine_category_public_order
    ON medicine_category (display_order ASC)
    WHERE deleted_at IS NULL AND is_visible = TRUE;

INSERT INTO medicine_category (id, name, slug, icon_url, is_visible, display_order, created_at, updated_at)
VALUES
    ('c0000001-0000-4000-8000-000000000001', 'Antibiotics', 'antibiotics', 'https://cdn.nammamedmate.com/categories/antibiotics.svg', TRUE, 1, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000002', 'Antifungals', 'antifungals', 'https://cdn.nammamedmate.com/categories/antifungals.svg', TRUE, 2, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000003', 'Antacids', 'antacids', 'https://cdn.nammamedmate.com/categories/antacids.svg', TRUE, 3, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000004', 'Pain Relief', 'pain-relief', 'https://cdn.nammamedmate.com/categories/pain-relief.svg', TRUE, 4, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000005', 'Vitamins & Supplements', 'vitamins-supplements', 'https://cdn.nammamedmate.com/categories/vitamins-supplements.svg', TRUE, 5, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000006', 'Diabetic Care', 'diabetic-care', 'https://cdn.nammamedmate.com/categories/diabetic-care.svg', TRUE, 6, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000007', 'Blood Pressure', 'blood-pressure', 'https://cdn.nammamedmate.com/categories/blood-pressure.svg', TRUE, 7, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000008', 'Cardiac Care', 'cardiac-care', 'https://cdn.nammamedmate.com/categories/cardiac-care.svg', TRUE, 8, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000009', 'Thyroid', 'thyroid', 'https://cdn.nammamedmate.com/categories/thyroid.svg', TRUE, 9, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000000a', 'Allergy & Sinus', 'allergy-sinus', 'https://cdn.nammamedmate.com/categories/allergy-sinus.svg', TRUE, 10, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000000b', 'Skin Care', 'skin-care', 'https://cdn.nammamedmate.com/categories/skin-care.svg', TRUE, 11, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000000c', 'Eye & Ear Drops', 'eye-ear-drops', 'https://cdn.nammamedmate.com/categories/eye-ear-drops.svg', TRUE, 12, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000000d', 'Women''s Health', 'womens-health', 'https://cdn.nammamedmate.com/categories/womens-health.svg', TRUE, 13, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000000e', 'Men''s Health', 'mens-health', 'https://cdn.nammamedmate.com/categories/mens-health.svg', TRUE, 14, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000000f', 'Baby Care', 'baby-care', 'https://cdn.nammamedmate.com/categories/baby-care.svg', TRUE, 15, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000010', 'Surgical Supplies', 'surgical-supplies', 'https://cdn.nammamedmate.com/categories/surgical-supplies.svg', TRUE, 16, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000011', 'Cold & Cough', 'cold-cough', 'https://cdn.nammamedmate.com/categories/cold-cough.svg', TRUE, 17, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000012', 'Fever & Flu', 'fever-flu', 'https://cdn.nammamedmate.com/categories/fever-flu.svg', TRUE, 18, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000013', 'Digestive Health', 'digestive-health', 'https://cdn.nammamedmate.com/categories/digestive-health.svg', TRUE, 19, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000014', 'Respiratory Care', 'respiratory-care', 'https://cdn.nammamedmate.com/categories/respiratory-care.svg', TRUE, 20, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000015', 'Orthopedic Care', 'orthopedic-care', 'https://cdn.nammamedmate.com/categories/orthopedic-care.svg', TRUE, 21, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000016', 'Neurological Care', 'neurological-care', 'https://cdn.nammamedmate.com/categories/neurological-care.svg', TRUE, 22, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000017', 'Mental Wellness', 'mental-wellness', 'https://cdn.nammamedmate.com/categories/mental-wellness.svg', TRUE, 23, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000018', 'Sexual Wellness', 'sexual-wellness', 'https://cdn.nammamedmate.com/categories/sexual-wellness.svg', TRUE, 24, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000019', 'Hair Care', 'hair-care', 'https://cdn.nammamedmate.com/categories/hair-care.svg', TRUE, 25, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000001a', 'Oral Care', 'oral-care', 'https://cdn.nammamedmate.com/categories/oral-care.svg', TRUE, 26, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000001b', 'First Aid', 'first-aid', 'https://cdn.nammamedmate.com/categories/first-aid.svg', TRUE, 27, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000001c', 'Medical Devices', 'medical-devices', 'https://cdn.nammamedmate.com/categories/medical-devices.svg', TRUE, 28, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000001d', 'Ayurvedic', 'ayurvedic', 'https://cdn.nammamedmate.com/categories/ayurvedic.svg', TRUE, 29, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000001e', 'Homeopathy', 'homeopathy', 'https://cdn.nammamedmate.com/categories/homeopathy.svg', TRUE, 30, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000001f', 'Personal Care', 'personal-care', 'https://cdn.nammamedmate.com/categories/personal-care.svg', TRUE, 31, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000020', 'Immunity Boosters', 'immunity-boosters', 'https://cdn.nammamedmate.com/categories/immunity-boosters.svg', TRUE, 32, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000021', 'Liver Care', 'liver-care', 'https://cdn.nammamedmate.com/categories/liver-care.svg', TRUE, 33, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000022', 'Kidney Care', 'kidney-care', 'https://cdn.nammamedmate.com/categories/kidney-care.svg', TRUE, 34, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000023', 'Bone & Joint', 'bone-joint', 'https://cdn.nammamedmate.com/categories/bone-joint.svg', TRUE, 35, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000024', 'Weight Management', 'weight-management', 'https://cdn.nammamedmate.com/categories/weight-management.svg', TRUE, 36, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000025', 'Smoking Cessation', 'smoking-cessation', 'https://cdn.nammamedmate.com/categories/smoking-cessation.svg', TRUE, 37, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000026', 'Contraceptives', 'contraceptives', 'https://cdn.nammamedmate.com/categories/contraceptives.svg', TRUE, 38, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000027', 'Pregnancy Care', 'pregnancy-care', 'https://cdn.nammamedmate.com/categories/pregnancy-care.svg', TRUE, 39, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000028', 'Elderly Care', 'elderly-care', 'https://cdn.nammamedmate.com/categories/elderly-care.svg', TRUE, 40, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000029', 'Nutritional Drinks', 'nutritional-drinks', 'https://cdn.nammamedmate.com/categories/nutritional-drinks.svg', TRUE, 41, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000002a', 'Protein Supplements', 'protein-supplements', 'https://cdn.nammamedmate.com/categories/protein-supplements.svg', TRUE, 42, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000002b', 'Herbal Remedies', 'herbal-remedies', 'https://cdn.nammamedmate.com/categories/herbal-remedies.svg', TRUE, 43, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000002c', 'Antivirals', 'antivirals', 'https://cdn.nammamedmate.com/categories/antivirals.svg', TRUE, 44, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000002d', 'Antiparasitics', 'antiparasitics', 'https://cdn.nammamedmate.com/categories/antiparasitics.svg', TRUE, 45, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000002e', 'Vaccines & Immunization', 'vaccines-immunization', 'https://cdn.nammamedmate.com/categories/vaccines-immunization.svg', TRUE, 46, NOW(), NOW()),
    ('c0000001-0000-4000-8000-00000000002f', 'Wound Care', 'wound-care', 'https://cdn.nammamedmate.com/categories/wound-care.svg', TRUE, 47, NOW(), NOW()),
    ('c0000001-0000-4000-8000-000000000030', 'Gastrointestinal', 'gastrointestinal', 'https://cdn.nammamedmate.com/categories/gastrointestinal.svg', TRUE, 48, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
