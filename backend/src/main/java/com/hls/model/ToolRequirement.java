package com.hls.model;

/**
 * Tool needed by a block while it executes.
 *
 * <p>{@code toolName} is normalized (trim + lowercase) at load time so tool
 * matching across blocks is case-insensitive.
 *
 * <p>{@code exclusive} maps directly from the Excel {@code TOOLS AMOUNT} column:
 * {@code "One"} → {@code true} (single-availability — no two blocks naming the
 * same tool may overlap), {@code "Multiple"} → {@code false} (no constraint).
 * Only exclusive tools generate scheduling constraints downstream.
 */
public record ToolRequirement(String toolName, boolean exclusive) {}
