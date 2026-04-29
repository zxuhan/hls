import ExcelJS from 'exceljs'
import type {
  AlgorithmId,
  BlockDto,
  ScheduleResponse,
  ScheduledBlockDto,
} from './types'

// Fallback palette for blocks whose backend `colour` is missing or unparseable.
// ARGB hex (alpha first). Picked from soft, high-contrast-to-black fills.
const FALLBACK_PALETTE = [
  'FFFDE68A', 'FFFCA5A5', 'FFA7F3D0', 'FFBAE6FD',
  'FFDDD6FE', 'FFFBCFE8', 'FFFDBA74', 'FF99F6E4',
]

// Flat-table sheet columns (sheet 1).
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
  writeGridSheet(wb, result)

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
    { header: 'end_hour_in_day', width: 16 },
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

// ─── Sheet 2: Grid (per-day, merged block cells with colour fills) ───────

function writeGridSheet(
  wb: ExcelJS.Workbook,
  result: ScheduleResponse,
): void {
  const sheet = wb.addWorksheet('Grid')
  const days = result.daySummaries!
  const scheduled = result.scheduledBlocks!

  // Sheet-wide column widths based on the widest day.
  const maxHalfHours = Math.max(1, ...days.map((d) => d.totalHalfHours))
  const cols: Partial<ExcelJS.Column>[] = [{ width: 10 }]
  for (let i = 0; i < maxHalfHours; i++) cols.push({ width: 4 })
  sheet.columns = cols

  const blocksByDay = new Map<number, ScheduledBlockDto[]>()
  for (const b of scheduled) {
    const arr = blocksByDay.get(b.dayIndex) ?? []
    arr.push(b)
    blocksByDay.set(b.dayIndex, arr)
  }

  const thin: ExcelJS.Borders = {
    top: { style: 'thin' },
    left: { style: 'thin' },
    bottom: { style: 'thin' },
    right: { style: 'thin' },
  } as ExcelJS.Borders
  const centerWrap: Partial<ExcelJS.Alignment> = {
    horizontal: 'center',
    vertical: 'middle',
    wrapText: true,
  }

  let cursor = 1 // 1-indexed row cursor
  for (const day of days) {
    const headerRow = cursor
    const firstLaneRow = cursor + 1
    const lastLaneRow = cursor + day.laneCount

    // Header row: "DAY N" in col 1; hour numbers in the half-hour columns,
    // each label merged across 2 half-hour cells (the two halves of the hour).
    const dayCell = sheet.getCell(headerRow, 1)
    dayCell.value = `DAY ${day.dayIndex}`
    dayCell.font = { bold: true }
    dayCell.alignment = centerWrap
    dayCell.border = thin

    const hours = Math.ceil(day.totalHalfHours / 2)
    for (let h = 1; h <= hours; h++) {
      const cStart = 2 + (h - 1) * 2
      const cEnd = Math.min(cStart + 1, 1 + day.totalHalfHours)
      if (cEnd > cStart) sheet.mergeCells(headerRow, cStart, headerRow, cEnd)
      const c = sheet.getCell(headerRow, cStart)
      c.value = h
      c.font = { bold: true }
      c.alignment = centerWrap
      // Borders must be set on every cell in a merged range, not just the anchor.
      for (let col = cStart; col <= cEnd; col++) {
        sheet.getCell(headerRow, col).border = thin
      }
    }

    // Lane label column + empty cells with borders (so the grid renders even
    // where no block is placed).
    for (let lane = 1; lane <= day.laneCount; lane++) {
      const r = headerRow + lane
      const laneCell = sheet.getCell(r, 1)
      laneCell.value = `Eng ${lane}`
      laneCell.font = { bold: true }
      laneCell.alignment = centerWrap
      laneCell.border = thin
      for (let c = 2; c <= 1 + day.totalHalfHours; c++) {
        sheet.getCell(r, c).border = thin
      }
    }

    // Blocks: merge the (laneStart..laneEnd) × (startHalfHour..endHalfHour-1)
    // rectangle, write the name in the anchor, fill with block colour.
    for (const blk of blocksByDay.get(day.dayIndex) ?? []) {
      const rowStart = firstLaneRow + blk.laneStart - 1
      const rowEnd = firstLaneRow + blk.laneEnd - 1
      const colStart = 2 + blk.startHalfHour
      const colEnd = 2 + blk.endHalfHour - 1
      if (rowEnd > rowStart || colEnd > colStart) {
        sheet.mergeCells(rowStart, colStart, rowEnd, colEnd)
      }
      const argb = resolveArgb(blk.colour, blk.blockId)
      const fill: ExcelJS.Fill = {
        type: 'pattern',
        pattern: 'solid',
        fgColor: { argb },
      }
      for (let r = rowStart; r <= rowEnd; r++) {
        for (let c = colStart; c <= colEnd; c++) {
          const cell = sheet.getCell(r, c)
          cell.fill = fill
          cell.border = thin
        }
      }
      const anchor = sheet.getCell(rowStart, colStart)
      anchor.value = blk.name
      anchor.alignment = centerWrap
      anchor.font = { bold: true }
    }

    // Set a slightly taller row height for readability on lane rows.
    for (let r = firstLaneRow; r <= lastLaneRow; r++) {
      sheet.getRow(r).height = 22
    }
    sheet.getRow(headerRow).height = 22

    cursor = lastLaneRow + 2 // blank separator row between days
  }
}

// ─── Helpers ─────────────────────────────────────────────────────────────

function resolveArgb(hex: string | null | undefined, seed: string): string {
  if (hex && /^#[0-9a-fA-F]{6}$/.test(hex)) {
    return 'FF' + hex.slice(1).toUpperCase()
  }
  // Deterministic palette fallback.
  let h = 2166136261 >>> 0
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return FALLBACK_PALETTE[(h >>> 0) % FALLBACK_PALETTE.length]
}

function formatTimestamp(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  )
}
