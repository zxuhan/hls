package com.hls.controller.dto;

/**
 * Shift in the request payload, expressed in <em>relative</em> hours within
 * a day. {@code startHour} is 1-indexed (1 = first hour of the day) and
 * {@code durationHours} is the shift length in whole hours. There is no
 * clock time anywhere in this DTO — see {@code API_SPEC.md} and rule 6 in
 * {@code CLAUDE.md}.
 *
 * <p>Internally converted to half-hour units in {@code SchedulingService}:
 * {@code startHalfHour = (startHour - 1) * 2}, {@code endHalfHour = startHalfHour + durationHours * 2}.
 */
public record ShiftDto(int startHour, int durationHours, int fte) {}
