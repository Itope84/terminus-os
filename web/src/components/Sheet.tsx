// Bottom sheet: tap outside to close.
import type { ReactNode } from 'react';

export function Sheet({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode }) {
  if (!open) return null;
  return (
    <div className="scrim" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="grab" />
        {children}
      </div>
    </div>
  );
}

export function Opt({ title, sub, onClick, right, disabled, danger }: { title: ReactNode; sub?: ReactNode; onClick?: () => void; right?: ReactNode; disabled?: boolean; danger?: boolean }) {
  return (
    <div className={`opt${disabled ? ' dis' : ''}${danger ? ' danger' : ''}`} onClick={disabled ? undefined : onClick}>
      <div className="grow">
        {title}
        {sub ? <small>{sub}</small> : null}
      </div>
      {right}
    </div>
  );
}
