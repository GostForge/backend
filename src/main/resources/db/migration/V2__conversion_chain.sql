ALTER TABLE conversion_jobs
    ADD COLUMN conversion_chain VARCHAR(20);

UPDATE conversion_jobs
SET conversion_chain = CASE UPPER(output_format)
    WHEN 'PDF' THEN 'MD_TO_DOCX_TO_PDF'
    WHEN 'BOTH' THEN 'MD_TO_DOCX_TO_PDF'
    WHEN 'MARKDOWN' THEN 'DOCX_TO_MD'
    ELSE 'MD_TO_DOCX'
END;

ALTER TABLE conversion_jobs
    ALTER COLUMN conversion_chain SET DEFAULT 'MD_TO_DOCX',
    ALTER COLUMN conversion_chain SET NOT NULL;

ALTER TABLE conversion_jobs
    DROP COLUMN output_format;
