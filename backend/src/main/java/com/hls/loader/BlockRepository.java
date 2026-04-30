package com.hls.loader;

import com.hls.model.Block;
import com.hls.model.Part;
import java.util.List;

public interface BlockRepository {
    List<Block> getAllBlocks();
    Block getBlockById(String id);

    /**
     * Parts discovered in the optional {@code Blocks_Parts} sheet, in the order
     * the part columns appear. Empty list when the sheet is missing or contains
     * no parts. Parts are a frontend-only convenience (see {@link Part}).
     */
    default List<Part> getAllParts() {
        return List.of();
    }
}
