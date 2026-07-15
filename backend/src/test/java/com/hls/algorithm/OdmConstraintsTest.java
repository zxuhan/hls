package com.hls.algorithm;

import com.hls.model.Block;
import com.hls.model.OdmConstraints;
import com.hls.model.ScheduleResult;
import com.hls.model.ScheduledBlock;
import com.hls.model.Shift;
import com.hls.model.ShiftDay;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforcement tests for the three ODM-sheet constraints (7–9) across all
 * schedulers that consume them: greedy A ({@link GreedyScheduler}), enhanced C
 * ({@link EnhancedGreedyScheduler}), and exact B ({@link CpSatScheduler}).
 *
 * <p>All shifts are built directly in the internal half-hour model (so a
 * {@code Shift(0, 16, 5)} is a day running half-hours [0, 16) with FTE 5),
 * bypassing the request-DTO hour conversion.
 */
class OdmConstraintsTest {

    private static final Map<String, Object> MTS = Map.of("priorityRule", "MTS");
    private static final Map<String, Object> CPSAT = Map.of("cpSatTimeLimitSeconds", 10);
    private static final Map<String, Object> ENHANCED = Map.of("candidateCWeight", 0.0);

    // ── Parallelism = No (constraint 9) ───────────────────────────────────

    @Test
    void noParallelBlockOverlapsNothing_greedy() {
        ScheduleResult r = new GreedyScheduler().schedule(noParallelBlocks(), days(3, 8, 5), MTS);
        assertThat(r.success()).isTrue();
        assertExclusive(r, "X");
    }

    @Test
    void noParallelBlockOverlapsNothing_cpsat() {
        ScheduleResult r = new CpSatScheduler().schedule(noParallelBlocks(), days(3, 8, 5), CPSAT);
        assertThat(r.success()).isTrue();
        assertExclusive(r, "X");
    }

    @Test
    void noParallelBlockOverlapsNothing_enhanced() {
        ScheduleResult r = new EnhancedGreedyScheduler().schedule(noParallelBlocks(), days(3, 8, 5), ENHANCED);
        assertThat(r.success()).isTrue();
        assertExclusive(r, "X");
    }

    private static List<Block> noParallelBlocks() {
        return List.of(
                block("X", 4, 1, new OdmConstraints(null, null, null, true)),
                block("Y", 4, 1, OdmConstraints.NONE),
                block("Z", 4, 1, OdmConstraints.NONE));
    }

    /** No other scheduled block may overlap {@code id}'s interval. */
    private static void assertExclusive(ScheduleResult r, String id) {
        ScheduledBlock x = find(r, id);
        for (ScheduledBlock other : r.scheduledBlocks()) {
            if (other.blockId().equals(id)) continue;
            assertThat(overlaps(x, other))
                    .as("block %s must not overlap exclusive block %s", other.blockId(), id)
                    .isFalse();
        }
    }

    // ── Day / Hour_Start pins (constraint 8) ──────────────────────────────

    @Test
    void dayPinLandsOnPinnedDay_greedyAndCpsat() {
        List<Block> blocks = List.of(block("P", 4, 1, new OdmConstraints(null, 2, null, false)));
        assertThat(find(new GreedyScheduler().schedule(blocks, days(3, 8, 5), MTS), "P").dayIndex())
                .isEqualTo(2);
        assertThat(find(new CpSatScheduler().schedule(blocks, days(3, 8, 5), CPSAT), "P").dayIndex())
                .isEqualTo(2);
    }

    @Test
    void hourPinStartsAtPinnedOffset_greedyAndCpsat() {
        // Hour 3 → half-hour offset (3-1)*2 = 4 within whatever day it lands on.
        List<Block> blocks = List.of(block("Q", 4, 1, new OdmConstraints(null, null, 3, false)));
        assertThat(find(new GreedyScheduler().schedule(blocks, days(3, 8, 5), MTS), "Q").startTime() % 48)
                .isEqualTo(4);
        assertThat(find(new CpSatScheduler().schedule(blocks, days(3, 8, 5), CPSAT), "Q").startTime() % 48)
                .isEqualTo(4);
    }

    @Test
    void bothPinsFixExactStart_cpsat() {
        // Day 2 (offset 48) + Hour 3 (offset 4) → absolute start 52.
        List<Block> blocks = List.of(block("R", 4, 1, new OdmConstraints(null, 2, 3, false)));
        ScheduleResult r = new CpSatScheduler().schedule(blocks, days(3, 8, 5), CPSAT);
        assertThat(r.success()).isTrue();
        assertThat(find(r, "R").startTime()).isEqualTo(52);
    }

    @Test
    void bothPinsFixExactStart_enhanced() {
        // Day 2 (offset 48) + Hour 3 (offset 4) → absolute start 52.
        List<Block> blocks = List.of(block("R", 4, 1, new OdmConstraints(null, 2, 3, false)));
        ScheduleResult r = new EnhancedGreedyScheduler().schedule(blocks, days(3, 8, 5), ENHANCED);
        assertThat(r.success()).isTrue();
        assertThat(find(r, "R").startTime()).isEqualTo(52);
    }

    @Test
    void unsatisfiablePinIsInfeasible_greedyAndEnhanced() {
        // Day 5 requested but only 3 days are scheduled.
        List<Block> blocks = List.of(block("P", 4, 1, new OdmConstraints(null, 5, null, false)));
        assertThat(new GreedyScheduler().schedule(blocks, days(3, 8, 5), MTS).success()).isFalse();
        assertThat(new EnhancedGreedyScheduler().schedule(blocks, days(3, 8, 5), ENHANCED).success()).isFalse();
    }

    @Test
    void unsatisfiablePinIsInfeasible_cpsat() {
        // Day 5 requested but only 3 days are scheduled.
        List<Block> blocks = List.of(block("P", 4, 1, new OdmConstraints(null, 5, null, false)));
        assertThat(new CpSatScheduler().schedule(blocks, days(3, 8, 5), CPSAT).success()).isFalse();
    }

    // ── Sequence-group contiguity (constraint 7) ──────────────────────────

    @Test
    void sequenceGroupIsContiguous_enhanced() {
        ScheduleResult r = new EnhancedGreedyScheduler().schedule(sgBlocks(), days(2, 8, 5), ENHANCED);
        assertThat(r.success()).isTrue();
        assertContiguousChain(r, "G1", "G2");
    }

    @Test
    void sequenceGroupIsContiguous_cpsat() {
        ScheduleResult r = new CpSatScheduler().schedule(sgBlocks(), days(2, 8, 5), CPSAT);
        assertThat(r.success()).isTrue();
        assertContiguousChain(r, "G1", "G2");
    }

    @Test
    void sequenceGroupIsContiguous_greedy() {
        ScheduleResult r = new GreedyScheduler().schedule(sgBlocks(), days(2, 8, 5), MTS);
        assertThat(r.success()).isTrue();
        assertContiguousChain(r, "G1", "G2");
    }

    /** A group placed alongside ungrouped singles must still tile gaplessly. */
    @Test
    void sequenceGroupStaysContiguousAmongSingles_greedy() {
        List<Block> blocks = List.of(
                block("G1", 4, 1, new OdmConstraints("sg001", null, null, false)),
                block("G2", 6, 1, new OdmConstraints("sg001", null, null, false)),
                block("S1", 4, 1, OdmConstraints.NONE),
                block("S2", 4, 1, OdmConstraints.NONE));
        ScheduleResult r = new GreedyScheduler().schedule(blocks, days(3, 8, 5), MTS);
        assertThat(r.success()).isTrue();
        assertContiguousChain(r, "G1", "G2");
    }

    /** Order within a group is free, but intra-group precedence still binds. */
    @Test
    void sequenceGroupHonoursIntraGroupPrecedence_greedyAndCpsat() {
        // G2 depends on G1, so the contiguous chain must run G1 then G2.
        List<Block> blocks = List.of(
                block("G1", 4, 1, new OdmConstraints("sg001", null, null, false)),
                blockWithPreds("G2", 6, 1, new OdmConstraints("sg001", null, null, false), List.of("G1")));
        for (ScheduleResult r : List.of(
                new GreedyScheduler().schedule(blocks, days(2, 8, 5), MTS),
                new CpSatScheduler().schedule(blocks, days(2, 8, 5), CPSAT))) {
            assertThat(r.success()).isTrue();
            assertContiguousChain(r, "G1", "G2");
            assertThat(find(r, "G1").endTime()).isEqualTo(find(r, "G2").startTime());
        }
    }

    /** Three members must form one solid run, not merely pairwise-adjacent ones. */
    @Test
    void threeMemberGroupTilesOneSolidInterval_greedyAndCpsat() {
        List<Block> blocks = List.of(
                block("G1", 4, 1, new OdmConstraints("sg001", null, null, false)),
                block("G2", 2, 1, new OdmConstraints("sg001", null, null, false)),
                block("G3", 6, 1, new OdmConstraints("sg001", null, null, false)));
        for (ScheduleResult r : List.of(
                new GreedyScheduler().schedule(blocks, days(2, 8, 5), MTS),
                new EnhancedGreedyScheduler().schedule(blocks, days(2, 8, 5), ENHANCED),
                new CpSatScheduler().schedule(blocks, days(2, 8, 5), CPSAT))) {
            assertThat(r.success()).isTrue();
            List<ScheduledBlock> chain = new ArrayList<>(List.of(
                    find(r, "G1"), find(r, "G2"), find(r, "G3")));
            chain.sort(java.util.Comparator.comparingInt(ScheduledBlock::startTime));
            // Union spans exactly the sum of durations: no gaps, no overlaps.
            assertThat(chain.get(2).endTime() - chain.get(0).startTime()).isEqualTo(4 + 2 + 6);
            for (int i = 0; i + 1 < chain.size(); i++) {
                assertThat(chain.get(i).endTime())
                        .as("member %s must butt against %s", chain.get(i).blockId(), chain.get(i + 1).blockId())
                        .isEqualTo(chain.get(i + 1).startTime());
            }
        }
    }

    private static List<Block> sgBlocks() {
        return List.of(
                block("G1", 4, 1, new OdmConstraints("sg001", null, null, false)),
                block("G2", 6, 1, new OdmConstraints("sg001", null, null, false)));
    }

    /** The two members must not overlap and must be exactly back-to-back. */
    private static void assertContiguousChain(ScheduleResult r, String a, String b) {
        ScheduledBlock x = find(r, a);
        ScheduledBlock y = find(r, b);
        assertThat(overlaps(x, y)).as("group members must not overlap").isFalse();
        assertThat(x.endTime() == y.startTime() || y.endTime() == x.startTime())
                .as("group members must be back-to-back (no gap)")
                .isTrue();
    }

    @Test
    void unrelatedBlockMayRunDuringSequenceGroup_cpsat() {
        // Group {G1(8), G2(8)} tiles the only day [0,16); W(4) must therefore
        // overlap the chain. Feasibility proves parallel work is allowed.
        List<Block> blocks = List.of(
                block("G1", 8, 1, new OdmConstraints("sg001", null, null, false)),
                block("G2", 8, 1, new OdmConstraints("sg001", null, null, false)),
                block("W", 4, 1, OdmConstraints.NONE));
        ScheduleResult r = new CpSatScheduler().schedule(blocks, days(1, 8, 5), CPSAT);
        assertThat(r.success()).isTrue();
        ScheduledBlock w = find(r, "W");
        boolean overlapsGroup = overlaps(w, find(r, "G1")) || overlaps(w, find(r, "G2"));
        assertThat(overlapsGroup).as("W is forced to run alongside the group").isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static boolean overlaps(ScheduledBlock a, ScheduledBlock b) {
        return a.startTime() < b.endTime() && b.startTime() < a.endTime();
    }

    private static ScheduledBlock find(ScheduleResult r, String id) {
        assertThat(r.success()).as("schedule should succeed: %s", r.errorMessage()).isTrue();
        return r.scheduledBlocks().stream()
                .filter(sb -> sb.blockId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("scheduled block not found: " + id));
    }

    private static Block block(String id, int durHalfHours, int fte, OdmConstraints odm) {
        return blockWithPreds(id, durHalfHours, fte, odm, List.of());
    }

    private static Block blockWithPreds(String id, int durHalfHours, int fte,
            OdmConstraints odm, List<String> preds) {
        return new Block(id, id, durHalfHours, fte, Set.of(), Map.of(), null, preds, null, odm);
    }

    /** {@code n} identical days, each one shift of {@code durationHours} hours at {@code fte}. */
    private static List<ShiftDay> days(int n, int durationHours, int fte) {
        List<ShiftDay> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new ShiftDay(List.of(new Shift(0, durationHours * 2, fte))));
        }
        return out;
    }
}
