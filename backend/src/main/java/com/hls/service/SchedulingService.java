package com.hls.service;

import com.hls.algorithm.CpSatScheduler;
import com.hls.algorithm.EnhancedGreedyScheduler;
import com.hls.algorithm.GreedyScheduler;
import com.hls.algorithm.Scheduler;
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

        // Cumulative horizon offset per day: half-hour 0 of day k =
        // sum of totalHalfHours of days 1..k-1 in horizon-relative units.
        int[] horizonOffset = new int[daySummaries.size() + 2];
        for (int i = 0; i < daySummaries.size(); i++) {
            horizonOffset[i + 1] = horizonOffset[i] + daySummaries.get(i).totalHalfHours();
        }

        // Group scheduled blocks by day, converting to day-relative half-hours.
        // We use a mutable holder so we can stamp lane assignments after packing.
        Map<Integer, List<PackingEntry>> blocksByDay = new TreeMap<>();
        for (ScheduledBlock sb : scheduled) {
            Block block = blocksById.get(sb.blockId());
            if (block == null) {
                throw new IllegalStateException(
                        "Algorithm returned unknown block id: " + sb.blockId());
            }
            int offset = horizonOffset[sb.dayIndex() - 1];
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
     * Greedy first-fit lane packing with contiguous lanes.
     *
     * <p>Sorts the day's blocks by {@code (startHalfHour ASC, fteRequirement DESC, blockId ASC)}
     * and assigns each one to the smallest lane index {@code i} such that
     * lanes {@code [i, i + fte - 1]} are all free at the block's start. This
     * is provably correct as long as the underlying scheduler respects the
     * FTE-capacity constraint at every half-hour, which all three algorithms
     * already guarantee.
     *
     * <p>If no valid lane range is found, throws — this would mean the
     * algorithm output violates the FTE constraint (a real bug worth surfacing
     * loudly rather than silently splitting blocks across non-contiguous lanes).
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
            int chosen = -1;
            outer:
            for (int i = 1; i + fte - 1 <= laneCount; i++) {
                for (int j = i; j < i + fte; j++) {
                    if (lanes[j] > e.startHalfHour) {
                        continue outer;
                    }
                }
                chosen = i;
                break;
            }
            if (chosen == -1) {
                throw new IllegalStateException(
                        "Lane packing failed for block " + e.block.id() +
                        " on day " + e.dayIndex +
                        " — algorithm output violates FTE constraint " +
                        "(needs " + fte + " contiguous free lanes at half-hour " +
                        e.startHalfHour + ", lane count is " + laneCount + ")");
            }
            e.laneStart = chosen;
            e.laneEnd = chosen + fte - 1;
            for (int j = chosen; j < chosen + fte; j++) {
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
