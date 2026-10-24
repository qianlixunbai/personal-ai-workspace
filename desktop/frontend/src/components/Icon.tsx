// Local SVG primitives: no font, network, sprite injection or icon runtime.
const paths = {
  assistant: 'M9 3h6l1 4 4 1v8l-4 1-1 4H9l-1-4-4-1V8l4-1 1-4Z M9 10v2m6-2v2m-6 3h6',
  conversations: 'M21 11a8 8 0 0 1-8 8H7l-5 3 2-6a8 8 0 1 1 17-5Z M8 11h.01M12 11h.01M16 11h.01',
  memory: 'M20 6c0 2-4 4-8 4S4 8 4 6s4-4 8-4 8 2 8 4ZM4 6v6c0 2 4 4 8 4s8-2 8-4V6M4 12v6c0 2 4 4 8 4s8-2 8-4v-6',
  knowledge: 'M4 3h13l3 3v15H4V3Zm4 4h5m-5 4h8m-8 4h8M1 6v14a4 4 0 0 0 4 4',
  translate: 'M3 4h12M9 2v2M5 8c1 4 4 6 8 8M13 4c0 5-4 9-10 12M14 21l4-10 4 10m-6-4h4',
  finance: 'M4 20v-7m5 7V8m5 12V4m5 16V2',
  settings: 'm9 3 6 0 1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1 1-3ZM16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  status: 'M3 12h4l3-8 4 16 3-8h4',
  moon: 'M20 15A9 9 0 0 1 9 3a9 9 0 1 0 11 12Z',
  sun: 'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM12 1v2m0 18v2M1 12h2m18 0h2M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2',
  cube: 'm12 2 9 5v10l-9 5-9-5V7l9-5Zm0 10v10M3 7l9 5 9-5M8 4l9 5',
  dashboard: 'M3 3h7v7H3V3Zm11 0h7v7h-7V3ZM3 14h7v7H3v-7Zm11 0h7v7h-7v-7Z',
  accounts: 'M3 6h18v15H3V6Zm2 0V3h13M16 12h5v5h-5v-5Z',
  transactions: 'M4 7h15m-4-4 4 4-4 4M20 17H5m4-4-4 4 4 4',
  assets: 'm12 3 9 5-9 5-9-5 9-5ZM3 12l9 5 9-5M3 16l9 5 9-5',
  investments: 'M3 21h18M6 17v-5h4v5m4 0V7h4v10M4 8l6-5 4 2 6-4',
  portfolio: 'M4 7h16v14H4V7Zm4 0V3h8v4M8 11v6m4-4v4m4-7v7',
  history: 'M3 10a9 9 0 1 1 2 8M3 4v6h6M12 7v5l3 2',
  imports: 'M14 2H4v20h16V8l-6-6Zm0 0v6h6M8 14h8m-3-3 3 3-3 3',
  budgets: 'M4 3h16v18H4V3Zm3 4h10M7 11h2m4 0h4M7 15h2m4 0h4',
  coins: 'M20 6c0 2-3 3-6 3s-6-1-6-3 3-3 6-3 6 1 6 3Zm0 0v5c0 2-3 3-6 3M4 10c0 2 3 3 6 3s6-1 6-3M4 10v5c0 2 3 3 6 3s6-1 6-3v-5M4 15v4c0 2 3 3 6 3s6-1 6-3v-4',
  income: 'M6 18 18 6M7 6h11v11',
  expense: 'm6 6 12 12M7 18h11V7',
  arrow: 'M4 12h16m-6-6 6 6-6 6',
  refresh: 'M20 7a9 9 0 0 0-15-2L2 8m0-6v6h6m-4 9a9 9 0 0 0 15 2l3-3m0 6v-6h-6',
  shield: 'm12 2 8 3v7c0 5-8 10-8 10S4 17 4 12V5l8-3Zm-4 9 3 3 5-6',
} as const

export type IconName = keyof typeof paths
export function Icon({ name, className = '' }: { name: IconName; className?: string }) {
  return <svg className={`icon ${className}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d={paths[name]} /></svg>
}
