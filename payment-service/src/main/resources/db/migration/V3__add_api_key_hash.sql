ALTER TABLE merchants ADD COLUMN api_key_hash CHAR(64);

UPDATE merchants SET api_key_hash = encode(sha256(convert_to(api_key, 'UTF8')), 'hex');

ALTER TABLE merchants ADD CONSTRAINT uk_merchants_api_key_hash UNIQUE (api_key_hash);
