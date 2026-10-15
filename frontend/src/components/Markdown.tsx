import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { useMemo } from 'react'

marked.setOptions({ gfm: true, breaks: true })

/** Renders user markdown; output is sanitized (spec §10: allow-list HTML). */
export function Markdown({ text, className = '' }: { text: string; className?: string }) {
  const html = useMemo(() => DOMPurify.sanitize(marked.parse(text, { async: false }) as string), [text])
  return <div className={`markdown ${className}`} dangerouslySetInnerHTML={{ __html: html }} />
}
