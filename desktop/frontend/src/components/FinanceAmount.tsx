/** String grouping only: never parse, round, scale, or recalculate authoritative amounts. */
export function FinanceAmount({ value, currency = '' }: { value: string | null; currency?: string }) {
  if (value === null) return <>未知 / 未提供</>
  const [integer = '', fraction] = value.split('.')
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction === undefined ? '' : `.${fraction}`)
  const suffix = currency ? ` ${currency}` : ''
  return <><span title={`${value}${suffix}`}>{grouped}{suffix}</span>{integer.replace('-', '').length > 12 && <details><summary>查看精确原值</summary><code>{value}{suffix}</code></details>}</>
}
export function FinanceDataContext({ context }: { context?: string | undefined }) {
  return <aside className="card" aria-label="Finance 数据来源"><strong>{context === 'SYNTHETIC' ? 'Synthetic Finance · 合成验收数据' : 'Finance 数据来源尚未核验'}</strong>
    <p className="hint">{context === 'SYNTHETIC' ? '数据库合成标记已核对。持仓、超大金额和旧缓存均用于验收，不代表真实账户或新获取的市场行情。' : '不能按用户名、金额大小或服务商标签推断数据真实。当前不将未知来源缓存当作真实报价。'}</p></aside>
}
export const quoteSource = (provider: string | null) => provider === 'SYNTHETIC_LEGACY' ? '旧 Finance 合成缓存 · 非 Massive 真实日终报价'
  : provider === 'TWELVE_DATA' ? 'Twelve Data 来源标签 · 实际验证 NOT RUN'
  : provider === null ? '未提供报价' : `旧 Finance 缓存 · 来源标签 ${provider} · 真实性未核验`
