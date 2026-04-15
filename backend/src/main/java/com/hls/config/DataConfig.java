package com.hls.config;

import com.hls.loader.BlockRepository;
import com.hls.loader.BlockRepositoryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataConfig {

    @Value("${hls.data.file}")
    private String dataFilePath;

    /**
     * Single live instance. Construction loads the workbook synchronously and
     * fails fast (server refuses to start) if the file is missing or any
     * validation rule fails — see {@link BlockRepositoryProvider}.
     */
    @Bean
    public BlockRepositoryProvider blockRepositoryProvider() {
        return new BlockRepositoryProvider(dataFilePath);
    }

    /**
     * Re-expose the provider as the {@link BlockRepository} interface so
     * services can keep depending on the read-only contract without knowing
     * about reload. Both beans return the same instance.
     */
    @Bean
    public BlockRepository blockRepository(BlockRepositoryProvider provider) {
        return provider;
    }
}
