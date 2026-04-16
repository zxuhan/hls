import { Button } from '@/components/ui/button'
import { X } from 'lucide-react'
import type { LoaderViolation } from '../types'

interface Props {
  violations: LoaderViolation[]
  onClose: () => void
}

export function ViolationModal({ violations, onClose }: Props) {
  return (
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center bg-black/35 p-5"
      onClick={onClose}
    >
      <div
        className="w-full max-w-[800px] max-h-[80vh] flex flex-col rounded-lg border bg-card shadow-lg"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b px-4 py-3">
          <span className="text-sm font-semibold text-foreground">
            Reload failed — {violations.length} violation
            {violations.length === 1 ? '' : 's'}
          </span>
          <Button
            variant="ghost"
            size="sm"
            className="h-7 w-7 p-0"
            onClick={onClose}
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-4 py-3">
          <p className="mb-3 text-sm text-muted-foreground">
            The previous dataset is still loaded. Fix the workbook and reload
            again.
          </p>
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr>
                <th className="border-b bg-accent px-3 py-2 text-left font-semibold text-foreground">
                  Sheet
                </th>
                <th className="border-b bg-accent px-3 py-2 text-left font-semibold text-foreground">
                  Cell
                </th>
                <th className="border-b bg-accent px-3 py-2 text-left font-semibold text-foreground">
                  Code
                </th>
                <th className="border-b bg-accent px-3 py-2 text-left font-semibold text-foreground">
                  Message
                </th>
              </tr>
            </thead>
            <tbody>
              {violations.map((v, i) => (
                <tr key={i} className={i % 2 === 0 ? '' : 'bg-secondary/50'}>
                  <td className="border-b px-3 py-2">{v.sheet}</td>
                  <td className="border-b px-3 py-2">{v.cellRef}</td>
                  <td className="border-b px-3 py-2 font-mono text-xs font-semibold text-destructive">
                    {v.code}
                  </td>
                  <td className="border-b px-3 py-2">{v.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Footer */}
        <div className="flex justify-end border-t bg-secondary/30 px-4 py-3">
          <Button onClick={onClose}>Close</Button>
        </div>
      </div>
    </div>
  )
}
