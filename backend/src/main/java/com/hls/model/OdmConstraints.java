package com.hls.model;

/**
 * Per-block scheduling overrides loaded from the optional {@code ODM} sheet
 * (constraints 7–9 in {@code CLAUDE.md}). A block with none of these declared
 * uses {@link #NONE}.
 *
 * <ul>
 *   <li>{@code sequenceGroup} — sequence-group label (normalized trim+lowercase),
 *       or {@code null} for no group. Blocks sharing a non-empty value form a
 *       contiguous serialised chain: they never overlap each other and run
 *       back-to-back with no idle gap, in any order. Other blocks may run in
 *       parallel during the chain.</li>
 *   <li>{@code pinnedDay} — 1-indexed calendar day the block must run on, or
 *       {@code null} when unpinned on the day axis.</li>
 *   <li>{@code pinnedStartHour} — 1-indexed hour-of-day the block must start at
 *       (hour 1 = first hour = half-hour offset 0; hour {@code h} = offset
 *       {@code (h-1)*2}), or {@code null} when unpinned on the hour axis.</li>
 *   <li>{@code noParallel} — when {@code true} the block is globally exclusive
 *       and may not overlap any other block ({@code Parallelism = No}).</li>
 * </ul>
 */
public record OdmConstraints(
    String sequenceGroup,
    Integer pinnedDay,
    Integer pinnedStartHour,
    boolean noParallel
) {
    /** Shared instance for blocks that declare nothing in the ODM sheet. */
    public static final OdmConstraints NONE = new OdmConstraints(null, null, null, false);

    public OdmConstraints {
        sequenceGroup = (sequenceGroup == null || sequenceGroup.isBlank()) ? null : sequenceGroup;
    }

    public boolean hasSequenceGroup() {
        return sequenceGroup != null;
    }

    public boolean hasDayPin() {
        return pinnedDay != null;
    }

    public boolean hasHourPin() {
        return pinnedStartHour != null;
    }

    /**
     * Human-readable rendering of the calendar pin for error messages, e.g.
     * {@code "Day 3, Hour_Start 11"}, or {@code null} when the block is
     * unpinned. Callers use the null case to fall back to a generic message.
     */
    public String describePin() {
        if (!hasDayPin() && !hasHourPin()) return null;
        StringBuilder sb = new StringBuilder();
        if (hasDayPin()) sb.append("Day ").append(pinnedDay);
        if (hasDayPin() && hasHourPin()) sb.append(", ");
        if (hasHourPin()) sb.append("Hour_Start ").append(pinnedStartHour);
        return sb.toString();
    }
}
