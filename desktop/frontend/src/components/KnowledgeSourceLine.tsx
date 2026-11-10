import { useState } from 'react'
import { sourceTextLimits } from './knowledgeSourceText'

export function KnowledgeSourceLine({ disabled, open }: { disabled: boolean; open: (line: number) => Promise<void> }) {
  const [line, setLine] = useState('')
  const valid = /^[1-9]\d*$/.test(line) && Number(line) <= sourceTextLimits.lines
  return <details className="advanced-section">
    <summary>按原文行号定位</summary>
    <p className="hint">按所选版本的已保存纯文本行号定位，不是 Word / PDF 页码。最多读取约 10 万字、1 万行或 128 页预览；读取失败时保留当前阅读位置。</p>
    <form onSubmit={event => { event.preventDefault(); if (valid && !disabled) void open(Number(line)) }} onKeyDown={event => { if (event.key === 'Enter' && event.nativeEvent.isComposing) event.preventDefault() }}>
      <label htmlFor="knowledge-source-line">原文行号</label>
      <input id="knowledge-source-line" inputMode="numeric" autoComplete="off" maxLength={5} value={line} disabled={disabled} onChange={event => setLine(event.target.value)} />
      <button type="submit" disabled={disabled || !valid}>定位原文行</button>
    </form>
  </details>
}
