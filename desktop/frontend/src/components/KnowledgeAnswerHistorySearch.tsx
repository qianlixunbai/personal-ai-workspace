import { useRef } from 'react'
import { isKnowledgeAnswerSearchQuery } from '../bridge/knowledgeAnswer'

export function KnowledgeAnswerHistorySearch({ id, value, change, run, disabled }: {
  id: string; value: string; change: (value: string) => void; run: (query: string) => void; disabled: boolean
}) {
  const composing = useRef(false), valid = isKnowledgeAnswerSearchQuery(value)
  return <form onSubmit={event => { event.preventDefault(); if (!disabled && !composing.current && valid) run(value) }}>
    <label htmlFor={id}>问答历史关键词</label>
    <input id={id} value={value} disabled={disabled} onChange={event => change(event.target.value)}
      onCompositionStart={() => { composing.current = true }} onCompositionEnd={() => { composing.current = false }}
      onKeyDown={event => { if (event.key === 'Enter' && (composing.current || event.nativeEvent.isComposing)) event.preventDefault() }} />
    <button type="submit" disabled={disabled || !valid}>查找已保存问答</button>
    <p className="hint">输入 1–128 个字符，按连续原文匹配已保存的问题、检索关键词或回答正文，区分大小写；不会重新生成回答。{value && !valid ? '当前输入为空白、过长或含无效字符。' : ''}</p>
  </form>
}
