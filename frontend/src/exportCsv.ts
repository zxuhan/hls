import type {
  AlgorithmId,
  BlockDto,
  ScheduleResponse,
} from './types'

// Flat-table columns, in row order. Single source of truth for the header
// row and the per-row payload assembled in buildTasksCsv.
const TASK_COLUMNS = [
  'task', 'day', 'hour_start', 'end_hour_in_day', 'duration',
  'fte', 'location', 'loto', 'ws', 'rs',
] as const

export async function exportSchedule(
  result: ScheduleResponse,
  blocks: BlockDto[],
  algorithm: AlgorithmId,
): Promise<void> {
  if (!result.scheduledBlocks || !result.daySummaries) return

  const csv = buildTasksCsv(result, blocks)
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const ts = formatTimestamp(new Date())
  const filename = `hls_schedule_${algorithm}_${ts}.csv`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ─── Flat task table (Blocks-sheet order) ────────────────────────────────

function buildTasksCsv(
  result: ScheduleResponse,
  blocks: BlockDto[],
): string {
  const blockById = new Map(blocks.map((b) => [b.id, b]))
  const scheduledById = new Map(
    (result.scheduledBlocks ?? []).map((sb) => [sb.blockId, sb]),
  )

  const lines = [TASK_COLUMNS.map(csvCell).join(',')]

  // Iterate in Blocks-sheet order; skip unscheduled blocks.
  for (const block of blocks) {
    const sb = scheduledById.get(block.id)
    if (!sb) continue
    const src = blockById.get(block.id) ?? block
    const hourStart = sb.startHalfHour / 2 + 1
    const hourEnd = sb.endHalfHour / 2 + 1
    const row: (string | number)[] = [
      sb.name,
      sb.dayIndex,
      hourStart,
      hourEnd,
      hourEnd - hourStart,
      sb.fteRequirement,
      src.occupiedZones.join(','),
      '',
      src.positionAxes['WS'] ?? '',
      src.positionAxes['RS'] ?? '',
    ]
    lines.push(row.map(csvCell).join(','))
  }

  // Trailing newline so the file ends cleanly for downstream parsers.
  return lines.join('\r\n') + '\r\n'
}

// ─── Helpers ─────────────────────────────────────────────────────────────

// RFC 4180 field escaping: wrap in double quotes when the value contains a
// comma, double quote, CR or LF, doubling any embedded quotes. The `location`
// column joins occupied zones with commas, so quoting is required there.
function csvCell(value: string | number): string {
  const s = String(value)
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

function formatTimestamp(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  )
}
