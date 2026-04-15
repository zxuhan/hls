package com.hls.controller;

import com.hls.controller.dto.ReloadResponse;
import com.hls.loader.BlockRepositoryProvider;
import com.hls.loader.LoaderValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint for re-reading the configured Excel file after the server has
 * already started. On any validation failure, the previously loaded valid
 * dataset is preserved (see {@link BlockRepositoryProvider#reload()}).
 */
@RestController
@RequestMapping("/api")
public class ReloadController {

    private final BlockRepositoryProvider provider;

    public ReloadController(BlockRepositoryProvider provider) {
        this.provider = provider;
    }

    @PostMapping("/reload")
    public ResponseEntity<ReloadResponse> reload() {
        try {
            provider.reload();
            int count = provider.getAllBlocks().size();
            return ResponseEntity.ok(new ReloadResponse(
                    true,
                    "Reloaded " + count + " blocks from " + provider.getFilePath(),
                    List.of(),
                    count
            ));
        } catch (LoaderValidationException e) {
            return ResponseEntity.unprocessableEntity().body(new ReloadResponse(
                    false,
                    "Reload failed; previous dataset preserved.",
                    e.getReport().getViolations(),
                    null
            ));
        }
    }
}
