import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { AnswerContent } from './AnswerContent'

afterEach(cleanup)

it('reads headings, lists, tables and code, and exposes the exact source without rewriting it', () => {
  const text = '# 交付结果\r\n\r\n**保留中文😀**\n\n- 第一步\n- 第二步\n\n| 项目 | 状态 |\n| --- | --- |\n| 资料 | 已保存 |\n\n```ts\nconst source = "<tag>";\n```\n\n> 仅供本项目。\n'
  const view = render(<AnswerContent key="first" text={text} />)
  expect(screen.getByRole('heading', { name: '交付结果' })).toBeTruthy()
  expect(screen.getByRole('table').textContent).toContain('已保存')
  expect(screen.getAllByRole('listitem')).toHaveLength(2)
  expect(screen.getByLabelText('回答代码块').textContent).toBe('const source = "<tag>";\n')
  fireEvent.click(screen.getByRole('button', { name: '查看原文' }))
  expect(screen.getByLabelText('回答原文').textContent).toBe(text)
  view.rerender(<AnswerContent key="first" text={text} />)
  expect(screen.getByLabelText('回答原文').textContent).toBe(text)
  view.rerender(<AnswerContent key="second" text={text} />)
  expect(screen.getByRole('table')).toBeTruthy()
  expect(screen.queryByLabelText('回答原文')).toBeNull()
})

it('keeps HTML inert and renders links, images and task lists without network or interactive targets', () => {
  const text = ['<script>alert("fixture")</script>', '<iframe src="https://example.invalid/frame"></iframe>',
    '<img src="https://example.invalid/raw" onerror="alert(1)">',
    '[官网](https://example.invalid/source)', '[本地](file:///C:/fixture.txt)', '[执行](javascript:alert%281%29)',
    '![资料图](https://example.invalid/pixel)', '![内联图](data:image/svg+xml;base64,PHN2Zz4=)',
    '- [x] 已核对\n- [ ] 待核对', '注释[^a]\n\n[^a]: 合成注释。'].join('\n\n')
  const { container } = render(<><AnswerContent text={text} /><AnswerContent text={text} /></>)
  expect(container.querySelector('a,img,script,iframe,svg,form,input,object,embed,style,link,video,audio')).toBeNull()
  expect(container.querySelector('[href],[src],[srcset],[onclick],[onerror]')).toBeNull()
  expect(screen.getAllByText('官网 (https://example.invalid/source)')).toHaveLength(2)
  expect(screen.getAllByText('[图片：资料图，未加载]')).toHaveLength(2)
  expect(screen.getAllByLabelText('已勾选')).toHaveLength(2)
  const ids = [...container.querySelectorAll('[id]')].map(element => element.id)
  expect(new Set(ids).size).toBe(ids.length)
  fireEvent.click(screen.getAllByRole('button', { name: '查看原文' })[0]!)
  expect(screen.getByLabelText('回答原文').textContent).toBe(text)
  expect(container.querySelector('script,a,img,iframe')).toBeNull()
})
