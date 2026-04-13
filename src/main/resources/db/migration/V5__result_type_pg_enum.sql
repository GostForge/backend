DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'conversion_result_type') THEN
        CREATE TYPE conversion_result_type AS ENUM ('DOCX', 'PDF', 'ZIP');
    END IF;
END $$;

ALTER TABLE conversion_jobs
    ADD COLUMN result_type_new conversion_result_type,
    ADD COLUMN result_key_new VARCHAR(500);

UPDATE conversion_jobs
SET result_type_new = CASE
        WHEN result_type IS NULL OR btrim(result_type) = '' THEN NULL
        ELSE result_type::conversion_result_type
    END,
    result_key_new = result_key;

ALTER TABLE conversion_jobs
    DROP COLUMN result_key,
    DROP COLUMN result_type;

ALTER TABLE conversion_jobs
    RENAME COLUMN result_type_new TO result_type;

ALTER TABLE conversion_jobs
    RENAME COLUMN result_key_new TO result_key;

ALTER TABLE conversion_jobs
    DROP CONSTRAINT IF EXISTS chk_conversion_jobs_result_type;
