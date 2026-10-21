-- Demo ledger of an exporter that invoices customers in five countries and four currencies.

INSERT INTO customers (id, name, country) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Acme Robotics GmbH',          'DE'),
    ('22222222-2222-2222-2222-222222222222', 'Northwind Trading Ltd',       'GB'),
    ('33333333-3333-3333-3333-333333333333', 'Helvetia Precision AG',       'CH'),
    ('44444444-4444-4444-4444-444444444444', 'Sakura Components K.K.',      'JP'),
    ('55555555-5555-5555-5555-555555555555', 'Maple Leaf Instruments Inc.', 'CA'),
    ('66666666-6666-6666-6666-666666666666', 'Pampa Logistics S.A.',        'AR');

INSERT INTO customer_accounts (iban, customer_id) VALUES
    ('DE89370400440532013000', '11111111-1111-1111-1111-111111111111'),
    ('GB29NWBK60161331926819', '22222222-2222-2222-2222-222222222222'),
    ('CH9300762011623852957',  '33333333-3333-3333-3333-333333333333');

INSERT INTO invoices (id, number, customer_id, currency, amount, open_amount, issue_date, due_date, status) VALUES
    (gen_random_uuid(), 'INV-2026-0101', '11111111-1111-1111-1111-111111111111', 'EUR', 12400.00, 12400.00, '2026-09-15', '2026-10-15', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0110', '11111111-1111-1111-1111-111111111111', 'EUR',  3100.00,  3100.00, '2026-10-06', '2026-11-05', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0102', '22222222-2222-2222-2222-222222222222', 'GBP',  2000.00,  2000.00, '2026-09-10', '2026-10-10', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0103', '22222222-2222-2222-2222-222222222222', 'GBP',  3000.00,  3000.00, '2026-09-17', '2026-10-17', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0104', '33333333-3333-3333-3333-333333333333', 'CHF',  9380.00,  9380.00, '2026-09-18', '2026-10-18', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0111', '33333333-3333-3333-3333-333333333333', 'CHF',  2200.00,  2200.00, '2026-10-21', '2026-11-20', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0105', '44444444-4444-4444-4444-444444444444', 'USD',  3000.00,  3000.00, '2026-09-01', '2026-10-01', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0106', '44444444-4444-4444-4444-444444444444', 'USD',  4250.00,  4250.00, '2026-09-12', '2026-10-12', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0107', '44444444-4444-4444-4444-444444444444', 'USD',  1200.00,  1200.00, '2026-10-02', '2026-11-01', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0108', '55555555-5555-5555-5555-555555555555', 'USD', 15000.00, 15000.00, '2026-09-20', '2026-10-20', 'OPEN'),
    (gen_random_uuid(), 'INV-2026-0109', '66666666-6666-6666-6666-666666666666', 'USD', 48000.00, 48000.00, '2026-09-30', '2026-10-30', 'OPEN');

INSERT INTO fx_rates (currency, rate_date, per_eur)
SELECT c.currency, d::date, c.per_eur
FROM (VALUES ('USD', 1.0850), ('GBP', 0.8420), ('CHF', 0.9380), ('JPY', 162.10), ('CAD', 1.4950)) AS c(currency, per_eur)
CROSS JOIN generate_series(DATE '2026-10-12', DATE '2026-10-30', INTERVAL '1 day') AS d
WHERE extract(isodow FROM d) < 6;
