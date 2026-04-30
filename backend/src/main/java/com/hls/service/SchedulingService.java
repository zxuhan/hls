package com.hls.service;

import com.hls.algorithm.CpSatScheduler;
import com.hls.algorithm.EnhancedGreedyScheduler;
import com.hls.algorithm.GreedyScheduler;
import com.hls.algorithm.Scheduler;
import com.hls.algorithm.TimelineHelper;
import com.hls.controller.dto.*;
import com.hls.loader.BlockRepository;
import com.hls.model.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SchedulingService {

    private static final Set<Double> VALID_WEIGHTS = Set.of(0.0, 0.25, 0.5, 0.75, 1.0);

    private final BlockRepository blockRepository;
    private final Scheduler greedyScheduler = new GreedyScheduler();
    private final Scheduler cpSatScheduler = new CpSatScheduler();
    private final Scheduler enhancedGreedyScheduler = new EnhancedGreedyScheduler();

    public SchedulingService(BlockRepository blockRepository) {
        this.blockRepository = blockRepository;
    }

    public BlockListResponse getAllBlocks() {
        List<Block> blocks = blockRepository.getAllBlocks();
        List<BlockDto> dtos = blocks.stream().map(this::toBlockDto).toList();
        return new BlockListResponse(true, null, dtos);
    }

    public ScheduleResponse runSchedule(ScheduleRequest request) {
        // Validate block IDs
        List<Block> blocks = new ArrayList<>();
        for (String id : request.blockIds()) {
            Block block = blockRepository.getBlockById(id);
            if (block == null) {
                return errorResponse("Unknown block ID in request: " + id);
            }
            blocks.add(block);
        }

        // Validate Candidate C weight
        if (request.algorithm() == Algorithm.C_ENHANCED) {
            double w = request.candidateCWeight() != null ? request.candidateCWeight() : 0.5;
            if (!VALID_WEIGHTS.contains(w)) {
                return errorResponse("candidateCWeight must be one of: 0, 0.25, 0.5, 0.75, 1.0");
            }
        }

        // Convert shift schedule from DTOs to internal model
        List<ShiftDay> shiftSchedule = request.shiftSchedule().stream()
                .map(this::toShiftDay)
                .toList();

        // Build params
        Map<String, Object> params = new HashMap<>();
        switch (request.algorithm()) {
            case A_MTS -> params.put("priorityRule", "MTS");
            case A_SPT -> params.put("priorityRule", "SPT");
            case B_CPSAT -> params.put("cpSatTimeLimitSeconds",
                    request.cpSatTimeLimitSeconds() != null ? request.cpSatTimeLimitSeconds() : 60);
            case C_ENHANCED -> params.put("candidateCWeight",
                    request.candidateCWeight() != null ? request.candidateCWeight() : 0.5);
        }

        // Optional warm-start: run Candidate C first and pass its start-time
        // assignments to CP-SAT as solver hints. Only meaningful when the
        // primary algorithm is B_CPSAT.
        if (Boolean.TRUE.equals(request.warmStart()) && request.algorithm() == Algorithm.B_CPSAT) {
            Map<String, Object> warmParams = new HashMap<>();
            warmParams.put("candidateCWeight",
                    request.candidateCWeight() != null ? request.candidateCWeight() : 0.0);
            ScheduleResult warm = enhancedGreedyScheduler.schedule(blocks, shiftSchedule, warmParams);
            if (warm.success() && warm.scheduledBlocks() != null) {
                Map<String, Integer> hints = new HashMap<>();
                for (ScheduledBlock sb : warm.scheduledBlocks()) {
                    hints.put(sb.blockId(), sb.startTime());
                }
                params.put("warmStartHints", hints);
            }
        }

        // Select algorithm and run
        Scheduler scheduler = switch (request.algorithm()) {
            case A_MTS, A_SPT -> greedyScheduler;
            case B_CPSAT -> cpSatScheduler;
            case C_ENHANCED -> enhancedGreedyScheduler;
        };

        ScheduleResult result = scheduler.schedule(blocks, shiftSchedule, params);

        if (!result.success()) {
            return new ScheduleResponse(
                    false,
                    result.errorMessage(),
                    null,
                    null,
                    null,
                    result.runtimeMs(),
                    result.bestBound(),
                    result.optimalityGap()
            );
        }

        // Post-process the algorithm output for the frontend grid:
        //   1. Compute per-day metadata (length, peak FTE, echo of shifts).
        //   2. Convert horizon-relative half-hour timestamps to day-relative.
        //   3. Pack each block into contiguous engineer lanes.
        //   4. Denormalize block name + FTE for direct rendering.
        Map<String, Block> blocksById = new HashMap<>();
        for (Block b : blocks) {
            blocksById.put(b.id(), b);
        }
        List<DaySummaryDto> daySummaries = computeDaySummaries(request.shiftSchedule(), shiftSchedule);
        List<ScheduledBlockDto> scheduledDtos = buildScheduledBlockDtos(
                result.scheduledBlocks(), blocksById, daySummaries);

        return new ScheduleResponse(
                true,
                null,
                result.makespan(),
                scheduledDtos,
                daySummaries,
                result.runtimeMs(),
                result.bestBound(),
                result.optimalityGap()
        );
    }

    // ─── Conversion: request DTO → internal model ────────────────────────

    ShiftDay toShiftDay(ShiftDayDto dto) {
        List<Shift> shifts = dto.shifts().stream()
                .map(s -> new Shift(
                        (s.startHour() - 1) * 2,
                        (s.startHour() - 1) * 2 + s.durationHours() * 2,
                        s.fte()))
                .toList();
        return new ShiftDay(shifts);
    }

    // ─── Post-processing: day summaries ──────────────────────────────────

    /**
     * One {@link DaySummaryDto} per calendar day, in order. {@code totalHalfHours}
     * is the maximum shift end on that day; {@code laneCount} is the peak
     * cumulative FTE supply across all half-hour slots on that day.
     */
    List<DaySummaryDto> computeDaySummaries(
            List<ShiftDayDto> requestDays, List<ShiftDay> internalDays) {
        List<DaySummaryDto> summaries = new ArrayList<>(internalDays.size());
        for (int i = 0; i < internalDays.size(); i++) {
            ShiftDay day = internalDays.get(i);
            int totalHalfHours = 0;
            for (Shift s : day.shifts()) {
                if (s.endHalfHour() > totalHalfHours) {
                    totalHalfHours = s.endHalfHour();
                }
            }
            int laneCount = peakFteSupply(day, totalHalfHours);
            summaries.add(new DaySummaryDto(
                    i + 1,
                    totalHalfHours,
                    laneCount,
                    requestDays.get(i).shifts()
            ));
        }
        return summaries;
    }

    /**
     * Peak simultaneous FTE supply across all half-hour slots in
     * {@code [0, totalHalfHours)}, summing FTE of all active shifts at each
     * slot. Returns 0 if the day has no shifts.
     */
    private int peakFteSupply(ShiftDay day, int totalHalfHours) {
        int peak = 0;
        for (int t = 0; t < totalHalfHours; t++) {
            int supply = 0;
            for (Shift s : day.shifts()) {
                if (t >= s.startHalfHour() && t < s.endHalfHour()) {
                    supply += s.fte();
                }
            }
            if (supply > peak) {
                peak = supply;
            }
        }
        return peak;
    }

    // ─── Post-processing: scheduled blocks → DTOs with lane assignment ──

    /**
     * Convert each {@link ScheduledBlock} to a {@link ScheduledBlockDto} with
     * day-relative half-hour offsets, the block name + FTE denormalized from
     * the repository, and a contiguous engineer-lane assignment computed by
     * {@link #assignLanesForDay(List, int)}.
     */
    List<ScheduledBlockDto> buildScheduledBlockDtos(
            List<ScheduledBlock> scheduled,
            Map<String, Block> blocksById,
            List<DaySummaryDto> daySummaries) {

        // Group scheduled blocks by day, converting to day-relative half-hours.
        // The algorithm's absolute timeline uses TimelineHelper.SLOTS_PER_DAY
        // slots per calendar day (a fixed 48 = 24 hours × 2), independent of
        // the shift's actual duration. So day k starts at absolute time
        // (k - 1) * SLOTS_PER_DAY — NOT at sum of prior totalHalfHours.
        Map<Integer, List<PackingEntry>> blocksByDay = new TreeMap<>();
        for (ScheduledBlock sb : scheduled) {
            Block block = blocksById.get(sb.blockId());
            if (block == null) {
                throw new IllegalStateException(
                        "Algorithm returned unknown block id: " + sb.blockId());
            }
            int offset = (sb.dayIndex() - 1) * TimelineHelper.SLOTS_PER_DAY;
            int startHalfHour = sb.startTime() - offset;
            int endHalfHour = sb.endTime() - offset;
            blocksByDay.computeIfAbsent(sb.dayIndex(), k -> new ArrayList<>())
                    .add(new PackingEntry(block, sb.dayIndex(), startHalfHour, endHalfHour));
        }

        // Pack each day's blocks into contiguous lanes.
        for (Map.Entry<Integer, List<PackingEntry>> entry : blocksByDay.entrySet()) {
            int laneCount = daySummaries.get(entry.getKey() - 1).laneCount();
            assignLanesForDay(entry.getValue(), laneCount);
        }

        // Flatten in original algorithm order (preserves intent) and emit DTOs.
        List<ScheduledBlockDto> dtos = new ArrayList<>(scheduled.size());
        Map<String, PackingEntry> entryByBlockId = new HashMap<>();
        for (List<PackingEntry> list : blocksByDay.values()) {
            for (PackingEntry e : list) {
                entryByBlockId.put(e.block.id() + "@" + e.dayIndex, e);
            }
        }
        for (ScheduledBlock sb : scheduled) {
            PackingEntry e = entryByBlockId.get(sb.blockId() + "@" + sb.dayIndex());
            dtos.add(new ScheduledBlockDto(
                    e.block.id(),
                    e.block.name(),
                    e.block.fteRequirement(),
                    e.dayIndex,
                    e.startHalfHour,
                    e.endHalfHour,
                    e.laneStart,
                    e.laneEnd,
                    e.block.colour()
            ));
        }
        return dtos;
    }

    /**
     * Greedy lane packing. Tries contiguous {@code [laneStart..laneEnd]}
     * first (ideal for the rendering grid); if first-fit can't find a
     * contiguous range, falls back to any {@code fte} free lanes — the block
     * still uses exactly {@code fte} lanes, but those lanes may be
     * non-adjacent. {@code laneStart}/{@code laneEnd} then hold the min/max
     * of the assigned lanes, with possible gaps inside the range.
     *
     * <p>Contiguity is a display-grid property, not a scheduling constraint
     * (the six hard constraints from CLAUDE.md make no such requirement).
     * First-fit contiguous packing fragments under realistic concurrent
     * block patterns even when FTE-sum ≤ capacity holds at every
     * half-hour — e.g. lanes 3 and 6 free but lanes 4–5 still occupied.
     * Rather than reject a valid schedule, we emit non-contiguous lanes and
     * keep going.
     *
     * <p>Only throws if fewer than {@code fte} lanes are free at the
     * block's start time, which does indicate a real FTE-capacity
     * violation in the algorithm output.
     */
    private void assignLanesForDay(List<PackingEntry> dayBlocks, int laneCount) {
        // lanes[i] holds the next half-hour at which lane i becomes free; 1-indexed.
        int[] lanes = new int[laneCount + 1];
        // Sort by (start ASC, fte DESC, id ASC) for deterministic, packing-friendly order.
        dayBlocks.sort((a, b) -> {
            if (a.startHalfHour != b.startHalfHour) {
                return Integer.compare(a.startHalfHour, b.startHalfHour);
            }
            int fteCmp = Integer.compare(b.block.fteRequirement(), a.block.fteRequirement());
            if (fteCmp != 0) return fteCmp;
            return a.block.id().compareTo(b.block.id());
        });

        for (PackingEntry e : dayBlocks) {
            int fte = e.block.fteRequirement();

            // Try contiguous first.
            int contiguousStart = -1;
            outer:
            for (int i = 1; i + fte - 1 <= laneCount; i++) {
                for (int j = i; j < i + fte; j++) {
                    if (lanes[j] > e.startHalfHour) {
                        continue outer;
                    }
                }
                contiguousStart = i;
                break;
            }

            int[] assigned;
            if (contiguousStart >= 0) {
                assigned = new int[fte];
                for (int k = 0; k < fte; k++) {
                    assigned[k] = contiguousStart + k;
                }
            } else {
                // Fall back to any fte free lanes.
                assigned = new int[fte];
                int n = 0;
                for (int i = 1; i <= laneCount && n < fte; i++) {
                    if (lanes[i] <= e.startHalfHour) {
                        assigned[n++] = i;
                    }
                }
                if (n < fte) {
                    throw new IllegalStateException(
                            "Lane packing failed for block " + e.block.id() +
                            " on day " + e.dayIndex +
                            " — only " + n + " free lanes at half-hour " +
                            e.startHalfHour + " but " + fte + " required " +
                            "(lane count is " + laneCount + ")");
                }
            }

            e.laneStart = assigned[0];
            e.laneEnd = assigned[fte - 1];
            for (int j : assigned) {
                lanes[j] = e.endHalfHour;
            }
        }
    }

    /** Mutable holder used during lane packing. */
    private static final class PackingEntry {
        final Block block;
        final int dayIndex;
        final int startHalfHour;
        final int endHalfHour;
        int laneStart;
        int laneEnd;

        PackingEntry(Block block, int dayIndex, int startHalfHour, int endHalfHour) {
            this.block = block;
            this.dayIndex = dayIndex;
            this.startHalfHour = startHalfHour;
            this.endHalfHour = endHalfHour;
        }
    }

    // ─── Misc helpers ────────────────────────────────────────────────────

    private BlockDto toBlockDto(Block block) {
        ToolRequirement tool = block.requiredTool();
        return new BlockDto(
                block.id(),
                block.name(),
                block.durationHalfHours(),
                block.fteRequirement(),
                List.copyOf(block.occupiedZones()),
                block.positionAxes(),
                tool == null ? null : tool.toolName(),
                tool == null ? null : tool.exclusive(),
                block.predecessorBlockIds(),
                block.colour()
        );
    }

    private ScheduleResponse errorResponse(String message) {
        return new ScheduleResponse(false, message, null, null, null, null, null, null);
    }
}
