package com.hls.controller.dto;

import com.hls.loader.LoaderViolation;

import java.util.List;

/**
 * Result of a {@code POST /api/reload} call.
 *
 * <p>On success, {@code violations} is empty and {@code blockCount} reflects
 * the freshly loaded dataset. On failure, the previously loaded dataset is
 * left untouched, {@code blockCount} is {@code null}, and {@code violations}
 * lists every problem the loader found in the file.
 */
public record ReloadResponse(
        boolean success,
        String message,
        List<LoaderViolation> violations,
        Integer blockCount
) {}
