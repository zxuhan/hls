import * as XLSX from 'xlsx'
import type { AlgorithmId, ScheduleResponse } from './types'

// Column layout:
//   Column A (index 0) = row label ("Day N", "Eng k")
//   Column B (index 1) = half-hour 0
//   Column C (index 2) = half-hour 1
//   ...
//   Column index `1 + halfHour` = that half-hour
// Hour h spans half-hours [(h-1)*2, h*2), so its label sits at column index `1 + (h-1)*2`.

export function exportSchedule(
  result: ScheduleResponse,
  algorithm: AlgorithmId,
): void {
  if (!result.daySummaries || !result.scheduledBlocks) return
  const daySummaries = result.daySummaries
  const scheduledBlocks = result.scheduledBlocks

  const rows: (string | number)[][] = []
  const merges: XLSX.Range[] = []

  for (let i = 0; i < daySummaries.length; i++) {
    const day = daySummaries[i]
    const dayRowOffset = rows.length

    // Row 0: "Day N"
    const dayHeader: (string | number)[] = new Array(1 + day.totalHalfHours).fill('')
    dayHeader[0] = `Day ${day.dayIndex}`
    rows.push(dayHeader)

    // Row 1: hour labels
    const hourRow: (string | number)[] = new Array(1 + day.totalHalfHours).fill('')
    const hours = Math.ceil(day.totalHalfHours / 2)
    for (let h = 1; h <= hours; h++) {
      hourRow[1 + (h - 1) * 2] = h
    }
    rows.push(hourRow)

    // Lane rows
    for (let lane = 1; lane <= day.laneCount; lane++) {
      const laneRow: (string | number)[] = new Array(1 + day.totalHalfHours).fill('')
      laneRow[0] = `Eng ${lane}`
      rows.push(laneRow)
    }

    // Place blocks for this day; record merges and write the block name
    // into the top-left cell of each merged region.
    const laneStartRow = dayRowOffset + 2 // first lane row in the sheet
    for (const blk of scheduledBlocks) {
      if (blk.dayIndex !== day.dayIndex) continue
      const r1 = laneStartRow + (blk.laneStart - 1)
      const r2 = laneStartRow + (blk.laneEnd - 1)
      const c1 = 1 + blk.startHalfHour
      const c2 = 1 + (blk.endHalfHour - 1)
      rows[r1][c1] = blk.name
      if (r1 !== r2 || c1 !== c2) {
        merges.push({ s: { r: r1, c: c1 }, e: { r: r2, c: c2 } })
      }
    }

    // Blank separator row between days
    if (i < daySummaries.length - 1) rows.push([''])
  }

  const ws = XLSX.utils.aoa_to_sheet(rows)
  ws['!merges'] = merges

  // Reasonable column widths: label column wider, half-hour columns narrow.
  const maxHalfHours = Math.max(...daySummaries.map((d) => d.totalHalfHours))
  ws['!cols'] = [
    { wch: 8 },
    ...Array.from({ length: maxHalfHours }, () => ({ wch: 4 })),
  ]

  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, 'Schedule')

  const ts = formatTimestamp(new Date())
  const filename = `hls_schedule_${algorithm}_${ts}.xlsx`

  const arr = XLSX.write(wb, { type: 'array', bookType: 'xlsx' }) as ArrayBuffer
  const blob = new Blob([arr], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function formatTimestamp(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  )
}
