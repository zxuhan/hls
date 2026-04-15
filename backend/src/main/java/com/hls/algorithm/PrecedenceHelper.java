package com.hls.algorithm;

import com.hls.model.Block;

import java.util.*;

public final class PrecedenceHelper {

    private PrecedenceHelper() {}

    public static Map<String, List<String>> buildSuccessorMap(List<Block> blocks) {
        Map<String, List<String>> successors = new HashMap<>();
        for (Block block : blocks) {
            successors.putIfAbsent(block.id(), new ArrayList<>());
        }
        for (Block block : blocks) {
            for (String predId : block.predecessorBlockIds()) {
                successors.computeIfAbsent(predId, k -> new ArrayList<>()).add(block.id());
            }
        }
        return successors;
    }

    public static List<Block> topologicalSort(List<Block> blocks) {
        Map<String, Block> blockMap = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> successors = buildSuccessorMap(blocks);

        for (Block b : blocks) {
            blockMap.put(b.id(), b);
            inDegree.put(b.id(), 0);
        }
        for (Block b : blocks) {
            for (String predId : b.predecessorBlockIds()) {
                if (blockMap.containsKey(predId)) {
                    inDegree.merge(b.id(), 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<Block> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(blockMap.get(current));
            for (String succ : successors.getOrDefault(current, List.of())) {
                if (inDegree.containsKey(succ)) {
                    int newDegree = inDegree.get(succ) - 1;
                    inDegree.put(succ, newDegree);
                    if (newDegree == 0) {
                        queue.add(succ);
                    }
                }
            }
        }

        if (sorted.size() != blocks.size()) {
            throw new IllegalArgumentException("Precedence cycle detected");
        }
        return sorted;
    }

    public static boolean hasCycle(List<Block> blocks) {
        try {
            topologicalSort(blocks);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static Map<String, Integer> computeTotalSuccessors(List<Block> blocks) {
        Map<String, List<String>> successors = buildSuccessorMap(blocks);
        Map<String, Integer> cache = new HashMap<>();

        for (Block block : blocks) {
            countSuccessors(block.id(), successors, cache, new HashSet<>());
        }
        return cache;
    }

    private static int countSuccessors(String blockId, Map<String, List<String>> successors,
                                       Map<String, Integer> cache, Set<String> visiting) {
        if (cache.containsKey(blockId)) return cache.get(blockId);
        visiting.add(blockId);

        int count = 0;
        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        for (String succ : successors.getOrDefault(blockId, List.of())) {
            if (!visiting.contains(succ)) {
                queue.add(succ);
                reachable.add(succ);
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : successors.getOrDefault(current, List.of())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        count = reachable.size();

        visiting.remove(blockId);
        cache.put(blockId, count);
        return count;
    }

    public static Map<String, Integer> computeCriticalPathRemaining(List<Block> blocks) {
        Map<String, Block> blockMap = new HashMap<>();
        for (Block b : blocks) {
            blockMap.put(b.id(), b);
        }

        Map<String, List<String>> successors = buildSuccessorMap(blocks);
        List<Block> sorted = topologicalSort(blocks);
        Map<String, Integer> cpr = new HashMap<>();

        // Reverse topological order
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Block block = sorted.get(i);
            int maxSuccCpr = 0;
            for (String succId : successors.getOrDefault(block.id(), List.of())) {
                if (cpr.containsKey(succId)) {
                    maxSuccCpr = Math.max(maxSuccCpr, cpr.get(succId));
                }
            }
            cpr.put(block.id(), block.durationHalfHours() + maxSuccCpr);
        }
        return cpr;
    }
}
