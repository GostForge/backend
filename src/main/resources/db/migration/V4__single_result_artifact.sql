ALTER TABLE conversion_jobs
    ADD COLUMN result_key VARCHAR(500),
    ADD COLUMN result_type VARCHAR(20);

UPDATE conversion_jobs
SET result_key = CASE
        WHEN conversion_chain = 'DOCX_TO_MD' THEN merged_md_key
        WHEN conversion_chain = 'MD_TO_DOCX_TO_PDF' THEN COALESCE(pdf_key, docx_key)
        ELSE COALESCE(docx_key, pdf_key, merged_md_key)
    END,
    result_type = CASE
        WHEN conversion_chain = 'DOCX_TO_MD' AND merged_md_key IS NOT NULL THEN 'ZIP'
        WHEN conversion_chain = 'MD_TO_DOCX_TO_PDF' AND pdf_key IS NOT NULL THEN 'PDF'
        WHEN conversion_chain = 'MD_TO_DOCX_TO_PDF' AND pdf_key IS NULL AND docx_key IS NOT NULL THEN 'DOCX'
        WHEN docx_key IS NOT NULL THEN 'DOCX'
        WHEN pdf_key IS NOT NULL THEN 'PDF'
        WHEN merged_md_key IS NOT NULL THEN 'ZIP'
        ELSE NULL
    END
WHERE result_key IS NULL OR result_type IS NULL;

ALTER TABLE conversion_jobs
    ADD CONSTRAINT chk_conversion_jobs_result_type
    CHECK (result_type IS NULL OR result_type IN ('DOCX', 'PDF', 'ZIP'));

ALTER TABLE conversion_jobs
    DROP COLUMN merged_md_key,
    DROP COLUMN docx_key,
    DROP COLUMN pdf_key;
