CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE customers (
    id      UUID PRIMARY KEY,
    name    TEXT    NOT NULL,
    country CHAR(2) NOT NULL
);
CREATE INDEX customers_name_trgm ON customers USING gin (name gin_trgm_ops);

CREATE TABLE customer_accounts (
    iban        TEXT PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers (id)
);

CREATE TABLE invoices (
    id          UUID PRIMARY KEY,
    number      TEXT           NOT NULL UNIQUE,
    customer_id UUID           NOT NULL REFERENCES customers (id),
    currency    CHAR(3)        NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    open_amount NUMERIC(19, 2) NOT NULL CHECK (open_amount >= 0 AND open_amount <= amount),
    issue_date  DATE           NOT NULL,
    due_date    DATE           NOT NULL,
    status      TEXT           NOT NULL CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'PAID')),
    version     BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX invoices_customer_open ON invoices (customer_id, due_date) WHERE status <> 'PAID';

-- ECB-style reference rates: units of `currency` per 1 EUR.
CREATE TABLE fx_rates (
    currency  CHAR(3)        NOT NULL,
    rate_date DATE           NOT NULL,
    per_eur   NUMERIC(19, 8) NOT NULL CHECK (per_eur > 0),
    PRIMARY KEY (currency, rate_date)
);

CREATE TABLE bank_transactions (
    id                  UUID PRIMARY KEY,
    bank_reference      TEXT           NOT NULL UNIQUE,
    end_to_end_id       TEXT           NOT NULL,
    credited_amount     NUMERIC(19, 2) NOT NULL,
    credited_currency   CHAR(3)        NOT NULL,
    instructed_amount   NUMERIC(19, 2),
    instructed_currency CHAR(3),
    charges_amount      NUMERIC(19, 2),
    charges_currency    CHAR(3),
    charge_bearer       TEXT           NOT NULL,
    value_date          DATE           NOT NULL,
    debtor_name         TEXT           NOT NULL,
    debtor_iban         TEXT           NOT NULL,
    remittance_info     TEXT           NOT NULL,
    status              TEXT           NOT NULL
        CHECK (status IN ('RECEIVED', 'POSTED', 'IN_REVIEW', 'UNMATCHED')),
    received_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    id             UUID PRIMARY KEY,
    transaction_id UUID        NOT NULL UNIQUE REFERENCES bank_transactions (id),
    source         TEXT        NOT NULL,
    posted_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_lines (
    id        BIGSERIAL PRIMARY KEY,
    entry_id  UUID           NOT NULL REFERENCES journal_entries (id),
    account   TEXT           NOT NULL,
    side      TEXT           NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount    NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency  CHAR(3)        NOT NULL,
    reference TEXT           NOT NULL
);

CREATE TABLE review_tasks (
    id                UUID PRIMARY KEY,
    transaction_id    UUID        NOT NULL REFERENCES bank_transactions (id),
    reason            TEXT        NOT NULL,
    proposed_invoices TEXT[]      NOT NULL DEFAULT '{}',
    agent_confidence  DOUBLE PRECISION,
    agent_reasoning   TEXT,
    status            TEXT        NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED')),
    decided_by        TEXT,
    decided_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_open_review_per_transaction ON review_tasks (transaction_id) WHERE status = 'OPEN';
