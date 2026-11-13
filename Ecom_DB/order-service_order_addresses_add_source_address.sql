-- Run against the order-service database before deploying the selected-address reference.
ALTER TABLE order_addresses
  ADD COLUMN source_address_id VARCHAR(64) NULL;
