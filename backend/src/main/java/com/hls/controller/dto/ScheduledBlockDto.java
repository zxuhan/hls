package com.hls.controller.dto;

/**
 * One scheduled block in the response, fully denormalized for direct
 * rendering by the frontend grid (no joins against {@code /api/blocks}
 * required).
 *
 * <ul>
 *   <li>{@code startHalfHour} / {@code endHalfHour} are 0-indexed half-hour
 *       offsets <em>relative to the start of the day</em>. End is exclusive.</li>
 *   <li>{@code lanes} is the 1-indexed list of engineer-lane indices the
 *       block occupies, sorted ascending. {@code lanes.length == fteRequirement}.
 *       <em>May be non-contiguous</em> when contiguous packing was not
 *       feasible (e.g. {@code [3, 6]} with lanes 4–5 taken by another block);
 *       the frontend should render one rectangle per maximal contiguous run.
 *       Lanes are a visualization layer assigned by
 *       {@link com.hls.service.SchedulingService} after the algorithm
 *       finishes — they are slots, not real engineers.</li>
 * </ul>
 */
public record ScheduledBlockDto(
    String blockId,
    String name,
    int fteRequirement,
    int dayIndex,
    int startHalfHour,
    int endHalfHour,
    int[] lanes,
    String colour
) {}
