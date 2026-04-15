package com.hls.loader;

import com.hls.model.Block;

import java.util.List;

/**
 * Holds the live {@link BlockRepository} backing the running server and
 * supports atomic reload via {@link #reload()}.
 *
 * <p>Implements {@link BlockRepository} itself by delegating to a {@code volatile}
 * reference, so services can keep depending on the {@code BlockRepository}
 * interface without knowing about the provider. The volatile reference makes
 * a successful reload visible to in-flight scheduling threads atomically;
 * a failed reload leaves the previous valid dataset in place.
 *
 * <p>Construction performs the first load synchronously and propagates any
 * {@link LoaderValidationException} so the Spring context fails to start
 * when the configured file is missing or invalid (the fail-fast contract
 * defined in CLAUDE.md).
 */
public class BlockRepositoryProvider implements BlockRepository {

    private final String filePath;
    private volatile BlockRepository current;

    public BlockRepositoryProvider(String filePath) {
        this.filePath = filePath;
        this.current = new ExcelBlockRepository(filePath); // fail-fast on startup
    }

    /**
     * Re-read the configured file. On any validation failure, the previously
     * loaded dataset is preserved and the exception is re-thrown. Synchronized
     * so concurrent /api/reload calls are serialized; reads through
     * {@link #getAllBlocks()} / {@link #getBlockById(String)} are lock-free.
     */
    public synchronized void reload() {
        BlockRepository fresh = new ExcelBlockRepository(filePath); // throws on any violation
        this.current = fresh; // atomic swap only on success
    }

    public String getFilePath() {
        return filePath;
    }

    @Override
    public List<Block> getAllBlocks() {
        return current.getAllBlocks();
    }

    @Override
    public Block getBlockById(String id) {
        return current.getBlockById(id);
    }
}
