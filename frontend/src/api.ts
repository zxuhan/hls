import type {
  BlockListResponse,
  ErrorResponse,
  ReloadResponse,
  ScheduleRequest,
  ScheduleResponse,
} from './types'

export async function getBlocks(): Promise<BlockListResponse> {
  const res = await fetch('/api/blocks')
  if (!res.ok) {
    throw new Error(`GET /api/blocks failed: ${res.status} ${res.statusText}`)
  }
  return (await res.json()) as BlockListResponse
}

export async function reloadBlocks(): Promise<{
  status: number
  body: ReloadResponse
}> {
  const res = await fetch('/api/reload', { method: 'POST' })
  const body = (await res.json()) as ReloadResponse
  return { status: res.status, body }
}

export async function postSchedule(
  req: ScheduleRequest,
  signal?: AbortSignal,
): Promise<{
  status: number
  body: ScheduleResponse | ErrorResponse
}> {
  const res = await fetch('/api/schedule', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
    signal,
  })
  const body = (await res.json()) as ScheduleResponse | ErrorResponse
  return { status: res.status, body }
}
