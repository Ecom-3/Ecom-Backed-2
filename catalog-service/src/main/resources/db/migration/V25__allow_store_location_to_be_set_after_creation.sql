ALTER TABLE stores
  MODIFY COLUMN latitude DECIMAL(10,7) NULL,
  MODIFY COLUMN longitude DECIMAL(10,7) NULL;

UPDATE stores
SET latitude = NULL, longitude = NULL
WHERE id NOT LIKE 'location-%'
  AND (latitude = 0 OR longitude = 0);
