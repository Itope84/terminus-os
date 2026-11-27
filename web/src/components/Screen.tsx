// A full-height page with the palette as CSS variables (thread.css and ui.css read them).
import type { CSSProperties, ReactNode } from 'react';
import { usePalette } from '../lib/theme';
import './ui.css';

export function useCssVars(): CSSProperties {
  const p = usePalette();
  return Object.fromEntries(Object.entries(p).map(([k, v]) => [`--${k}`, v])) as CSSProperties;
}

export function Screen({ children }: { children: ReactNode }) {
  const vars = useCssVars();
  return (
    <div className="screen" style={vars}>
      {children}
    </div>
  );
}
