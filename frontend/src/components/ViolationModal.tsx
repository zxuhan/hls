import type { LoaderViolation } from '../types'

interface Props {
  violations: LoaderViolation[]
  onClose: () => void
}

export function ViolationModal({ violations, onClose }: Props) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal panel"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="panel-header">
          <span>Reload failed — {violations.length} violation{violations.length === 1 ? '' : 's'}</span>
          <button type="button" className="btn-icon" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        <div className="modal-body">
          <p className="modal-help">
            The previous dataset is still loaded. Fix the workbook and reload again.
          </p>
          <table className="violation-table">
            <thead>
              <tr>
                <th>Sheet</th>
                <th>Cell</th>
                <th>Code</th>
                <th>Message</th>
              </tr>
            </thead>
            <tbody>
              {violations.map((v, i) => (
                <tr key={i}>
                  <td>{v.sheet}</td>
                  <td>{v.cellRef}</td>
                  <td className="violation-code">{v.code}</td>
                  <td>{v.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn-primary" onClick={onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
