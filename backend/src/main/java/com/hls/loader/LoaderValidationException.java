package com.hls.loader;

/**
 * Thrown when one or more {@link LoaderViolation}s are detected while loading
 * the Excel workbook. The exception carries the full {@link LoaderValidationReport}
 * so callers (startup boot path, /api/reload endpoint, etc.) can render it
 * however they need.
 *
 * <p>The loader collects every violation in one pass before throwing — callers
 * always get the complete picture, never just the first failure.
 */
public class LoaderValidationException extends RuntimeException {

    private final LoaderValidationReport report;

    public LoaderValidationException(LoaderValidationReport report) {
        super(report.format());
        this.report = report;
    }

    public LoaderValidationException(LoaderValidationReport report, Throwable cause) {
        super(report.format(), cause);
        this.report = report;
    }

    public LoaderValidationReport getReport() {
        return report;
    }
}
