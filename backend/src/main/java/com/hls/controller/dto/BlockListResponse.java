package com.hls.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record BlockListResponse(
    boolean success,
    String errorMessage,
    List<BlockDto> blocks
) {}
