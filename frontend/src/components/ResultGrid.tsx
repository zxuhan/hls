import type { CSSProperties } from 'react'
import type { DaySummaryDto, ScheduleResponse, ScheduledBlockDto } from '../types'

interface Props {
  result: ScheduleResponse
}

// Deterministic fallback palette (yellow, blue, green, pink). Used both when
// the backend sends no colour and when it sends the sentinel `#FFFF00`
// (Block.DEFAULT_COLOUR) — the latter would otherwise paint every block
// the same bright yellow.
const FALLBACK_PALETTE = ['#FDE68A', '#BAE6FD', '#A7F3D0', '#FBCFE8']
const BACKEND_DEFAULT_COLOUR = '#FFFF00'

// Group sorted lane numbers into maximal contiguous runs.
// e.g. [3, 4, 6] → [{start: 3, end: 4}, {start: 6, end: 6}]
function groupContiguousLanes(lanes: number[]): { start: number; end: number }[] {
  if (lanes.length === 0) return []
  const runs: { start: number; end: number }[] = []
  let runStart = lanes[0]
  let prev = lanes[0]
  for (let i = 1; i < lanes.length; i++) {
    if (lanes[i] === prev + 1) {
      prev = lanes[i]
    } else {
      runs.push({ start: runStart, end: prev })
      runStart = lanes[i]
      prev = lanes[i]
    }
  }
  runs.push({ start: runStart, end: prev })
  return runs
}

function resolveColour(hex: string | null | undefined, seed: string): string {
  if (
    hex &&
    hex.toUpperCase() !== BACKEND_DEFAULT_COLOUR &&
    /^#[0-9a-fA-F]{6}$/.test(hex)
  ) {
    return hex
  }
  let h = 2166136261 >>> 0
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return FALLBACK_PALETTE[(h >>> 0) % FALLBACK_PALETTE.length]
}

export function ResultGrid({ result }: Props) {
  if (!result.daySummaries || !result.scheduledBlocks || result.makespan == null) {
    return null
  }

  const blocksByDay = new Map<number, ScheduledBlockDto[]>()
  for (const b of result.scheduledBlocks) {
    const arr = blocksByDay.get(b.dayIndex) ?? []
    arr.push(b)
    blocksByDay.set(b.dayIndex, arr)
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-4 rounded-lg bg-success/10 border border-success/20 px-4 py-2.5">
        <Metric
          label="Makespan"
          value={`${result.makespan} day${result.makespan === 1 ? '' : 's'}`}
        />
        <Separator />
        <Metric label="Runtime" value={`${result.runtimeMs} ms`} />
        {result.bestBound != null && (
          <>
            <Separator />
            <Metric
              label="Gap"
              value={`${((result.optimalityGap ?? 0) * 100).toFixed(1)}%`}
            />
            <Separator />
            <Metric label="Best bound" value={String(result.bestBound)} />
          </>
        )}
      </div>

      {result.daySummaries.map((day) => (
        <DayGrid
          key={day.dayIndex}
          day={day}
          blocks={blocksByDay.get(day.dayIndex) ?? []}
        />
      ))}
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="text-sm font-semibold text-foreground">{value}</p>
    </div>
  )
}

function Separator() {
  return <div className="w-px h-8 bg-border" />
}

function DayGrid({
  day,
  blocks,
}: {
  day: DaySummaryDto
  blocks: ScheduledBlockDto[]
}) {
  const totalCols = day.totalHalfHours
  const hours = Math.ceil(totalCols / 2)

  // Label column has a fixed minimum; half-hour cells share remaining width
  // equally via 1fr so the grid stretches edge-to-edge at any container width.
  const gridStyle: CSSProperties = {
    gridTemplateColumns: `minmax(64px, max-content) repeat(${totalCols}, minmax(14px, 1fr))`,
    gridTemplateRows: `var(--hour-header-h) repeat(${day.laneCount}, var(--lane-h))`,
  }

  return (
    <div className="day-grid-wrap">
      <div className="day-grid" style={gridStyle}>
        {/* DAY N inline label */}
        <div className="grid-cell grid-label day-label" style={{ gridRow: 1, gridColumn: 1 }}>
          DAY {day.dayIndex}
        </div>

        {/* Hour labels — each spans the 2 half-hour cells that make up the hour */}
        {Array.from({ length: hours }, (_, h) => {
          const span = Math.min(2, totalCols - h * 2)
          return (
            <div
              key={`hour-${h}`}
              className="grid-cell grid-hour-label"
              style={{ gridRow: 1, gridColumn: `${h * 2 + 2} / span ${span}` }}
            >
              {h + 1}
            </div>
          )
        })}

        {/* Eng k lane labels */}
        {Array.from({ length: day.laneCount }, (_, lane) => (
          <div
            key={`lane-${lane}`}
            className="grid-cell grid-label lane-label"
            style={{ gridRow: lane + 2, gridColumn: 1 }}
          >
            Eng {lane + 1}
          </div>
        ))}

        {/* Empty cell backgrounds — rendered as individual half-hour cells so
            the borders line up with the hour labels above. */}
        {Array.from({ length: day.laneCount }).flatMap((_, lane) =>
          Array.from({ length: totalCols }, (_, col) => (
            <div
              key={`bg-${lane}-${col}`}
              className="grid-cell grid-empty"
              style={{ gridRow: lane + 2, gridColumn: col + 2 }}
            />
          )),
        )}

        {/* Blocks — one rectangle per maximal contiguous run of lanes.
              Non-contiguous lanes (e.g. [3, 6]) produce two rectangles so
              the gap remains free for whatever else lives in lanes 4-5.
              gridRow: lane numbers (1-indexed lane → grid row lane + 1)
              gridCol: startHalfHour..endHalfHour half-hour cells */}
        {blocks.flatMap((blk) => {
          const bg = resolveColour(blk.colour, blk.blockId)
          const dur = (blk.endHalfHour - blk.startHalfHour) / 2
          const tooltip =
            `id: ${blk.blockId}\n` +
            `name: ${blk.name}\n` +
            `FTE: ${blk.fteRequirement}\n` +
            `duration: ${dur}h\n` +
            `hours: ${blk.startHalfHour / 2 + 1}–${blk.endHalfHour / 2 + 1}\n` +
            `lanes: ${blk.lanes.join(', ')}`
          const runs = groupContiguousLanes(blk.lanes)
          return runs.map((run, idx) => {
            const style: CSSProperties = {
              gridRow: `${run.start + 1} / ${run.end + 2}`,
              gridColumn: `${blk.startHalfHour + 2} / ${blk.endHalfHour + 2}`,
              background: bg,
            }
            return (
              <div
                key={`${blk.blockId}-${blk.dayIndex}-${blk.startHalfHour}-${run.start}-${idx}`}
                className="grid-cell grid-block"
                style={style}
                title={tooltip}
              >
                {blk.name}
              </div>
            )
          })
        })}
      </div>
    </div>
  )
}
