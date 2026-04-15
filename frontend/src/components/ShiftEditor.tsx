import type { Shift, ShiftDay } from '../types'

interface Props {
  days: ShiftDay[]
  onChange: (next: ShiftDay[]) => void
}

// Validation helper — exported so App.tsx can compute disabled state
// for the Schedule button without importing the component.
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
        errors.push(`Day ${di + 1} · Shift ${si + 1}: start must be an integer ≥ 1.`)
      }
      if (!Number.isInteger(s.durationHours) || s.durationHours < 1) {
        errors.push(
          `Day ${di + 1} · Shift ${si + 1}: duration must be an integer ≥ 1.`,
        )
      }
      if (!Number.isInteger(s.fte) || s.fte < 1) {
        errors.push(`Day ${di + 1} · Shift ${si + 1}: FTE must be an integer ≥ 1.`)
      }
    })
  })
  return errors
}

export function ShiftEditor({ days, onChange }: Props) {
  const updateShift = (
    dayIdx: number,
    shiftIdx: number,
    patch: Partial<Shift>,
  ) => {
    const next = days.map((d, i) =>
      i === dayIdx
        ? {
            shifts: d.shifts.map((s, j) => (j === shiftIdx ? { ...s, ...patch } : s)),
          }
        : d,
    )
    onChange(next)
  }

  const addShift = (dayIdx: number) => {
    const next = days.map((d, i) =>
      i === dayIdx
        ? { shifts: [...d.shifts, { startHour: 1, durationHours: 8, fte: 3 }] }
        : d,
    )
    onChange(next)
  }

  const removeShift = (dayIdx: number, shiftIdx: number) => {
    const day = days[dayIdx]
    if (day.shifts.length === 1) {
      // Removing the last shift on a day removes the day itself.
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

  const addDay = () => {
    onChange([...days, { shifts: [{ startHour: 1, durationHours: 8, fte: 3 }] }])
  }

  return (
    <div className="panel shift-editor">
      <div className="panel-header">
        <span>Shift Schedule</span>
        <span className="panel-header-meta">
          {days.length} day{days.length === 1 ? '' : 's'}
        </span>
      </div>
      <div className="shift-editor-body">
        {days.map((day, di) => (
          <div className="shift-day" key={di}>
            <div className="shift-day-header">
              <span className="shift-day-label">Day {di + 1}</span>
              <button
                type="button"
                className="btn-secondary btn-sm"
                onClick={() => addShift(di)}
              >
                + Add shift
              </button>
            </div>
            <div className="shift-rows">
              {day.shifts.map((s, si) => (
                <div className="shift-row" key={si}>
                  <span className="shift-index">Shift {si + 1}</span>
                  <label>
                    start
                    <input
                      type="number"
                      min={1}
                      value={s.startHour}
                      onChange={(e) =>
                        updateShift(di, si, {
                          startHour: parseIntOr(e.target.value, 0),
                        })
                      }
                    />
                  </label>
                  <label>
                    dur
                    <input
                      type="number"
                      min={1}
                      value={s.durationHours}
                      onChange={(e) =>
                        updateShift(di, si, {
                          durationHours: parseIntOr(e.target.value, 0),
                        })
                      }
                    />
                  </label>
                  <label>
                    fte
                    <input
                      type="number"
                      min={1}
                      value={s.fte}
                      onChange={(e) =>
                        updateShift(di, si, { fte: parseIntOr(e.target.value, 0) })
                      }
                    />
                  </label>
                  <button
                    type="button"
                    className="btn-icon"
                    onClick={() => removeShift(di, si)}
                    aria-label="Remove shift"
                    title="Remove shift"
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          </div>
        ))}
        <button type="button" className="btn-secondary" onClick={addDay}>
          + Add day
        </button>
      </div>
    </div>
  )
}

function parseIntOr(s: string, fallback: number): number {
  const n = parseInt(s, 10)
  return Number.isFinite(n) ? n : fallback
}
