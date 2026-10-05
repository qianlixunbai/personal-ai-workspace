export const pages = [
  { id: 'assistant', name: 'Assistant', subtitle: '日常问答与摘要' },
  { id: 'conversations', name: 'Conversations', subtitle: '持续对话' },
  { id: 'memory', name: 'Memory', subtitle: '你选择保留的内容' },
  { id: 'knowledge', name: 'Knowledge', subtitle: '明确导入的参考来源与独立恢复' },
  { id: 'translate', name: 'Translate', subtitle: '桌面翻译' },
  { id: 'settings', name: 'Settings', subtitle: '状态与维护' },
] as const
export type Page = typeof pages[number]['id']
export const pageFromHash = (hash: string): Page => pages.find(page => hash === `#/${page.id}`)?.id ?? 'assistant'
