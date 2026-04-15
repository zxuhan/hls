package com.hls.model;

import java.util.List;

public record ScheduleResult(
    boolean success,
    String errorMessage,
    int makespan,
    List<ScheduledBlock> scheduledBlocks,
    long runtimeMs,
    Integer bestBound,
    Double optimalityGap
) {}
