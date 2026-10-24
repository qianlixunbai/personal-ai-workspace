// Decorative, deliberately separate from the financial charts below.
export function FinanceArtwork() {
  return <svg className="finance-artwork" viewBox="0 0 800 250" fill="none" aria-hidden="true" focusable="false">
    <defs>
      <linearGradient id="hero-wave" x1="100" y1="210" x2="650" y2="40" gradientUnits="userSpaceOnUse"><stop stopColor="#4b9dff" stopOpacity="0" /><stop offset=".6" stopColor="#3984ff" stopOpacity=".5" /><stop offset="1" stopColor="#b0e8ff" stopOpacity=".1" /></linearGradient>
      <linearGradient id="hero-glass" x1="505" y1="40" x2="645" y2="196" gradientUnits="userSpaceOnUse"><stop stopColor="#c3e8ff" stopOpacity=".7" /><stop offset=".5" stopColor="#267aff" stopOpacity=".6" /><stop offset="1" stopColor="#0842cf" stopOpacity=".3" /></linearGradient>
      <pattern id="hero-dots" width="12" height="12" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r="1.2" fill="#5db7ff" opacity=".5" /></pattern>
    </defs>
    <circle cx="580" cy="112" r="190" stroke="#5597ff" strokeOpacity=".17" />
    <circle cx="580" cy="112" r="125" stroke="#74baff" strokeOpacity=".2" />
    <path d="M0 245C140 210 166 25 300 124S475 256 565 139 708 25 800 70V250H0Z" fill="url(#hero-wave)" />
    <path d="M0 245C140 210 166 25 300 124S475 256 565 139 708 25 800 70" stroke="#75caff" strokeOpacity=".6" />
    <path d="M40 250C170 170 229 225 330 190S475 40 610 135 735 222 800 152" stroke="#83c7ff" strokeOpacity=".5" />
    <path d="M140 250C244 120 300 244 440 190S620 96 800 245" stroke="#7babff" strokeOpacity=".3" />
    <path d="m384 30 72 42-72 42-72-42 72-42Z" fill="url(#hero-dots)" />
    <g transform="translate(520 38) rotate(8 65 82)">
      <rect x="10" y="8" width="126" height="154" rx="17" stroke="#96d5ff" strokeOpacity=".4" />
      <rect width="126" height="154" rx="17" fill="url(#hero-glass)" stroke="#a4deff" strokeOpacity=".8" />
      <rect x="8" y="8" width="110" height="138" rx="12" stroke="#d0ebff" strokeOpacity=".35" />
      <path d="M29 119V91m28 28V68m28 51V40" stroke="#e4f6ff" strokeWidth="11" strokeLinecap="round" />
      <path d="M-13 169h153l17 14H-29l16-14Z" fill="#569dff" fillOpacity=".35" stroke="#82caff" strokeOpacity=".6" />
    </g>
    <g transform="translate(456 122) rotate(-8)"><rect width="64" height="67" rx="9" fill="url(#hero-glass)" stroke="#9dd6ff" strokeOpacity=".6" /><path d="m32 17 16 9-16 9-16-9 16-9Zm0 18v17m-16-26v17l16 9 16-9V26" stroke="#d9efff" strokeWidth="2" /></g>
  </svg>
}
