export const pages = [
  { id: 'assistant', name: 'Assistant', label: '助手', group: '工作', subtitle: '日常问答与摘要' },
  { id: 'conversations', name: 'Conversations', label: '对话', group: '工作', subtitle: '持续对话' },
  { id: 'translate', name: 'Translate', label: '翻译', group: '工作', subtitle: '桌面翻译' },
  { id: 'memory', name: 'Memory', label: '记忆', group: '资料', subtitle: '你选择保留的内容' },
  { id: 'knowledge', name: 'Knowledge', label: '知识', group: '资料', subtitle: '明确导入的参考来源与独立恢复' },
  { id: 'finance', name: 'Finance', label: '财务', group: '业务', subtitle: '账户总览与只读管理' },
  { id: 'settings', name: 'Settings', label: '设置', group: '底部', subtitle: '状态与维护' },
] as const
export type Page = typeof pages[number]['id']
export const pageFromHash = (hash: string): Page => pages.find(page => hash === `#/${page.id}`)?.id ?? 'assistant'
