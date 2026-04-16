import { useMemo, useState } from 'react'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Search } from 'lucide-react'
import { cn } from '@/lib/utils'
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
      (b) =>
        b.id.toLowerCase().includes(q) || b.name.toLowerCase().includes(q),
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
    <div className="flex flex-col rounded-lg border bg-card shadow-sm">
      {/* Header */}
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-semibold text-foreground">Blocks</h2>
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            {selectedIds.size}/{blocks.length}
          </span>
        </div>
        <div className="flex gap-1.5">
          <Button
            variant="ghost"
            size="sm"
            onClick={selectAllFiltered}
            disabled={filtered.length === 0}
            className="text-xs h-7"
          >
            Select all
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={clearAllFiltered}
            disabled={filtered.length === 0}
            className="text-xs h-7"
          >
            Clear all
          </Button>
        </div>
      </div>

      {/* Search */}
      <div className="relative px-4 py-2 border-b">
        <Search className="absolute left-6 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
        <Input
          placeholder="Filter by ID or name..."
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="pl-8 h-8 text-sm bg-secondary/50"
        />
      </div>

      {/* List */}
      <ScrollArea className="h-[380px]">
        <div className="divide-y">
          {filtered.length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">
              No blocks match your filter.
            </div>
          ) : (
            filtered.map((b) => {
              const checked = selectedIds.has(b.id)
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
                    onCheckedChange={() => toggle(b.id)}
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
          )}
        </div>
      </ScrollArea>
    </div>
  )
}
