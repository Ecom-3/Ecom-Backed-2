-- Reference the customer-owned address record while retaining immutable order snapshot fields.
ALTER TABLE order_addresses
  ADD COLUMN source_address_id VARCHAR(64) NULL;
