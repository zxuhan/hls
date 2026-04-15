package com.hls.algorithm;

import com.hls.model.Block;
import com.hls.model.ScheduleResult;
import com.hls.model.ShiftDay;

import java.util.List;
import java.util.Map;

public interface Scheduler {
    ScheduleResult schedule(List<Block> blocks, List<ShiftDay> shiftSchedule, Map<String, Object> params);
}
