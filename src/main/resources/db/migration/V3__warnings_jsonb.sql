CREATE OR REPLACE FUNCTION gf_try_parse_jsonb(input_text TEXT)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN input_text::jsonb;
EXCEPTION WHEN others THEN
    RETURN to_jsonb(ARRAY[input_text]);
END;
$$;

ALTER TABLE conversion_jobs
    ALTER COLUMN warnings TYPE JSONB
    USING CASE
        WHEN warnings IS NULL OR btrim(warnings) = '' THEN NULL
        ELSE gf_try_parse_jsonb(warnings)
    END;

ALTER TABLE conversion_jobs
    DROP COLUMN IF EXISTS md2gost_job_id;

DROP FUNCTION gf_try_parse_jsonb(TEXT);
