import { useId, useState } from 'react'
import type { FinanceDistribution, FinanceMonth, FinancePlot } from '../bridge/financeAnalytics'
import type { FinancePositionPage } from '../bridge/financeInvestments'

const labels: Record<string, string> = { BANK: '银行', CASH: '现金', CREDIT_CARD: '信用卡', PAYMENT_PLATFORM: '支付平台', BROKERAGE: '证券账户', CRYPTO_WALLET: '加密钱包', OTHER: '其他', STOCK: '股票', ETF: 'ETF', FUND: '基金', BOND: '债券', GOLD: '黄金', CRYPTO: '加密资产' }
export function FinanceHoldingsChart({ value, onSelect }: { value: FinancePositionPage; onSelect: (id: string) => void }) {
  const plot = value.comparison
  if (!plot || !value.records.length) return null
  const x = (n: number) => 12 + n * .0476
  const zero = x(plot.zero)
  return <section className="card" aria-label="当前页持仓成本与参考估值"><div className="card-heading"><h3>当前页持仓 · 成本与缓存参考值</h3><span className="coverage-tag">{value.records.length} 项 · CNY</span></div>
    <p className="hint">仅比较当前筛选与当前分页记录，不代表 Top Holdings 或完整组合。成本是账务事实，缓存参考值可能缺失、过期；不推导收益或收益率。</p>
    <div className="chart-legend"><span className="income-key">账务成本</span><span className="net-key">缓存参考值</span></div>
    <ul className="holdings-comparison">{value.records.map((p, i) => <li key={p.positionId}>
      <button className="finance-account-link" aria-label={`图表查看持仓 ${p.positionId}`} onClick={() => onSelect(p.positionId)}>{p.instrument.symbol} · #{p.positionId}<small>{p.quantity ?? '未知'} 单位 · {p.status}</small></button>
      <div><svg viewBox="0 0 500 48" role="img" aria-label={`${p.instrument.symbol} 成本 ${p.totalCost ?? '未知'} CNY，缓存参考 ${p.referenceValuation.value ?? '未知'} CNY，状态 ${p.referenceValuation.freshness}`}>
        <line x1={zero} x2={zero} y1="0" y2="48" className="chart-zero" />
        {(['cost', 'reference'] as const).map((key, n) => { const coordinate = plot.items[i]![key]; return coordinate === null ? null : <rect key={key} x={Math.min(zero, x(coordinate))} y={6 + n * 22} width={Math.abs(x(coordinate) - zero)} height="14" rx="3" className={`chart-bar ${n === 0 ? 'chart-income' : 'chart-net'}`} /> })}
      </svg><p className="hint finance-amount">成本 {p.totalCost ?? '未知'} · 参考 {p.referenceValuation.value ?? '未知'} CNY · {p.referenceValuation.freshness}{p.referenceValuation.warnings.some(w => w.code === 'QUOTE_STALE' || w.code === 'FX_STALE') ? ' · 含过期信息' : ''}</p></div>
    </li>)}</ul><p className="finance-plot-scale">共同坐标范围：{plot.minimum} 至 {plot.maximum} CNY；金额很小时请查看精确字符串。</p>
  </section>
}
export function FinanceDonut({ title, value, unit = '项', note, onSelect }: { title: string; value: FinanceDistribution; unit?: string; note: string; onSelect?: (key: string) => void }) {
  const [selected, setSelected] = useState('')
  const choose = (key: string) => { setSelected(key); onSelect?.(key) }
  const selection = value.items.find(s => s.key === selected)
  return <section className="card finance-donut-card" aria-label={title}>
    <div className="card-heading"><h3>{title}</h3><span className="coverage-tag">{unit === '项' ? '完整数量分母' : '完整支出分母'}</span></div>
    {value.state === 'COMPLETE' ? <><div className="finance-donut-layout">
      <svg viewBox="0 0 200 200" className="finance-donut" aria-label={`${title}，总计 ${value.total} ${unit}`} role="img">
        <circle cx="100" cy="100" r="75" className="donut-track" />
        {value.items.map((s, i) => <circle key={s.key} cx="100" cy="100" r="75" pathLength="10000" fill="none" strokeWidth={selected === s.key ? 30 : 24}
          className={`donut-slice chart-color-${i % 8}`} strokeDasharray={`${s.end - s.start} ${10000 - s.end + s.start}`} strokeDashoffset={-s.start}
          transform="rotate(-90 100 100)" onClick={() => choose(s.key)}><title>{labels[s.key] ?? s.label}：{s.value} {unit} · {s.percentage}%</title></circle>)}
        <text x="100" y="95" textAnchor="middle" className="donut-center">{unit === '项' ? value.total : 'CNY'}</text>
        <text x="100" y="119" textAnchor="middle" className="donut-caption">{unit === '项' ? '记录总数' : '支出结构'}</text>
      </svg>
      <ul className="finance-donut-legend">{value.items.map((s, i) => <li key={s.key}><button className="donut-legend-button" aria-pressed={selected === s.key} onClick={() => choose(s.key)}
        aria-label={`${title} · ${labels[s.key] ?? s.label} · ${s.value} ${unit} · ${s.percentage}%${onSelect ? ' · 查看记录' : ' · 查看详情'}`}>
        <span className={`chart-swatch chart-color-${i % 8}`} /><span>{labels[s.key] ?? s.label}<small>{s.value} {unit}</small></span><strong>{s.percentage}%</strong>
      </button></li>)}</ul>
    </div>{selection && <p className="finance-chart-selection" role="status">{labels[selection.key] ?? selection.label} · <strong>{selection.value} {unit}</strong> · {selection.percentage}% <button className="text-button" onClick={() => setSelected('')}>关闭结构详情</button></p>}
    {unit !== '项' && <p className="finance-amount">支出总计 {value.total} {unit}</p>}</>
      : <p className="dashboard-empty">{value.state === 'EMPTY' ? '此范围没有可形成占比的数据。' : '存在带符号的分类金额，保留支出汇总，不绘制占比圆环。'}</p>}
    <p className="hint">{note}</p>
  </section>
}

export function FinanceCashChart({ months, plot, selected, onSelect, label = '月度收支', initialMode = 'bars' }: { months: FinanceMonth[]; plot: FinancePlot; selected: string; onSelect: (month: string, trigger?: HTMLElement) => void; label?: string; initialMode?: 'bars' | 'net' }) {
  const [mode, setMode] = useState(initialMode), [hovered, setHovered] = useState('')
  const id = useId()
  // Only server-issued bounded coordinates enter geometry. Exact money never enters Number.
  const x = (i: number) => 32 + (i + .5) * 696 / months.length
  const y = (n: number) => 22 + n * .020
  const zero = y(plot.zero), focus = months.find(m => m.month === (hovered || selected))
  const points = months.map((m, i) => m.state === 'FUTURE' ? '' : `${x(i)},${y(plot.net[i]!)}`).filter(Boolean).join(' ')
  return <div className="finance-cash-chart">
    <div className="finance-chart-controls" role="group" aria-label={`${label}图形切换`}>
      <button className="secondary" aria-pressed={mode === 'bars'} onClick={() => setMode('bars')}>收支对比</button><button className="secondary" aria-pressed={mode === 'net'} onClick={() => setMode('net')}>净额趋势</button>
      <span className="chart-legend"><span className="income-key">收入</span><span className="expense-key">支出</span><span className="net-key">净额</span></span>
    </div>
    <div className="finance-chart-scroll" tabIndex={0} aria-label={`${label}图，可横向滚动`}>
      <svg viewBox="0 0 760 266" className="finance-month-chart" role="img" aria-label={`${label} · ${mode === 'bars' ? '分组柱状图与净额线' : '净现金流折线与零基线'}；下方月份按钮提供等价键盘操作`}>
        <defs><linearGradient id={id} x1="0" x2="0" y1="0" y2="1"><stop className="chart-net" stopOpacity=".20" /><stop offset="1" className="chart-net" stopOpacity=".02" /></linearGradient></defs>
        {plot.ticks.map((t, i) => <line key={i} className="chart-grid" x1="32" x2="728" y1={y(t.coordinate)} y2={y(t.coordinate)} />)}
        <line className="chart-zero" x1="32" x2="728" y1={zero} y2={zero} /><text className="chart-label" x="6" y={zero + 4}>0</text>
        {mode === 'net' && points && <polygon points={`${x(0)},${zero} ${points} ${x(months.filter(m => m.state !== 'FUTURE').length - 1)},${zero}`} fill={`url(#${id})`} />}
        {months.map((m, i) => <g key={m.month}>
          {m.state !== 'FUTURE' ? <g className="chart-pick" onMouseEnter={() => setHovered(m.month)} onMouseLeave={() => setHovered('')} onClick={() => onSelect(m.month)}>
            <rect x={x(i) - 25} y="20" width="50" height="206" className={selected === m.month ? 'chart-month-selected' : 'chart-hit-area'} />
            {mode === 'bars' && (['income', 'expense'] as const).map((k, j) => <rect key={k} x={x(i) - 19 + j * 20} y={Math.min(zero, y(plot[k][i]!))} width="16" height={Math.abs(zero - y(plot[k][i]!))} rx="3" className={`chart-bar chart-${k}`} />)}
            <circle cx={x(i)} cy={y(plot.net[i]!)} r={selected === m.month ? 6 : 3.5} className={`chart-point ${m.net!.startsWith('-') ? 'chart-expense' : 'chart-net'}`} />
            <title>{m.month} 收入 {m.income} CNY · 支出 {m.expense} CNY · 净额 {m.net} CNY · {m.transactionCount} 笔</title>
          </g> : <text x={x(i)} y="160" textAnchor="middle" className="chart-label">未发生</text>}
          <text x={x(i)} y="246" textAnchor="middle" className="chart-label">{m.month.slice(5)}月</text><text x={x(i)} y="261" textAnchor="middle" className="chart-year-label">{i === 0 || m.month.endsWith('-01') ? m.month.slice(0, 4) : ''}</text>
        </g>)}
        <polyline points={points} fill="none" className="chart-line chart-net" pointerEvents="none" />
      </svg>
    </div>
    <div className="finance-plot-scale"><span>比例上界 {plot.ticks[0]!.amount} CNY</span><span>下界 {plot.ticks[4]!.amount} CNY</span></div>
    <p className="finance-chart-tooltip" aria-live="polite">{focus && focus.state !== 'FUTURE' ? `${focus.month} · 收入 ${focus.income} CNY · 支出 ${focus.expense} CNY · 净额 ${focus.net} CNY · ${focus.transactionCount} 笔` : '悬停图形或选择月份，查看精确金额与流水入口。'}</p>
    <div className="cash-flow-months" role="group" aria-label="选择现金流月份">{months.map(m => <button key={m.month} className="secondary" disabled={m.state === 'FUTURE'} aria-pressed={selected === m.month}
      aria-label={m.state === 'FUTURE' ? `${m.month} 尚未发生` : `${m.month} 收入 ${m.income} CNY，支出 ${m.expense} CNY，净额 ${m.net} CNY`} onClick={event => onSelect(m.month, event.currentTarget)}>{m.month}<span>{m.state === 'FUTURE' ? '尚未发生' : selected === m.month ? '已选中' : '查看明细'}</span></button>)}</div>
  </div>
}
