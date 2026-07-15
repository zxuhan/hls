package com.hls.algorithm;

import com.hls.model.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Groups the blocks of a scheduling run by their ODM sequence-group label and
 * answers the questions the greedy constructors need to place each group as one
 * contiguous campaign (constraint 7):
 *
 * <ul>
 *   <li>which blocks are grouped, and into which group;</li>
 *   <li>the fixed internal order of a group's members — a topological order of
 *       any intra-group precedence, tie-broken by id, so the chain is
 *       deterministic and respects precedence;</li>
 *   <li>whether a group is <em>ready</em>: every member's predecessors that lie
 *       in the run and outside the group are already scheduled. Intra-group
 *       predecessors are not gated here — they are satisfied by the member
 *       ordering when the whole group is placed at once.</li>
 * </ul>
 *
 * Ungrouped blocks are ignored by this planner; the scheduler keeps placing
 * them one at a time as before.
 */
final class SequenceGroupPlanner {

    /** groupId → members in fixed chain order. */
    private final Map<String, List<Block>> groupMembers = new LinkedHashMap<>();
    /** blockId → groupId, for grouped blocks only. */
    private final Map<String, String> blockToGroup = new HashMap<>();
    /** Ids of every block in the run (used to ignore out-of-run predecessors). */
    private final Set<String> runIds;

    SequenceGroupPlanner(List<Block> blocks) {
        this.runIds = blocks.stream().map(Block::id).collect(Collectors.toSet());

        Map<String, List<Block>> raw = new LinkedHashMap<>();
        for (Block b : blocks) {
            String group = b.odm().sequenceGroup();
            if (group != null) {
                raw.computeIfAbsent(group, k -> new ArrayList<>()).add(b);
            }
        }
        for (Map.Entry<String, List<Block>> e : raw.entrySet()) {
            List<Block> ordered = orderMembers(e.getValue());
            groupMembers.put(e.getKey(), ordered);
            for (Block m : ordered) {
                blockToGroup.put(m.id(), e.getKey());
            }
        }
    }

    boolean hasGroups() {
        return !groupMembers.isEmpty();
    }

    boolean isGrouped(String blockId) {
        return blockToGroup.containsKey(blockId);
    }

    String groupOf(String blockId) {
        return blockToGroup.get(blockId);
    }

    Iterable<String> groupIds() {
        return groupMembers.keySet();
    }

    List<Block> members(String groupId) {
        return groupMembers.get(groupId);
    }

    /**
     * A group is ready when every member's in-run, out-of-group predecessor is
     * already scheduled.
     */
    boolean isReady(String groupId, Predicate<String> scheduled) {
        List<Block> members = groupMembers.get(groupId);
        Set<String> memberIds = members.stream().map(Block::id).collect(Collectors.toSet());
        for (Block m : members) {
            for (String pred : m.predecessorBlockIds()) {
                if (!runIds.contains(pred)) continue;   // predecessor outside the run — ignored
                if (memberIds.contains(pred)) continue;  // intra-group — handled by ordering
                if (!scheduled.test(pred)) return false;
            }
        }
        return true;
    }

    /**
     * Order a group's members topologically over their intra-group precedence
     * edges, tie-broken by id, so the contiguous chain respects precedence and
     * is deterministic. The global DAG is acyclic, so this sub-graph is too.
     */
    private static List<Block> orderMembers(List<Block> members) {
        Set<String> ids = members.stream().map(Block::id).collect(Collectors.toSet());
        Map<String, Block> byId = members.stream().collect(Collectors.toMap(Block::id, b -> b));
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        for (Block m : members) {
            inDegree.put(m.id(), 0);
            successors.put(m.id(), new ArrayList<>());
        }
        for (Block m : members) {
            for (String pred : m.predecessorBlockIds()) {
                if (ids.contains(pred)) {
                    successors.get(pred).add(m.id());
                    inDegree.merge(m.id(), 1, Integer::sum);
                }
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        List<Block> ordered = new ArrayList<>(members.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            ordered.add(byId.get(id));
            for (String s : successors.get(id)) {
                if (inDegree.merge(s, -1, Integer::sum) == 0) {
                    ready.add(s);
                }
            }
        }
        return ordered;
    }
}
