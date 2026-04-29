import { useEffect, useMemo, useRef, useState } from 'react'
import { toast, Toaster } from 'sonner'
import { getBlocks, postSchedule, reloadBlocks } from './api'
import { BlockList } from './components/BlockList'
import { ResultGrid } from './components/ResultGrid'
import { ShiftEditor, validateShiftSchedule } from './components/ShiftEditor'
import { ViolationModal } from './components/ViolationModal'
import { exportSchedule } from './exportXlsx'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { TooltipProvider } from '@/components/ui/tooltip'
import {
  Play,
  RotateCcw,
  FileSpreadsheet,
  AlertTriangle,
  Download,
  BarChart3,
} from 'lucide-react'
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
  { id: 'A_MTS', label: 'A · Greedy (MTS)' },
  { id: 'A_SPT', label: 'A · Greedy (SPT)' },
  { id: 'B_CPSAT', label: 'B · CP-SAT' },
  { id: 'C_ENHANCED', label: 'C · Enhanced' },
]

const C_WEIGHTS: CandidateCWeight[] = [0, 0.25, 0.5, 0.75, 1]

const CPSAT_BACKEND_LIMIT_SECONDS = 86400
const CLIENT_REQUEST_TIMEOUT_MS = 20 * 60 * 1000

const INITIAL_DAYS: ShiftDay[] = [
  { shifts: [{ startHour: 1, durationHours: 14, fte: 6 }] },
]

export default function App() {
  const [blocks, setBlocks] = useState<BlockDto[]>([])
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [days, setDays] = useState<ShiftDay[]>(INITIAL_DAYS)
  const [algorithm, setAlgorithm] = useState<AlgorithmId>('A_MTS')
  const [candidateCWeight, setCandidateCWeight] =
    useState<CandidateCWeight>(0.5)
  const [loading, setLoading] = useState(false)
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)
  const tickerRef = useRef<number | null>(null)
  const [result, setResult] = useState<ScheduleResponse | null>(null)
  const [scheduleError, setScheduleError] = useState<string | null>(null)
  const [violations, setViolations] = useState<LoaderViolation[] | null>(null)
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

  const shiftErrors = useMemo(() => validateShiftSchedule(days), [days])

  const noBlocksSelected = selectedIds.size === 0
  const canSchedule = !loading && !noBlocksSelected && shiftErrors.length === 0
  const showNotice = shiftErrors.length > 0 || noBlocksSelected

  const warnings = useMemo(() => {
    const w: string[] = []
    if (noBlocksSelected) w.push('Select at least one block to schedule.')
    w.push(...shiftErrors)
    return w
  }, [noBlocksSelected, shiftErrors])

  const handleReload = async () => {
    try {
      setLoading(true)
      const { status, body } = await reloadBlocks()
      if (status === 200 && body.success) {
        const blocksRes = await getBlocks()
        if (blocksRes.success) setBlocks(blocksRes.blocks)
        setSelectedIds(new Set())
        toast.success('File reloaded successfully.')
        setFileLabel(extractFilename(body.message) ?? fileLabel)
      } else {
        setViolations(body.violations ?? [])
      }
    } catch (err) {
      toast.error(`Reload error: ${String(err)}`)
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
    if (algorithm === 'B_CPSAT')
      req.cpSatTimeLimitSeconds = CPSAT_BACKEND_LIMIT_SECONDS
    if (algorithm === 'C_ENHANCED') req.candidateCWeight = candidateCWeight

    const controller = new AbortController()
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      CLIENT_REQUEST_TIMEOUT_MS,
    )
    const startedAt = performance.now()
    setElapsedMs(0)
    tickerRef.current = window.setInterval(() => {
      setElapsedMs(performance.now() - startedAt)
    }, 250)

    try {
      setLoading(true)
      const { status, body } = await postSchedule(req, controller.signal)
      if (status === 200 && (body as ScheduleResponse).success) {
        const res = body as ScheduleResponse
        setResult(res)
        toast.success(
          `Schedule complete — ${res.makespan} day${res.makespan !== 1 ? 's' : ''} makespan in ${res.runtimeMs}ms`,
        )
      } else if (status === 422) {
        setScheduleError(
          (body as ErrorResponse).errorMessage ?? 'Schedule failed (422)',
        )
      } else {
        const msg = (body as ErrorResponse).errorMessage ?? 'unknown error'
        setScheduleError(`Server error: ${status} ${msg}`)
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        const msg = `Request timed out after ${CLIENT_REQUEST_TIMEOUT_MS / 60000} minutes — try a faster algorithm or a smaller block set.`
        setScheduleError(msg)
        toast.error(msg)
      } else {
        setScheduleError(`Network error: ${String(err)}`)
      }
    } finally {
      clearTimeout(timeoutId)
      if (tickerRef.current !== null) {
        clearInterval(tickerRef.current)
        tickerRef.current = null
      }
      setElapsedMs(null)
      setLoading(false)
    }
  }

  useEffect(() => {
    return () => {
      if (tickerRef.current !== null) clearInterval(tickerRef.current)
    }
  }, [])

  const handleExport = () => {
    if (!result) return
    exportSchedule(result, blocks, algorithm)
      .then(() => toast.success('Exported .xlsx.'))
      .catch((err) => toast.error(`Export failed: ${String(err)}`))
  }

  return (
    <TooltipProvider>
      <div className="min-h-screen bg-background">
        <Toaster position="bottom-right" richColors />

        {/* Header */}
        <header className="sticky top-0 z-50 border-b bg-card shadow-sm">
          <div className="flex items-center justify-between gap-4 px-6 py-3">
            {/* Left: Logo */}
            <div className="flex items-center gap-3 shrink-0">
              <span className="text-xl font-bold tracking-wider text-primary">ASML</span>
              <span className="text-lg font-semibold text-foreground">HLS Scheduler</span>
            </div>

            {/* Center: Controls */}
            <div className="flex items-center gap-3 flex-wrap justify-center">
              <Select
                value={algorithm}
                onValueChange={(v) => setAlgorithm(v as AlgorithmId)}
              >
                <SelectTrigger className="w-[180px] bg-card">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ALGORITHMS.map((a) => (
                    <SelectItem key={a.id} value={a.id}>
                      {a.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              {algorithm === 'C_ENHANCED' && (
                <div className="flex items-center gap-1.5">
                  <label className="text-sm text-muted-foreground whitespace-nowrap">
                    Weight (w)
                  </label>
                  <Select
                    value={String(candidateCWeight)}
                    onValueChange={(v) =>
                      setCandidateCWeight(parseFloat(v) as CandidateCWeight)
                    }
                  >
                    <SelectTrigger className="w-[80px] bg-card">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {C_WEIGHTS.map((w) => (
                        <SelectItem key={w} value={String(w)}>
                          {w}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}

              <Button onClick={handleSchedule} disabled={!canSchedule} className="gap-2">
                <Play className="h-4 w-4" />
                {loading
                  ? `Scheduling… ${elapsedMs !== null ? formatElapsed(elapsedMs) : ''}`
                  : 'Schedule'}
              </Button>
            </div>

            {/* Right: File info */}
            <div className="flex items-center gap-3 shrink-0">
              <div className="flex items-center gap-2 rounded-md bg-secondary px-3 py-1.5">
                <FileSpreadsheet className="h-4 w-4 text-muted-foreground" />
                <span className="text-sm font-medium">{fileLabel}</span>
                <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                  {blocks.length} blocks
                </span>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={handleReload}
                disabled={loading}
                className="gap-1.5"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Reload
              </Button>
            </div>
          </div>
        </header>

        {/* Warning banner */}
        {showNotice && (
          <div className="mx-6 mt-3 flex items-start gap-2 rounded-lg border border-warning/30 bg-warning/10 px-4 py-2.5">
            <AlertTriangle className="h-4 w-4 text-warning mt-0.5 shrink-0" />
            <div className="text-sm text-warning-foreground">
              {warnings.map((w, i) => (
                <p key={i}>{w}</p>
              ))}
            </div>
          </div>
        )}

        {/* Bootstrap error */}
        {bootstrapError && (
          <div className="mx-6 mt-3 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-2.5 text-sm text-destructive">
            Failed to reach backend: {bootstrapError}
          </div>
        )}

        {/* Two-column workspace */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 p-6">
          <BlockList
            blocks={blocks}
            selectedIds={selectedIds}
            onChange={setSelectedIds}
          />
          <ShiftEditor days={days} onChange={setDays} />
        </div>

        {/* Results */}
        <div className="px-6 pb-6">
          <div className="rounded-lg border bg-card shadow-sm">
            {/* Result header */}
            <div className="flex items-center justify-between border-b px-4 py-3">
              <h2 className="text-sm font-semibold text-foreground">Result</h2>
              <Button
                variant="outline"
                size="sm"
                className="gap-1.5"
                disabled={!result}
                onClick={handleExport}
              >
                <Download className="h-3.5 w-3.5" />
                Export .xlsx
              </Button>
            </div>

            {/* Result body */}
            {scheduleError && (
              <div className="mx-4 mt-4 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-2.5 text-sm font-medium text-destructive">
                {scheduleError}
              </div>
            )}

            {result ? (
              <div className="p-4">
                <ResultGrid result={result} />
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center py-16 text-muted-foreground">
                <BarChart3 className="h-10 w-10 mb-3 opacity-30" />
                <p className="text-sm">No schedule generated yet.</p>
                <p className="text-xs mt-1">
                  Select blocks and configure shifts, then click Schedule.
                </p>
              </div>
            )}
          </div>
        </div>

        {/* Violation modal */}
        {violations && (
          <ViolationModal
            violations={violations}
            onClose={() => setViolations(null)}
          />
        )}
      </div>
    </TooltipProvider>
  )
}

function extractFilename(message: string): string | null {
  const m = message.match(/from\s+(.+)$/)
  return m ? m[1].trim() : null
}

function formatElapsed(ms: number): string {
  const totalSec = Math.floor(ms / 1000)
  if (totalSec < 60) return `${totalSec}s`
  const m = Math.floor(totalSec / 60)
  const s = totalSec % 60
  return `${m}:${s.toString().padStart(2, '0')}`
}
