package com.hls.controller.dto;

/**
 * One scheduled block in the response, fully denormalized for direct
 * rendering by the frontend grid (no joins against {@code /api/blocks}
 * required).
 *
 * <ul>
 *   <li>{@code startHalfHour} / {@code endHalfHour} are 0-indexed half-hour
 *       offsets <em>relative to the start of the day</em>. End is exclusive.</li>
 *   <li>{@code laneStart} / {@code laneEnd} are 1-indexed engineer-lane
 *       indices. The block occupies the contiguous lane range
 *       {@code [laneStart, laneEnd]}; {@code laneEnd - laneStart + 1 == fteRequirement}.
 *       Lanes are a visualization layer assigned by {@link com.hls.service.SchedulingService}
 *       after the algorithm finishes — they are slots, not real engineers.</li>
 * </ul>
 */
public record ScheduledBlockDto(
    String blockId,
    String name,
    int fteRequirement,
    int dayIndex,
    int startHalfHour,
    int endHalfHour,
    int laneStart,
    int laneEnd,
    String colour
) {}
