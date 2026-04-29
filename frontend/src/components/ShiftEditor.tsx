import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Plus, X, CalendarDays } from 'lucide-react'
import type { Shift, ShiftDay } from '../types'

const DEFAULT_SHIFT: Shift = { startHour: 1, durationHours: 14, fte: 6 }
const MAX_BULK_DAYS = 50

interface Props {
  days: ShiftDay[]
  onChange: (next: ShiftDay[]) => void
}

// Validation helper — exported so App.tsx can compute disabled state
export function validateShiftSchedule(days: ShiftDay[]): string[] {
  const errors: string[] = []
  if (days.length === 0) {
    errors.push('Add at least one day.')
    return errors
  }
  days.forEach((d, di) => {
    if (d.shifts.length === 0) {
      errors.push(`Day ${di + 1}: must have at least one shift.`)
      return
    }
    d.shifts.forEach((s, si) => {
      if (!Number.isInteger(s.startHour) || s.startHour < 1) {
        errors.push(
          `Day ${di + 1} · Shift ${si + 1}: start must be an integer >= 1.`,
        )
      }
      if (!Number.isInteger(s.durationHours) || s.durationHours < 1) {
        errors.push(
          `Day ${di + 1} · Shift ${si + 1}: duration must be an integer >= 1.`,
        )
      }
      if (!Number.isInteger(s.fte) || s.fte < 1) {
        errors.push(
          `Day ${di + 1} · Shift ${si + 1}: FTE must be an integer >= 1.`,
        )
      }
    })
  })
  return errors
}

export function ShiftEditor({ days, onChange }: Props) {
  const [bulkCount, setBulkCount] = useState(10)

  const updateShift = (
    dayIdx: number,
    shiftIdx: number,
    patch: Partial<Shift>,
  ) => {
    const next = days.map((d, i) =>
      i === dayIdx
        ? {
            shifts: d.shifts.map((s, j) =>
              j === shiftIdx ? { ...s, ...patch } : s,
            ),
          }
        : d,
    )
    onChange(next)
  }

  const addShift = (dayIdx: number) => {
    const next = days.map((d, i) =>
      i === dayIdx ? { shifts: [...d.shifts, { ...DEFAULT_SHIFT }] } : d,
    )
    onChange(next)
  }

  const removeShift = (dayIdx: number, shiftIdx: number) => {
    const day = days[dayIdx]
    if (day.shifts.length === 1) {
      onChange(days.filter((_, i) => i !== dayIdx))
      return
    }
    const next = days.map((d, i) =>
      i === dayIdx
        ? { shifts: d.shifts.filter((_, j) => j !== shiftIdx) }
        : d,
    )
    onChange(next)
  }

  const addDays = (n: number) => {
    const clamped = Math.min(MAX_BULK_DAYS, Math.max(1, n))
    const fresh: ShiftDay[] = Array.from({ length: clamped }, () => ({
      shifts: [{ ...DEFAULT_SHIFT }],
    }))
    onChange([...days, ...fresh])
  }

  return (
    <div className="flex flex-col rounded-lg border bg-card shadow-sm">
      {/* Header */}
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-semibold text-foreground">
            Shift Schedule
          </h2>
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            {days.length} day{days.length !== 1 ? 's' : ''}
          </span>
        </div>
      </div>

      <ScrollArea className="h-[380px]">
        <div className="p-4 space-y-3">
          {days.map((day, di) => (
            <div
              key={di}
              className="rounded-md border bg-secondary/30 overflow-hidden"
            >
              <div className="flex items-center justify-between bg-secondary/50 px-3 py-2">
                <div className="flex items-center gap-2">
                  <CalendarDays className="h-4 w-4 text-primary" />
                  <span className="text-sm font-semibold">Day {di + 1}</span>
                </div>
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-7 text-xs gap-1"
                    onClick={() => addShift(di)}
                  >
                    <Plus className="h-3 w-3" /> Add shift
                  </Button>
                  {days.length > 1 && (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 w-7 p-0 text-destructive hover:text-destructive"
                      onClick={() => {
                        onChange(days.filter((_, i) => i !== di))
                      }}
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  )}
                </div>
              </div>

              <div className="p-2 space-y-1.5">
                {day.shifts.length === 0 && (
                  <p className="text-xs text-muted-foreground text-center py-2">
                    No shifts added yet.
                  </p>
                )}
                {day.shifts.map((s, si) => (
                  <div
                    key={si}
                    className="flex items-center gap-2 bg-card rounded px-2 py-1.5"
                  >
                    <div className="flex items-center gap-1">
                      <label className="text-xs text-muted-foreground w-10">
                        Start
                      </label>
                      <Input
                        type="number"
                        value={s.startHour}
                        onChange={(e) =>
                          updateShift(di, si, {
                            startHour: parseIntOr(e.target.value, 0),
                          })
                        }
                        className="w-16 h-7 text-xs"
                        min={1}
                      />
                    </div>
                    <div className="flex items-center gap-1">
                      <label className="text-xs text-muted-foreground w-10">
                        Dur.
                      </label>
                      <Input
                        type="number"
                        value={s.durationHours}
                        onChange={(e) =>
                          updateShift(di, si, {
                            durationHours: parseIntOr(e.target.value, 0),
                          })
                        }
                        className="w-16 h-7 text-xs"
                        min={1}
                      />
                    </div>
                    <div className="flex items-center gap-1">
                      <label className="text-xs text-muted-foreground w-10">
                        FTE
                      </label>
                      <Input
                        type="number"
                        value={s.fte}
                        onChange={(e) =>
                          updateShift(di, si, {
                            fte: parseIntOr(e.target.value, 0),
                          })
                        }
                        className="w-16 h-7 text-xs"
                        min={1}
                      />
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 w-7 p-0 ml-auto text-destructive hover:text-destructive"
                      onClick={() => removeShift(di, si)}
                    >
                      <X className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                ))}
              </div>
            </div>
          ))}

          <div className="flex items-center gap-2">
            <Input
              type="number"
              value={bulkCount}
              onChange={(e) =>
                setBulkCount(
                  Math.min(
                    MAX_BULK_DAYS,
                    Math.max(1, parseIntOr(e.target.value, 1)),
                  ),
                )
              }
              className="w-16 h-9"
              min={1}
              max={MAX_BULK_DAYS}
            />
            <Button
              variant="outline"
              className="flex-1 border-dashed gap-1.5 text-muted-foreground"
              onClick={() => addDays(bulkCount)}
              disabled={bulkCount < 1 || bulkCount > MAX_BULK_DAYS}
            >
              <Plus className="h-4 w-4" /> Add {bulkCount} day
              {bulkCount === 1 ? '' : 's'}
            </Button>
          </div>
        </div>
      </ScrollArea>
    </div>
  )
}

function parseIntOr(s: string, fallback: number): number {
  const n = parseInt(s, 10)
  return Number.isFinite(n) ? n : fallback
}
