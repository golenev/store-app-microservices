ALTER TABLE IF EXISTS warehouse_product RENAME COLUMN id TO barcode_id;
ALTER TABLE IF EXISTS warehouse_product ADD COLUMN IF NOT EXISTS foodstuff boolean;
ALTER TABLE IF EXISTS warehouse_product ADD COLUMN IF NOT EXISTS arrival_time timestamptz;
ALTER TABLE IF EXISTS warehouse_product ADD COLUMN IF NOT EXISTS quantity integer;
