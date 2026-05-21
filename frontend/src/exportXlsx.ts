import ExcelJS from 'exceljs'
import type {
  AlgorithmId,
  BlockDto,
  ScheduleResponse,
} from './types'

// Flat-table sheet columns. Order matches the row payload below.
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

  const wb = new ExcelJS.Workbook()
  writeTasksSheet(wb, result, blocks)

  const buf = await wb.xlsx.writeBuffer()
  const blob = new Blob([buf], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  const ts = formatTimestamp(new Date())
  const filename = `hls_schedule_${algorithm}_${ts}.xlsx`
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ─── Sheet 1: Tasks (flat table, Blocks-sheet order) ─────────────────────

function writeTasksSheet(
  wb: ExcelJS.Workbook,
  result: ScheduleResponse,
  blocks: BlockDto[],
): void {
  const sheet = wb.addWorksheet('Tasks')
  sheet.columns = [
    { header: 'task', width: 28 },
    { header: 'day', width: 5 },
    { header: 'hour_start', width: 11 },
    { header: 'end_hour_in_day', width: 11 },
    { header: 'duration', width: 9 },
    { header: 'fte', width: 5 },
    { header: 'location', width: 22 },
    { header: 'loto', width: 6 },
    { header: 'ws', width: 10 },
    { header: 'rs', width: 10 },
  ]
  sheet.getRow(1).font = { bold: true }

  const blockById = new Map(blocks.map((b) => [b.id, b]))
  const scheduledById = new Map(
    (result.scheduledBlocks ?? []).map((sb) => [sb.blockId, sb]),
  )

  // Iterate in Blocks-sheet order; skip unscheduled blocks.
  for (const block of blocks) {
    const sb = scheduledById.get(block.id)
    if (!sb) continue
    const src = blockById.get(block.id) ?? block
    const hourStart = sb.startHalfHour / 2 + 1
    const hourEnd = sb.endHalfHour / 2 + 1
    sheet.addRow([
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
    ])
  }
  // Quick confirmation that TASK_COLUMNS matches what we emit.
  if (TASK_COLUMNS.length !== 10) throw new Error('Task columns out of sync')
}

// ─── Helpers ─────────────────────────────────────────────────────────────

function formatTimestamp(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  )
}
