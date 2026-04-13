package org.gostforge.backend.conversion;

/**
 * Explicit conversion pipelines supported by the backend.
 */
public enum ConversionChain {
    MD_TO_DOCX,
    MD_TO_DOCX_TO_PDF,
    DOCX_TO_MD;

    public static ConversionChain fromString(String value) {
        if (value == null) {
            return MD_TO_DOCX;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MD_TO_DOCX;
        }
    }

    public boolean requiresMarkdownInput() {
        return this != DOCX_TO_MD;
    }

    public boolean producesPdfResult() {
        return this == MD_TO_DOCX_TO_PDF;
    }

    public boolean producesZipResult() {
        return this == DOCX_TO_MD;
    }
}