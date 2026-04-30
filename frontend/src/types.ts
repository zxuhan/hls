// Mirrors of API_SPEC.md DTOs. Field names match the OpenAPI schema exactly.

export type AlgorithmId = 'A_MTS' | 'A_SPT' | 'B_CPSAT' | 'C_ENHANCED'

export type CandidateCWeight = 0 | 0.25 | 0.5 | 0.75 | 1

export interface BlockDto {
  id: string
  name: string
  durationHalfHours: number
  fteRequirement: number
  occupiedZones: string[]
  positionAxes: Record<string, string>
  requiredToolName: string | null
  requiredToolExclusive: boolean | null
  predecessorBlockIds: string[]
  colour: string
}

export interface BlockListResponse {
  success: boolean
  errorMessage: string | null
  blocks: BlockDto[]
}

export interface Shift {
  startHour: number
  durationHours: number
  fte: number
}

export interface ShiftDay {
  shifts: Shift[]
}

export interface ScheduleRequest {
  algorithm: AlgorithmId
  blockIds: string[]
  shiftSchedule: ShiftDay[]
  cpSatTimeLimitSeconds?: number
  candidateCWeight?: CandidateCWeight
}

export interface ScheduledBlockDto {
  blockId: string
  name: string
  fteRequirement: number
  dayIndex: number
  startHalfHour: number
  endHalfHour: number
  // 1-indexed lane numbers, sorted ascending. May be non-contiguous —
  // render one rectangle per maximal contiguous run.
  lanes: number[]
  colour: string
}

export interface DaySummaryDto {
  dayIndex: number
  totalHalfHours: number
  laneCount: number
  shifts: Shift[]
}

export interface ScheduleResponse {
  success: boolean
  errorMessage: string | null
  makespan: number | null
  scheduledBlocks: ScheduledBlockDto[] | null
  daySummaries: DaySummaryDto[] | null
  runtimeMs: number
  bestBound: number | null
  optimalityGap: number | null
}

export interface LoaderViolation {
  sheet: string
  cellRef: string
  code: string
  message: string
}

export interface ReloadResponse {
  success: boolean
  message: string
  violations: LoaderViolation[]
  blockCount: number | null
}

export interface ErrorResponse {
  success: false
  errorMessage: string
}
