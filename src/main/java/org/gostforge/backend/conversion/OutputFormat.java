package org.gostforge.backend.conversion;

/**
 * Requested output format for a conversion job.
 * Stored as a STRING in the database (VARCHAR column).
 */
public enum OutputFormat {
    DOCX,
    PDF,
    BOTH;

    /**
     * Parse from a string value, defaulting to DOCX for unknown inputs.
     */
    public static OutputFormat fromString(String value) {
        if (value == null) return DOCX;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DOCX;
        }
    }
}
