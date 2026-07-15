package com.hls.algorithm;

import com.hls.model.Block;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for {@link PrecedenceHelper#resolveTransitivePredecessors}.
 *
 * <p>The helper bridges over unselected blocks so the scheduler sees the
 * implicit precedence between selected blocks. These cases cover the
 * shapes that motivated the fix: a straight chain, a diamond, a long
 * chain (multi-hop bridging), the all-selected no-op, and the
 * leaf-block-with-no-preds boundary.
 */
class PrecedenceHelperTest {

    @Test
    void bridgesOverSingleUnselectedIntermediary() {
        // A → B → C; selection drops B.
        Block a = block("A", List.of("B"));
        Block b = block("B", List.of("C"));
        Block c = block("C", List.of());

        List<Block> rewritten = PrecedenceHelper.resolveTransitivePredecessors(
                List.of(a, c), List.of(a, b, c));

        assertThat(byId(rewritten, "A").predecessorBlockIds()).containsExactly("C");
        assertThat(byId(rewritten, "C").predecessorBlockIds()).isEmpty();
    }

    @Test
    void bridgesOverMultiHopChainOfUnselectedIntermediaries() {
        // A → B → C → D; selection keeps only A and D.
        Block a = block("A", List.of("B"));
        Block b = block("B", List.of("C"));
        Block c = block("C", List.of("D"));
        Block d = block("D", List.of());

        List<Block> rewritten = PrecedenceHelper.resolveTransitivePredecessors(
                List.of(a, d), List.of(a, b, c, d));

        assertThat(byId(rewritten, "A").predecessorBlockIds()).containsExactly("D");
        assertThat(byId(rewritten, "D").predecessorBlockIds()).isEmpty();
    }

    @Test
    void diamondCollapsesToSingleAncestorWhenInteriorIsUnselected() {
        // D → B → A, D → C → A; selection drops B and C, keeps A and D.
        // (Edge direction: predecessor → successor.)
        Block a = block("A", List.of("B", "C"));
        Block b = block("B", List.of("D"));
        Block c = block("C", List.of("D"));
        Block d = block("D", List.of());

        List<Block> rewritten = PrecedenceHelper.resolveTransitivePredecessors(
                List.of(a, d), List.of(a, b, c, d));

        // A reaches D through both B and C; the LinkedHashSet collapses
        // those to a single edge.
        assertThat(byId(rewritten, "A").predecessorBlockIds()).containsExactly("D");
        assertThat(byId(rewritten, "D").predecessorBlockIds()).isEmpty();
    }

    @Test
    void allSelectedIsANoOpAndPreservesObjectIdentity() {
        Block a = block("A", List.of("B"));
        Block b = block("B", List.of("C"));
        Block c = block("C", List.of());
        List<Block> all = List.of(a, b, c);

        List<Block> rewritten = PrecedenceHelper.resolveTransitivePredecessors(all, all);

        // The walk stops at the first selected ancestor (always the direct
        // pred), so the predecessor lists are byte-for-byte the originals
        // and the helper returns the same Block instances it received.
        assertThat(rewritten).containsExactly(a, b, c);
    }

    @Test
    void leafBlockWithNoPredecessorsHasEmptyEffectiveList() {
        // C has no predecessors in the TDM; selection is {C} only.
        Block c = block("C", List.of());

        List<Block> rewritten = PrecedenceHelper.resolveTransitivePredecessors(
                List.of(c), List.of(c));

        assertThat(byId(rewritten, "C").predecessorBlockIds()).isEmpty();
    }

    @Test
    void selectedBlockSkippedWhenItsOnlyPathTouchesUnselectedRoots() {
        // A → B (unselected) only. B has no further predecessors. A is
        // selected. Result: A has no effective predecessors — there's no
        // selected ancestor reachable.
        Block a = block("A", List.of("B"));
        Block b = block("B", List.of());

        List<Block> rewritten = PrecedenceHelper.resolveTransitivePredecessors(
                List.of(a), List.of(a, b));

        assertThat(byId(rewritten, "A").predecessorBlockIds()).isEmpty();
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static Block block(String id, List<String> predecessors) {
        return new Block(
                id, id, 1, 1,
                Set.of(),
                Map.of(),
                null,
                predecessors,
                null,
                null);
    }

    private static Block byId(List<Block> blocks, String id) {
        return blocks.stream()
                .filter(b -> b.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Block not found: " + id));
    }
}
