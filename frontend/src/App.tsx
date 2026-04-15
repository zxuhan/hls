import { useEffect, useMemo, useState } from 'react'
import { getBlocks, postSchedule, reloadBlocks } from './api'
import { BlockList } from './components/BlockList'
import { ResultGrid } from './components/ResultGrid'
import { ShiftEditor, validateShiftSchedule } from './components/ShiftEditor'
import { ViolationModal } from './components/ViolationModal'
import { exportSchedule } from './exportXlsx'
import type {
  AlgorithmId,
  BlockDto,
  CandidateCWeight,
  ErrorResponse,
  LoaderViolation,
  ScheduleRequest,
  ScheduleResponse,
  ShiftDay,
} from './types'

const ALGORITHMS: { id: AlgorithmId; label: string }[] = [
  { id: 'A_MTS', label: 'A · Greedy (Most Total Successors)' },
  { id: 'A_SPT', label: 'A · Greedy (Shortest Processing Time)' },
  { id: 'B_CPSAT', label: 'B · CP-SAT exact solver' },
  { id: 'C_ENHANCED', label: 'C · Domain-enhanced greedy' },
]

const C_WEIGHTS: CandidateCWeight[] = [0, 0.25, 0.5, 0.75, 1]

const INITIAL_DAYS: ShiftDay[] = [
  { shifts: [{ startHour: 1, durationHours: 8, fte: 3 }] },
]

export default function App() {
  const [blocks, setBlocks] = useState<BlockDto[]>([])
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [days, setDays] = useState<ShiftDay[]>(INITIAL_DAYS)
  const [algorithm, setAlgorithm] = useState<AlgorithmId>('A_MTS')
  const [cpSatTimeLimitSeconds, setCpSatTimeLimitSeconds] = useState(60)
  const [candidateCWeight, setCandidateCWeight] =
    useState<CandidateCWeight>(0.5)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<ScheduleResponse | null>(null)
  const [scheduleError, setScheduleError] = useState<string | null>(null)
  const [violations, setViolations] = useState<LoaderViolation[] | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [fileLabel, setFileLabel] = useState('blocks.xlsx')
  const [bootstrapError, setBootstrapError] = useState<string | null>(null)

  // Initial fetch
  useEffect(() => {
    let cancelled = false
    getBlocks()
      .then((res) => {
        if (cancelled) return
        if (res.success) setBlocks(res.blocks)
        else setBootstrapError(res.errorMessage ?? 'Failed to load blocks')
      })
      .catch((err) => {
        if (!cancelled) setBootstrapError(String(err))
      })
    return () => {
      cancelled = true
    }
  }, [])

  // Auto-dismiss toast
  useEffect(() => {
    if (!toast) return
    const t = window.setTimeout(() => setToast(null), 3000)
    return () => window.clearTimeout(t)
  }, [toast])

  const shiftErrors = useMemo(() => validateShiftSchedule(days), [days])

  const noBlocksSelected = selectedIds.size === 0
  const canSchedule = !loading && !noBlocksSelected && shiftErrors.length === 0
  const showNotice = shiftErrors.length > 0 || noBlocksSelected

  const handleReload = async () => {
    try {
      setLoading(true)
      const { status, body } = await reloadBlocks()
      if (status === 200 && body.success) {
        const blocksRes = await getBlocks()
        if (blocksRes.success) setBlocks(blocksRes.blocks)
        setSelectedIds(new Set())
        setToast('Block list changed — selections cleared')
        setFileLabel(extractFilename(body.message) ?? fileLabel)
      } else {
        setViolations(body.violations ?? [])
      }
    } catch (err) {
      setToast(`Reload error: ${String(err)}`)
    } finally {
      setLoading(false)
    }
  }

  const handleSchedule = async () => {
    setScheduleError(null)
    const req: ScheduleRequest = {
      algorithm,
      blockIds: Array.from(selectedIds),
      shiftSchedule: days,
    }
    if (algorithm === 'B_CPSAT') req.cpSatTimeLimitSeconds = cpSatTimeLimitSeconds
    if (algorithm === 'C_ENHANCED') req.candidateCWeight = candidateCWeight
    try {
      setLoading(true)
      const { status, body } = await postSchedule(req)
      if (status === 200 && (body as ScheduleResponse).success) {
        setResult(body as ScheduleResponse)
      } else if (status === 422) {
        setScheduleError(
          (body as ErrorResponse).errorMessage ?? 'Schedule failed (422)',
        )
      } else {
        const msg = (body as ErrorResponse).errorMessage ?? 'unknown error'
        setScheduleError(`Server error: ${status} ${msg}`)
      }
    } catch (err) {
      setScheduleError(`Network error: ${String(err)}`)
    } finally {
      setLoading(false)
    }
  }

  const handleExport = () => {
    if (result) exportSchedule(result, algorithm)
  }

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-left">
          <img src="/asml-logo.svg" alt="ASML" className="brand-logo" />
          <h1 className="app-title">HLS Scheduler</h1>
        </div>

        <div className="app-header-center">
          <label className="toolbar-field">
            <span>Algorithm</span>
            <select
              value={algorithm}
              onChange={(e) => setAlgorithm(e.target.value as AlgorithmId)}
            >
              {ALGORITHMS.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.label}
                </option>
              ))}
            </select>
          </label>

          {algorithm === 'B_CPSAT' && (
            <label className="toolbar-field">
              <span>Time limit (s)</span>
              <input
                type="number"
                min={1}
                value={cpSatTimeLimitSeconds}
                onChange={(e) =>
                  setCpSatTimeLimitSeconds(
                    Math.max(1, parseInt(e.target.value, 10) || 1),
                  )
                }
              />
            </label>
          )}

          {algorithm === 'C_ENHANCED' && (
            <label className="toolbar-field">
              <span>Weight (w)</span>
              <select
                value={candidateCWeight}
                onChange={(e) =>
                  setCandidateCWeight(
                    parseFloat(e.target.value) as CandidateCWeight,
                  )
                }
              >
                {C_WEIGHTS.map((w) => (
                  <option key={w} value={w}>
                    {w}
                  </option>
                ))}
              </select>
            </label>
          )}

          <button
            type="button"
            className="btn-primary"
            onClick={handleSchedule}
            disabled={!canSchedule}
          >
            {loading ? 'Scheduling…' : 'Schedule'}
          </button>
        </div>

        <div className="app-header-right">
          <span className="file-label">
            {fileLabel} · {blocks.length} blocks
          </span>
          <button
            type="button"
            className="btn-secondary"
            onClick={handleReload}
            disabled={loading}
          >
            Reload
          </button>
        </div>
      </header>

      {showNotice && (
        <div className="toolbar-notice">
          {shiftErrors.map((e, i) => (
            <div key={i}>{e}</div>
          ))}
          {noBlocksSelected && <div>Select at least one block to schedule.</div>}
        </div>
      )}

      {bootstrapError && (
        <div className="bootstrap-error">
          Failed to reach backend: {bootstrapError}
        </div>
      )}

      <main className="app-main">
        <section className="two-col">
          <BlockList
            blocks={blocks}
            selectedIds={selectedIds}
            onChange={setSelectedIds}
          />
          <ShiftEditor days={days} onChange={setDays} />
        </section>

        <section className="panel result-panel">
          <div className="panel-header">
            <span>Result</span>
            <button
              type="button"
              className="btn-secondary btn-sm"
              onClick={handleExport}
              disabled={!result}
            >
              Export .xlsx
            </button>
          </div>
          <div className="result-body">
            {scheduleError && (
              <div className="schedule-error">{scheduleError}</div>
            )}
            {result ? (
              <ResultGrid result={result} />
            ) : (
              <div className="empty">
                No schedule yet. Pick blocks, define shifts, and click Schedule.
              </div>
            )}
          </div>
        </section>
      </main>

      {violations && (
        <ViolationModal
          violations={violations}
          onClose={() => setViolations(null)}
        />
      )}

      {toast && <div className="toast">{toast}</div>}
    </div>
  )
}

// Best-effort extract of "<N> blocks from <path>" → "<path>" tail.
function extractFilename(message: string): string | null {
  const m = message.match(/from\s+(.+)$/)
  return m ? m[1].trim() : null
}
