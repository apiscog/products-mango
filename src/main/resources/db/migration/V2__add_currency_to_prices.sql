ALTER TABLE prices
    ADD COLUMN currency VARCHAR(3);

UPDATE prices
SET currency = 'EUR'
WHERE currency IS NULL;

ALTER TABLE prices
    ALTER COLUMN currency SET NOT NULL;

ALTER TABLE prices
    ADD CONSTRAINT chk_prices_currency
        CHECK (currency IN ('EUR', 'USD', 'GBP', 'JPY', 'CHF'));
