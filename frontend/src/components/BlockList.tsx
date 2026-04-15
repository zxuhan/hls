import { useMemo, useState } from 'react'
import type { BlockDto } from '../types'

interface Props {
  blocks: BlockDto[]
  selectedIds: Set<string>
  onChange: (next: Set<string>) => void
}

export function BlockList({ blocks, selectedIds, onChange }: Props) {
  const [query, setQuery] = useState('')

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return blocks
    return blocks.filter(
      (b) => b.id.toLowerCase().includes(q) || b.name.toLowerCase().includes(q),
    )
  }, [blocks, query])

  const toggle = (id: string) => {
    const next = new Set(selectedIds)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    onChange(next)
  }

  const selectAllFiltered = () => {
    const next = new Set(selectedIds)
    for (const b of filtered) next.add(b.id)
    onChange(next)
  }

  const clearAllFiltered = () => {
    const next = new Set(selectedIds)
    for (const b of filtered) next.delete(b.id)
    onChange(next)
  }

  return (
    <div className="panel block-list">
      <div className="panel-header">
        <span>Blocks ({blocks.length})</span>
        <span className="panel-header-meta">
          {selectedIds.size} selected
        </span>
      </div>
      <div className="block-list-controls">
        <input
          type="search"
          placeholder="Search by ID or name…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="search-input"
        />
        <button
          type="button"
          className="btn-secondary btn-sm"
          onClick={selectAllFiltered}
          disabled={filtered.length === 0}
        >
          Select all
        </button>
        <button
          type="button"
          className="btn-secondary btn-sm"
          onClick={clearAllFiltered}
          disabled={filtered.length === 0}
        >
          Clear all
        </button>
      </div>
      <div className="block-list-scroll">
        {filtered.length === 0 ? (
          <div className="empty">No blocks match.</div>
        ) : (
          filtered.map((b) => {
            const checked = selectedIds.has(b.id)
            return (
              <label
                key={b.id}
                className={'block-row' + (checked ? ' selected' : '')}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggle(b.id)}
                />
                <span className="block-id">{b.id}</span>
                <span className="block-name">{b.name}</span>
                <span className="block-meta">
                  ({b.durationHalfHours / 2}h, {b.fteRequirement} FTE)
                </span>
              </label>
            )
          })
        )}
      </div>
    </div>
  )
}
