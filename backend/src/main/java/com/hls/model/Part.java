package com.hls.model;

import java.util.List;

/**
 * Immutable grouping of blocks under a user-supplied "part" name, parsed from
 * the optional {@code Blocks_Parts} sheet. Parts are a frontend-only batch-
 * select convenience: when the UI is in parts mode, the union of every
 * selected part's {@code blockIds} (deduplicated) is what gets sent to the
 * algorithm. Parts have no scheduling semantics.
 *
 * <p>{@code blockIds} preserves the row order of the {@code Blocks} sheet so
 * the frontend can render parts deterministically.
 */
public record Part(String id, List<String> blockIds) {
    public Part {
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
    }
}
