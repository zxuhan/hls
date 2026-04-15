package com.hls.controller;

import com.hls.controller.dto.ErrorResponse;
import com.hls.controller.dto.ScheduleRequest;
import com.hls.controller.dto.ScheduleResponse;
import com.hls.service.SchedulingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ScheduleController {

    private final SchedulingService schedulingService;

    public ScheduleController(SchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    @PostMapping("/schedule")
    public ResponseEntity<?> createSchedule(@RequestBody ScheduleRequest request) {
        if (request.algorithm() == null) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(false, "Field 'algorithm' is required"));
        }
        if (request.blockIds() == null || request.blockIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(false, "Field 'blockIds' is required and must not be empty"));
        }
        if (request.shiftSchedule() == null || request.shiftSchedule().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(false, "Field 'shiftSchedule' is required and must not be empty"));
        }

        ScheduleResponse response = schedulingService.runSchedule(request);

        if (!response.success()) {
            return ResponseEntity.unprocessableEntity().body(response);
        }
        return ResponseEntity.ok(response);
    }
}
