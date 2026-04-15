package com.hls.loader;

import com.hls.model.Block;
import java.util.List;

public interface BlockRepository {
    List<Block> getAllBlocks();
    Block getBlockById(String id);
}
