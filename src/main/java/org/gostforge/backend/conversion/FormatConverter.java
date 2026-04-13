package org.gostforge.backend.conversion;

import java.util.List;
import java.util.Map;

/**
 * A single step in the conversion pipeline.
 * <p>
 * Each converter transforms data from {@link #inputFormat()} to {@link #outputFormat()}.
 * The pipeline resolver chains converters automatically based on the requested
 * {@link ConversionChain}.
 */
public interface FormatConverter {

    /** The format this converter accepts as input. */
    ConversionFormat inputFormat();

    /** The format this converter produces as output. */
    ConversionFormat outputFormat();

    /**
     * Execute the conversion.
     *
     * @param files all input files keyed by relative path (e.g. "doc.md", "img/pic.png");
     *              the converter picks the files it needs by extension/name
     * @return conversion result containing output bytes and optional warnings
     */
    ConversionResult convert(Map<String, byte[]> files);

    /**
     * Result of a single converter step.
     */
    record ConversionResult(byte[] data, List<String> warnings) {
        public ConversionResult(byte[] data) {
            this(data, List.of());
        }
    }
}
