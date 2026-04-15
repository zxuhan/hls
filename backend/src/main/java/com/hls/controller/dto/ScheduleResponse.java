package com.hls.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ScheduleResponse(
    boolean success,
    String errorMessage,
    Integer makespan,
    List<ScheduledBlockDto> scheduledBlocks,
    List<DaySummaryDto> daySummaries,
    Long runtimeMs,
    Integer bestBound,
    Double optimalityGap
) {}
