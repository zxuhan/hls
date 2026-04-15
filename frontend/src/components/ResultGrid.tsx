import type { CSSProperties } from 'react'
import { colorForBlockId } from '../color'
import type { DaySummaryDto, ScheduleResponse, ScheduledBlockDto } from '../types'

interface Props {
  result: ScheduleResponse
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
    <div className="result-grid">
      <div className="result-status">
        <span>
          <strong>makespan:</strong> {result.makespan} day
          {result.makespan === 1 ? '' : 's'}
        </span>
        <span className="dot">·</span>
        <span>
          <strong>runtime:</strong> {result.runtimeMs}ms
        </span>
        {result.bestBound != null && (
          <>
            <span className="dot">·</span>
            <span>
              <strong>gap:</strong>{' '}
              {((result.optimalityGap ?? 0) * 100).toFixed(1)}%
            </span>
            <span className="dot">·</span>
            <span>
              <strong>bestBound:</strong> {result.bestBound}
            </span>
          </>
        )}
      </div>

      {result.daySummaries.map((day) => (
        <DayBlock
          key={day.dayIndex}
          day={day}
          blocks={blocksByDay.get(day.dayIndex) ?? []}
        />
      ))}
    </div>
  )
}

function DayBlock({
  day,
  blocks,
}: {
  day: DaySummaryDto
  blocks: ScheduledBlockDto[]
}) {
  const totalCols = day.totalHalfHours
  const hours = Math.ceil(totalCols / 2)

  // Grid columns: gutter + N half-hour columns.
  // Grid rows: column letters (row 1) + hour labels (row 2) + K lane rows.
  const gridStyle: CSSProperties = {
    gridTemplateColumns: `var(--gutter-w) repeat(${totalCols}, var(--cell-w))`,
    gridTemplateRows: `var(--col-header-h) var(--hour-header-h) repeat(${day.laneCount}, var(--lane-h))`,
  }

  return (
    <div className="day-block">
      <div className="day-header">Day {day.dayIndex}</div>

      <div className="sheet-scroll">
        <div className="sheet" style={gridStyle}>
          {/* Top-left corner — covers gutter × column-letter row */}
          <div
            className="sheet-corner row-1"
            style={{ gridRow: 1, gridColumn: 1 }}
          />

          {/* Row 1: column letters A, B, C, ... AA, AB, ... */}
          {Array.from({ length: totalCols }, (_, i) => (
            <div
              key={`col-${i}`}
              className="sheet-col-header"
              style={{ gridRow: 1, gridColumn: i + 2 }}
            >
              {indexToColumnLetter(i)}
            </div>
          ))}

          {/* Top-left corner — gutter × hour-label row */}
          <div
            className="sheet-corner row-2"
            style={{ gridRow: 2, gridColumn: 1 }}
          >
            #
          </div>

          {/* Row 2: hour labels — each spans 2 half-hour columns */}
          {Array.from({ length: hours }, (_, h) => {
            const span = Math.min(2, totalCols - h * 2)
            return (
              <div
                key={`hour-${h}`}
                className="sheet-hour-header"
                style={{
                  gridRow: 2,
                  gridColumn: `${h * 2 + 2} / span ${span}`,
                }}
              >
                Hour {h + 1}
              </div>
            )
          })}

          {/* Lane row gutters (sticky left) — render row numbers 1..laneCount */}
          {Array.from({ length: day.laneCount }, (_, lane) => (
            <div
              key={`row-${lane}`}
              className="sheet-row-header"
              style={{ gridRow: lane + 3, gridColumn: 1 }}
            >
              {lane + 1}
            </div>
          ))}

          {/* Lane row backgrounds (alternating stripes) — span all half-hour columns */}
          {Array.from({ length: day.laneCount }, (_, lane) => (
            <div
              key={`bg-${lane}`}
              className={'sheet-row-bg' + (lane % 2 === 1 ? ' odd' : '')}
              style={{
                gridRow: lane + 3,
                gridColumn: `2 / span ${totalCols}`,
              }}
            />
          ))}

          {/* Block cells */}
          {blocks.map((blk) => {
            const cellStyle: CSSProperties = {
              gridRow: `${blk.laneStart + 2} / ${blk.laneEnd + 3}`,
              gridColumn: `${blk.startHalfHour + 2} / ${blk.endHalfHour + 2}`,
              ['--cell-bg' as string]: colorForBlockId(blk.blockId),
            }
            const dur = (blk.endHalfHour - blk.startHalfHour) / 2
            const tooltip =
              `id: ${blk.blockId}\n` +
              `name: ${blk.name}\n` +
              `FTE: ${blk.fteRequirement}\n` +
              `duration: ${dur}h\n` +
              `lanes: ${blk.laneStart}–${blk.laneEnd}\n` +
              `hours: ${blk.startHalfHour / 2 + 1}–${blk.endHalfHour / 2}`
            return (
              <div
                key={`${blk.blockId}-${blk.dayIndex}-${blk.startHalfHour}-${blk.laneStart}`}
                className="sheet-block"
                style={cellStyle}
                title={tooltip}
              >
                <span className="sheet-block-id">{blk.blockId}</span>
                <span className="sheet-block-name">{blk.name}</span>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

// 0 → "A", 25 → "Z", 26 → "AA", 27 → "AB", … (Excel column letters)
function indexToColumnLetter(i: number): string {
  let n = i
  let s = ''
  while (true) {
    s = String.fromCharCode(65 + (n % 26)) + s
    n = Math.floor(n / 26) - 1
    if (n < 0) break
  }
  return s
}
