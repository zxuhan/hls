package com.hls.controller.dto;

import java.util.List;
import java.util.Map;

public record BlockDto(
    String id,
    String name,
    int durationHalfHours,
    int fteRequirement,
    List<String> occupiedZones,
    Map<String, String> positionAxes,
    String requiredToolName,
    Boolean requiredToolExclusive,
    List<String> predecessorBlockIds,
    String colour,
    String sequenceGroup,
    Integer pinnedDay,
    Integer pinnedStartHour,
    boolean noParallel
) {}
