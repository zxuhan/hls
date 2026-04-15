package com.hls.algorithm;

import com.hls.model.Shift;
import com.hls.model.ShiftDay;

import java.util.List;

public final class TimelineHelper {

    public static final int SLOTS_PER_DAY = 48;

    private TimelineHelper() {}

    public static int[] buildFteCapacityArray(List<ShiftDay> shiftSchedule) {
        int horizonLength = shiftSchedule.size() * SLOTS_PER_DAY;
        int[] capacity = new int[horizonLength];

        for (int dayIdx = 0; dayIdx < shiftSchedule.size(); dayIdx++) {
            int dayOffset = dayIdx * SLOTS_PER_DAY;
            for (Shift shift : shiftSchedule.get(dayIdx).shifts()) {
                int start = dayOffset + shift.startHalfHour();
                int end = dayOffset + shift.endHalfHour();
                for (int t = start; t < end && t < horizonLength; t++) {
                    capacity[t] += shift.fte();
                }
            }
        }
        return capacity;
    }

    public static int dayIndex(int absoluteTime) {
        return (absoluteTime / SLOTS_PER_DAY) + 1;
    }

    public static boolean fitsInSingleDay(int startTime, int duration) {
        return (startTime / SLOTS_PER_DAY) == ((startTime + duration - 1) / SLOTS_PER_DAY);
    }

    public static boolean hasSufficientFte(int startTime, int duration, int fteRequired, int[] fteCapacity, int[] fteUsed) {
        for (int t = startTime; t < startTime + duration; t++) {
            if (t >= fteCapacity.length) return false;
            if (fteCapacity[t] - fteUsed[t] < fteRequired) return false;
        }
        return true;
    }

    public static int nextDayStart(int absoluteTime) {
        return ((absoluteTime / SLOTS_PER_DAY) + 1) * SLOTS_PER_DAY;
    }
}
