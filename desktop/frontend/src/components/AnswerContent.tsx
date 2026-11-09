import { memo, useId, useState } from 'react'
import Markdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'

// Only generated Markdown nodes reach these renderers. Raw HTML stays text;
// links and images never create navigable or network-loading DOM elements.
const components: Components = {
  a: ({ children, href }) => <span className="answer-link">{children}{href && !href.startsWith('#') && <> ({href})</>}</span>,
  img: ({ alt }) => <span className="answer-image">[图片{alt ? `：${alt}` : ''}，未加载]</span>,
  input: ({ checked }) => <span role="img" aria-label={checked ? '已勾选' : '未勾选'}>{checked ? '☑' : '☐'}</span>,
  h1: ({ children }) => <h3>{children}</h3>,
  // Footnote headings use a parser-wide ID; omit it because markers are inert text.
  h2: ({ children }) => <h4>{children}</h4>,
  h3: ({ children }) => <h5>{children}</h5>,
  h4: ({ children }) => <h6>{children}</h6>,
  h5: ({ children }) => <h6>{children}</h6>,
  h6: ({ children }) => <h6>{children}</h6>,
  table: ({ children }) => <div className="answer-table" tabIndex={0} role="region" aria-label="回答表格"><table>{children}</table></div>,
  pre: ({ children }) => <pre tabIndex={0} aria-label="回答代码块">{children}</pre>,
}
const elements = ['p', 'br', 'hr', 'em', 'strong', 'del', 'blockquote', 'ul', 'ol', 'li', 'pre', 'code',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a', 'img', 'input', 'sup', 'section']
const plugins = [remarkGfm]

export const AnswerContent = memo(function AnswerContent({ text }: { text: string }) {
  const [raw, setRaw] = useState(false)
  const id = useId()
  return <div className="answer-content">
    <div className="answer-reading-tools"><button type="button" className="secondary" aria-controls={id} aria-pressed={raw}
      onClick={() => setRaw(value => !value)}>{raw ? '返回排版' : '查看原文'}</button></div>
    <div id={id} dir="auto" className={raw ? 'answer-original' : 'answer-markdown'}>
      {raw ? <pre aria-label="回答原文">{text}</pre> : <Markdown components={components} allowedElements={elements}
        remarkPlugins={plugins} remarkRehypeOptions={{ clobberPrefix: `${id}-`, footnoteLabel: '注释', footnoteBackLabel: '返回注释' }}>{text}</Markdown>}
    </div>
  </div>
})
