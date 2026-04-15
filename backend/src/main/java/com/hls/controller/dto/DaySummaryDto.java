package com.hls.controller.dto;

import java.util.List;

/**
 * Per-day metadata the frontend uses to size and label the result grid.
 *
 * <ul>
 *   <li>{@code totalHalfHours} — length of the day in half-hour columns.
 *       Equals {@code max over shifts of ((startHour - 1) * 2 + durationHours * 2)}.</li>
 *   <li>{@code laneCount} — number of {@code Eng N} rows the frontend
 *       should render. Equals the peak simultaneous FTE supply on this day
 *       (cumulative over overlapping shifts).</li>
 *   <li>{@code shifts} — echo of the user's shift input for this day, so
 *       the UI can render shift backgrounds and tooltips without re-sending
 *       the request.</li>
 * </ul>
 */
public record DaySummaryDto(
    int dayIndex,
    int totalHalfHours,
    int laneCount,
    List<ShiftDto> shifts
) {}
