package com.hls.controller.dto;

import com.hls.model.Algorithm;

import java.util.List;

public record ScheduleRequest(
    Algorithm algorithm,
    List<String> blockIds,
    List<ShiftDayDto> shiftSchedule,
    Integer cpSatTimeLimitSeconds,
    Double candidateCWeight
) {}
