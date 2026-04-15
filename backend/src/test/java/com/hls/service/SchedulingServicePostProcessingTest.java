package com.hls.service;

import com.hls.controller.dto.DaySummaryDto;
import com.hls.controller.dto.ScheduledBlockDto;
import com.hls.controller.dto.ShiftDayDto;
import com.hls.controller.dto.ShiftDto;
import com.hls.loader.BlockRepository;
import com.hls.model.Block;
import com.hls.model.ScheduledBlock;
import com.hls.model.ShiftDay;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the post-processing the service runs on top of an algorithm's
 * output: shift conversion, day-summary computation, day-relative time
 * conversion, and contiguous lane packing.
 *
 * <p>These tests construct {@link SchedulingService} with a tiny in-memory
 * fake of {@link BlockRepository} so they don't need a real Excel workbook
 * or Spring context.
 */
class SchedulingServicePostProcessingTest {

    private final SchedulingService service =
            new SchedulingService(new InMemoryBlockRepository(List.of()));

    // ─── toShiftDay ──────────────────────────────────────────────────────

    @Test
    void toShiftDay_convertsRelativeHoursToZeroIndexedHalfHours() {
        ShiftDayDto dto = new ShiftDayDto(List.of(
                new ShiftDto(1, 10, 3),
                new ShiftDto(5, 10, 2)
        ));

        ShiftDay day = service.toShiftDay(dto);

        // Shift 1 starts at hour 1 (= half-hour 0) and runs 10h (= 20 half-hours).
        assertThat(day.shifts().get(0).startHalfHour()).isZero();
        assertThat(day.shifts().get(0).endHalfHour()).isEqualTo(20);
        assertThat(day.shifts().get(0).fte()).isEqualTo(3);

        // Shift 2 starts at hour 5 (= half-hour 8) and runs 10h, ending at half-hour 28.
        assertThat(day.shifts().get(1).startHalfHour()).isEqualTo(8);
        assertThat(day.shifts().get(1).endHalfHour()).isEqualTo(28);
        assertThat(day.shifts().get(1).fte()).isEqualTo(2);
    }

    // ─── computeDaySummaries (eyeball-check from the plan) ───────────────

    @Test
    void computeDaySummaries_overlappingShifts_peakFteIsSumDuringOverlap() {
        ShiftDayDto dayDto = new ShiftDayDto(List.of(
                new ShiftDto(1, 10, 3),
                new ShiftDto(5, 10, 2)
        ));
        ShiftDay day = service.toShiftDay(dayDto);

        List<DaySummaryDto> summaries = service.computeDaySummaries(
                List.of(dayDto), List.of(day));

        assertThat(summaries).hasSize(1);
        DaySummaryDto s = summaries.get(0);
        assertThat(s.dayIndex()).isEqualTo(1);
        // max(20, 28) = 28
        assertThat(s.totalHalfHours()).isEqualTo(28);
        // Peak supply during the overlap window [8, 20) = 3 + 2 = 5
        assertThat(s.laneCount()).isEqualTo(5);
        // Shifts are echoed back unchanged for the frontend.
        assertThat(s.shifts()).containsExactly(
                new ShiftDto(1, 10, 3),
                new ShiftDto(5, 10, 2)
        );
    }

    @Test
    void computeDaySummaries_singleShift_laneCountEqualsShiftFte() {
        ShiftDayDto dayDto = new ShiftDayDto(List.of(new ShiftDto(1, 14, 6)));
        ShiftDay day = service.toShiftDay(dayDto);

        DaySummaryDto s = service.computeDaySummaries(List.of(dayDto), List.of(day)).get(0);

        assertThat(s.totalHalfHours()).isEqualTo(28);
        assertThat(s.laneCount()).isEqualTo(6);
    }

    // ─── buildScheduledBlockDtos: lane packing + day-relative times ─────

    @Test
    void buildScheduledBlockDtos_horizonRelativeTimesBecomeDayRelative() {
        // Two days, each 14 hours long with 5 FTE — same as the API_SPEC examples.
        ShiftDayDto dayDto = new ShiftDayDto(List.of(new ShiftDto(1, 14, 5)));
        List<DaySummaryDto> summaries = service.computeDaySummaries(
                List.of(dayDto, dayDto),
                List.of(service.toShiftDay(dayDto), service.toShiftDay(dayDto))
        );

        Block blk1 = simpleBlock("BLK-001", "Remove optics", 6, 3);
        Block blk2 = simpleBlock("BLK-002", "Install laser", 10, 2);
        Block blk3 = simpleBlock("BLK-003", "Calibration sweep", 4, 1);

        // Algorithm output is HORIZON-relative: BLK-002 on day 2 starts at half-hour 28.
        List<ScheduledBlock> scheduled = List.of(
                new ScheduledBlock("BLK-001", 0, 6, 1),
                new ScheduledBlock("BLK-003", 6, 10, 1),
                new ScheduledBlock("BLK-002", 28, 38, 2)
        );

        List<ScheduledBlockDto> dtos = service.buildScheduledBlockDtos(
                scheduled,
                Map.of(blk1.id(), blk1, blk2.id(), blk2, blk3.id(), blk3),
                summaries
        );

        // Same order as the algorithm output.
        assertThat(dtos).extracting(ScheduledBlockDto::blockId)
                .containsExactly("BLK-001", "BLK-003", "BLK-002");

        ScheduledBlockDto d1 = dtos.get(0);
        assertThat(d1.dayIndex()).isEqualTo(1);
        assertThat(d1.startHalfHour()).isZero();
        assertThat(d1.endHalfHour()).isEqualTo(6);
        assertThat(d1.fteRequirement()).isEqualTo(3);
        assertThat(d1.name()).isEqualTo("Remove optics");

        ScheduledBlockDto d3 = dtos.get(1);
        assertThat(d3.startHalfHour()).isEqualTo(6);
        assertThat(d3.endHalfHour()).isEqualTo(10);

        // BLK-002 on day 2: horizon offset 28 → day-relative start 0.
        ScheduledBlockDto d2 = dtos.get(2);
        assertThat(d2.dayIndex()).isEqualTo(2);
        assertThat(d2.startHalfHour()).isZero();
        assertThat(d2.endHalfHour()).isEqualTo(10);
    }

    @Test
    void buildScheduledBlockDtos_assignsContiguousLanes() {
        // One day, 14h × 5 FTE. Three concurrent blocks: 3 FTE + 1 FTE + 1 FTE = 5.
        ShiftDayDto dayDto = new ShiftDayDto(List.of(new ShiftDto(1, 14, 5)));
        List<DaySummaryDto> summaries = service.computeDaySummaries(
                List.of(dayDto), List.of(service.toShiftDay(dayDto)));

        Block big = simpleBlock("BIG", "Big", 10, 3);
        Block small1 = simpleBlock("S1", "Small 1", 10, 1);
        Block small2 = simpleBlock("S2", "Small 2", 10, 1);

        List<ScheduledBlock> scheduled = List.of(
                new ScheduledBlock("BIG", 0, 10, 1),
                new ScheduledBlock("S1", 0, 10, 1),
                new ScheduledBlock("S2", 0, 10, 1)
        );

        List<ScheduledBlockDto> dtos = service.buildScheduledBlockDtos(
                scheduled,
                Map.of("BIG", big, "S1", small1, "S2", small2),
                summaries
        );

        // BIG sorts first (3 FTE > 1 FTE) → lanes 1-3.
        // Then S1 (alphabetical) → lane 4.
        // Then S2 → lane 5.
        ScheduledBlockDto bigDto = findById(dtos, "BIG");
        assertThat(bigDto.laneStart()).isEqualTo(1);
        assertThat(bigDto.laneEnd()).isEqualTo(3);
        assertThat(bigDto.laneEnd() - bigDto.laneStart() + 1).isEqualTo(bigDto.fteRequirement());

        ScheduledBlockDto s1Dto = findById(dtos, "S1");
        assertThat(s1Dto.laneStart()).isEqualTo(4);
        assertThat(s1Dto.laneEnd()).isEqualTo(4);

        ScheduledBlockDto s2Dto = findById(dtos, "S2");
        assertThat(s2Dto.laneStart()).isEqualTo(5);
        assertThat(s2Dto.laneEnd()).isEqualTo(5);
    }

    @Test
    void buildScheduledBlockDtos_concurrentBlocksHaveDisjointLaneRanges() {
        ShiftDayDto dayDto = new ShiftDayDto(List.of(new ShiftDto(1, 14, 5)));
        List<DaySummaryDto> summaries = service.computeDaySummaries(
                List.of(dayDto), List.of(service.toShiftDay(dayDto)));

        Block a = simpleBlock("A", "A", 6, 2);
        Block b = simpleBlock("B", "B", 6, 2);
        Block c = simpleBlock("C", "C", 6, 1);

        List<ScheduledBlock> scheduled = List.of(
                new ScheduledBlock("A", 0, 6, 1),
                new ScheduledBlock("B", 0, 6, 1),
                new ScheduledBlock("C", 0, 6, 1)
        );

        List<ScheduledBlockDto> dtos = service.buildScheduledBlockDtos(
                scheduled,
                Map.of("A", a, "B", b, "C", c),
                summaries
        );

        // No two concurrent blocks share a lane.
        for (int i = 0; i < dtos.size(); i++) {
            for (int j = i + 1; j < dtos.size(); j++) {
                ScheduledBlockDto x = dtos.get(i);
                ScheduledBlockDto y = dtos.get(j);
                boolean overlap = !(x.endHalfHour() <= y.startHalfHour()
                        || y.endHalfHour() <= x.startHalfHour());
                if (overlap) {
                    assertThat(x.laneEnd() < y.laneStart() || y.laneEnd() < x.laneStart())
                            .as("Concurrent blocks %s and %s must have disjoint lanes", x.blockId(), y.blockId())
                            .isTrue();
                }
            }
        }
    }

    @Test
    void buildScheduledBlockDtos_failsLoudlyOnFteOverallocation() {
        // Lane count is only 2, but the test feeds three concurrent FTE-1 blocks.
        // Real algorithms would reject this; we deliberately fabricate an invalid
        // scheduled list to verify the safety net.
        ShiftDayDto dayDto = new ShiftDayDto(List.of(new ShiftDto(1, 5, 2)));
        List<DaySummaryDto> summaries = service.computeDaySummaries(
                List.of(dayDto), List.of(service.toShiftDay(dayDto)));

        Block a = simpleBlock("A", "A", 4, 1);
        Block b = simpleBlock("B", "B", 4, 1);
        Block c = simpleBlock("C", "C", 4, 1);

        List<ScheduledBlock> bogus = List.of(
                new ScheduledBlock("A", 0, 4, 1),
                new ScheduledBlock("B", 0, 4, 1),
                new ScheduledBlock("C", 0, 4, 1)
        );

        assertThatThrownBy(() -> service.buildScheduledBlockDtos(
                bogus, Map.of("A", a, "B", b, "C", c), summaries))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lane packing failed");
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static Block simpleBlock(String id, String name, int durationHalfHours, int fte) {
        return new Block(id, name, durationHalfHours, fte, null, null, null, null, null);
    }

    private static ScheduledBlockDto findById(List<ScheduledBlockDto> dtos, String id) {
        return dtos.stream()
                .filter(d -> d.blockId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Block not found: " + id));
    }

    /** Tiny in-memory {@link BlockRepository} that returns a fixed list. */
    private static final class InMemoryBlockRepository implements BlockRepository {
        private final List<Block> blocks;

        InMemoryBlockRepository(List<Block> blocks) {
            this.blocks = blocks;
        }

        @Override
        public List<Block> getAllBlocks() {
            return blocks;
        }

        @Override
        public Block getBlockById(String id) {
            return blocks.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
        }
    }
}
