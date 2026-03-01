package org.gostforge.backend.conversion;

import java.util.List;
import java.util.Map;

/**
 * A single step in the conversion pipeline.
 * <p>
 * Each converter transforms data from {@link #inputFormat()} to {@link #outputFormat()}.
 * The pipeline resolver chains converters automatically based on the requested
 * {@link OutputFormat}.
 */
public interface FormatConverter {

    /** The format this converter accepts as input. */
    ConversionFormat inputFormat();

    /** The format this converter produces as output. */
    ConversionFormat outputFormat();

    /**
     * Execute the conversion.
     *
     * @param input  raw bytes of the input document
     * @param assets additional assets (images, etc.) keyed by relative path;
     *               may be empty for converters that don't need them
     * @return conversion result containing output bytes and optional warnings
     */
    ConversionResult convert(byte[] input, Map<String, byte[]> assets);

    /**
     * Result of a single converter step.
     */
    record ConversionResult(byte[] data, List<String> warnings) {
        public ConversionResult(byte[] data) {
            this(data, List.of());
        }
    }
}
