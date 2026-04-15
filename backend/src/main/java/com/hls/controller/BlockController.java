package com.hls.controller;

import com.hls.controller.dto.BlockListResponse;
import com.hls.service.SchedulingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BlockController {

    private final SchedulingService schedulingService;

    public BlockController(SchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    @GetMapping("/blocks")
    public ResponseEntity<BlockListResponse> getBlocks() {
        return ResponseEntity.ok(schedulingService.getAllBlocks());
    }
}
