import { useMemo, useState } from 'react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Search } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { BlockDto, PartDto } from '../types'

export type SelectionMode = 'blocks' | 'parts'

interface Props {
  blocks: BlockDto[]
  parts: PartDto[]
  mode: SelectionMode
  onModeChange: (next: SelectionMode) => void
  selectedBlockIds: Set<string>
  onBlockSelectionChange: (next: Set<string>) => void
  selectedPartIds: Set<string>
  onPartSelectionChange: (next: Set<string>) => void
}

export function BlockList({
  blocks,
  parts,
  mode,
  onModeChange,
  selectedBlockIds,
  onBlockSelectionChange,
  selectedPartIds,
  onPartSelectionChange,
}: Props) {
  const [query, setQuery] = useState('')
  const partsAvailable = parts.length > 0

  const filteredBlocks = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return blocks
    return blocks.filter(
      (b) =>
        b.id.toLowerCase().includes(q) || b.name.toLowerCase().includes(q),
    )
  }, [blocks, query])

  const filteredParts = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return parts
    return parts.filter((p) => p.id.toLowerCase().includes(q))
  }, [parts, query])

  // Effective blocks resolved from selected parts (deduplicated). Used both
  // for the header counter and to share an immediate visual signal of what
  // gets sent to the algorithm in parts mode.
  const effectiveBlockCount = useMemo(() => {
    if (mode !== 'parts') return 0
    const set = new Set<string>()
    for (const p of parts) {
      if (selectedPartIds.has(p.id)) {
        for (const id of p.blockIds) set.add(id)
      }
    }
    return set.size
  }, [mode, parts, selectedPartIds])

  const toggleBlock = (id: string) => {
    const next = new Set(selectedBlockIds)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    onBlockSelectionChange(next)
  }

  const togglePart = (id: string) => {
    const next = new Set(selectedPartIds)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    onPartSelectionChange(next)
  }

  const selectAllFiltered = () => {
    if (mode === 'blocks') {
      const next = new Set(selectedBlockIds)
      for (const b of filteredBlocks) next.add(b.id)
      onBlockSelectionChange(next)
    } else {
      const next = new Set(selectedPartIds)
      for (const p of filteredParts) next.add(p.id)
      onPartSelectionChange(next)
    }
  }

  const clearAllFiltered = () => {
    if (mode === 'blocks') {
      const next = new Set(selectedBlockIds)
      for (const b of filteredBlocks) next.delete(b.id)
      onBlockSelectionChange(next)
    } else {
      const next = new Set(selectedPartIds)
      for (const p of filteredParts) next.delete(p.id)
      onPartSelectionChange(next)
    }
  }

  const filteredCount = mode === 'blocks' ? filteredBlocks.length : filteredParts.length

  return (
    <div className="flex flex-col rounded-lg border bg-card shadow-sm">
      {/* Header */}
      <div className="flex flex-col gap-2 border-b px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            {mode === 'blocks' ? (
              <>
                <h2 className="text-sm font-semibold text-foreground">Blocks</h2>
                <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                  {selectedBlockIds.size}/{blocks.length}
                </span>
              </>
            ) : (
              <>
                <h2 className="text-sm font-semibold text-foreground">Parts</h2>
                <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                  {selectedPartIds.size}/{parts.length}
                </span>
                <span className="text-xs text-muted-foreground">
                  → {effectiveBlockCount} block{effectiveBlockCount !== 1 ? 's' : ''}
                </span>
              </>
            )}
          </div>
          <div className="flex gap-1.5">
            <Button
              variant="ghost"
              size="sm"
              onClick={selectAllFiltered}
              disabled={filteredCount === 0}
              className="text-xs h-7"
            >
              Select all
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={clearAllFiltered}
              disabled={filteredCount === 0}
              className="text-xs h-7"
            >
              Clear all
            </Button>
          </div>
        </div>

        {/* Mode tabs */}
        <div className="inline-flex w-fit rounded-md border bg-secondary/40 p-0.5">
          <button
            type="button"
            onClick={() => onModeChange('blocks')}
            className={cn(
              'px-3 py-1 text-xs font-medium rounded transition-colors',
              mode === 'blocks'
                ? 'bg-card text-foreground shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            Blocks
          </button>
          <button
            type="button"
            onClick={() => onModeChange('parts')}
            disabled={!partsAvailable}
            title={
              partsAvailable
                ? undefined
                : 'No Blocks_Parts sheet found in the loaded workbook'
            }
            className={cn(
              'px-3 py-1 text-xs font-medium rounded transition-colors',
              mode === 'parts'
                ? 'bg-card text-foreground shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
              !partsAvailable && 'opacity-50 cursor-not-allowed',
            )}
          >
            Parts
          </button>
        </div>
      </div>

      {/* Search */}
      <div className="relative px-4 py-2 border-b">
        <Search className="absolute left-6 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder={
            mode === 'blocks'
              ? 'Filter by ID or name...'
              : 'Filter by part name...'
          }
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="pl-8 h-8 text-sm bg-secondary/50"
        />
      </div>

      {/* List */}
      <ScrollArea className="h-[380px]">
        <div className="divide-y">
          {filteredCount === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">
              {mode === 'blocks'
                ? 'No blocks match your filter.'
                : partsAvailable
                  ? 'No parts match your filter.'
                  : 'No parts available — add a Blocks_Parts sheet to your workbook.'}
            </div>
          ) : mode === 'blocks' ? (
            filteredBlocks.map((b) => {
              const checked = selectedBlockIds.has(b.id)
              return (
                <label
                  key={b.id}
                  className={cn(
                    'flex items-center gap-3 px-4 py-2.5 cursor-pointer transition-colors hover:bg-accent/50',
                    checked && 'bg-accent border-l-2 border-l-primary',
                  )}
                >
                  <Checkbox
                    checked={checked}
                    onCheckedChange={() => toggleBlock(b.id)}
                  />
                  <span className="font-mono text-xs text-muted-foreground w-[72px] shrink-0">
                    {b.id}
                  </span>
                  <span className="text-sm font-medium flex-1 truncate">
                    {b.name}
                  </span>
                  <span className="rounded-full bg-secondary px-2 py-0.5 text-xs text-muted-foreground">
                    {b.durationHalfHours / 2}h
                  </span>
                  <span className="rounded-full bg-secondary px-2 py-0.5 text-xs text-muted-foreground">
                    {b.fteRequirement} FTE
                  </span>
                </label>
              )
            })
          ) : (
            filteredParts.map((p) => {
              const checked = selectedPartIds.has(p.id)
              const count = p.blockIds.length
              return (
                <label
                  key={p.id}
                  className={cn(
                    'flex items-center gap-3 px-4 py-2.5 cursor-pointer transition-colors hover:bg-accent/50',
                    checked && 'bg-accent border-l-2 border-l-primary',
                    count === 0 && 'opacity-60',
                  )}
                >
                  <Checkbox
                    checked={checked}
                    onCheckedChange={() => togglePart(p.id)}
                    disabled={count === 0}
                  />
                  <span className="font-mono text-xs text-muted-foreground w-[120px] shrink-0">
                    {p.id}
                  </span>
                  <span className="text-sm flex-1 truncate text-muted-foreground">
                    {count === 0
                      ? 'no blocks'
                      : p.blockIds.slice(0, 6).join(', ') +
                        (count > 6 ? `, +${count - 6}` : '')}
                  </span>
                  <span className="rounded-full bg-secondary px-2 py-0.5 text-xs text-muted-foreground">
                    {count} block{count !== 1 ? 's' : ''}
                  </span>
                </label>
              )
            })
          )}
        </div>
      </ScrollArea>
    </div>
  )
}
