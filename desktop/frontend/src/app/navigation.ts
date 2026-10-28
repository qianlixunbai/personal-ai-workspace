export const pages = [
  { id: 'assistant', name: 'Assistant', label: '助手', group: '工作', subtitle: '提个问题，或整理一段文字。' },
  { id: 'conversations', name: 'Conversations', label: '对话', group: '工作', subtitle: '接着聊，保留思路与上下文。' },
  { id: 'translate', name: 'Translate', label: '翻译', group: '工作', subtitle: '输入原文，翻译为你需要的语言。' },
  { id: 'memory', name: 'Memory', label: '记忆', group: '资料', subtitle: '你选择保留的内容' },
  { id: 'knowledge', name: 'Knowledge', label: '知识库', group: '资料', subtitle: '导入文档，查找资料与引用来源。' },
  { id: 'finance', name: 'Finance', label: '财务', group: '业务', subtitle: '账户总览与只读管理' },
  { id: 'settings', name: 'Settings', label: '设置', group: '底部', subtitle: '模型、快捷键与本机数据。' },
] as const
export type Page = typeof pages[number]['id']
export const pageFromHash = (hash: string): Page => pages.find(page => hash === `#/${page.id}`)?.id ?? 'assistant'
