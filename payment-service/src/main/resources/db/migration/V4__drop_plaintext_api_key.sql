UPDATE merchants SET api_key_hash = encode(sha256(convert_to(api_key, 'UTF8')), 'hex')
WHERE api_key_hash IS NULL;

ALTER TABLE merchants ALTER COLUMN api_key_hash SET NOT NULL;

ALTER TABLE merchants DROP COLUMN api_key;
