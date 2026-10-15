import { useEffect, useRef, useState } from 'react'
import { Extension, type Editor as TiptapEditor, type Range } from '@tiptap/core'
import Image from '@tiptap/extension-image'
import Placeholder from '@tiptap/extension-placeholder'
import TaskItem from '@tiptap/extension-task-item'
import TaskList from '@tiptap/extension-task-list'
import { EditorContent, useEditor } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import Suggestion, { type SuggestionProps } from '@tiptap/suggestion'
import { Markdown } from 'tiptap-markdown'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { useMe } from '../auth'
import type { UserRef } from '../spaces'
import { Button } from '../ui'
import { t } from '../i18n'

type MentionState = { items: UserRef[]; command: (u: UserRef) => void; rect: DOMRect | null }

/** "@" autocomplete that inserts plain "@username" text, so the markdown stays plain (spec §4.4). */
const MentionSuggest = Extension.create<{
  items: (q: string) => UserRef[]
  onState: (s: MentionState | null) => void
  onKeyDown: (e: KeyboardEvent) => boolean
}>({
  name: 'mentionSuggest',
  addOptions() {
    return { items: () => [], onState: () => {}, onKeyDown: () => false }
  },
  addProseMirrorPlugins() {
    const toState = (p: SuggestionProps<UserRef, UserRef>): MentionState => ({
      items: p.items, command: (u) => p.command(u), rect: p.clientRect?.() ?? null,
    })
    return [
      Suggestion<UserRef, UserRef>({
        editor: this.editor,
        char: '@',
        items: ({ query }) => this.options.items(query),
        command: ({ editor, range, props }: { editor: TiptapEditor; range: Range; props: UserRef }) =>
          editor.chain().focus().insertContentAt(range, `@${props.username} `).run(),
        render: () => ({
          onStart: (p) => this.options.onState(toState(p)),
          onUpdate: (p) => this.options.onState(toState(p)),
          onExit: () => this.options.onState(null),
          onKeyDown: ({ event }) => this.options.onKeyDown(event),
        }),
      }),
    ]
  },
})

type Props = {
  initial: string
  onSubmit: (markdown: string) => void
  onCancel?: () => void
  submitLabel?: string
  placeholder?: string
  mentionables?: UserRef[]
  /** Uploads a pasted/dropped image as a ticket attachment and returns its URL. */
  uploadImage?: (file: File) => Promise<string>
  autoFocus?: boolean
}

/** Rich editor with live markdown conversion; markdown is the stored format. Raw mode per user preference. */
export function Editor(props: Props) {
  const me = useMe().data!
  const qc = useQueryClient()
  const raw = me.preferences.editor === 'markdown'
  const toggle = async () => {
    const updated = await api('/me', { method: 'PATCH', body: { preferences: { editor: raw ? 'rich' : 'markdown' } } })
    qc.setQueryData(['me'], updated)
  }
  return raw ? <RawEditor {...props} onToggle={toggle} /> : <RichEditor {...props} onToggle={toggle} />
}

function Toolbar({ onSubmit, onCancel, submitLabel, onToggle, raw, length }: {
  onSubmit: () => void; onCancel?: () => void; submitLabel?: string; onToggle: () => void; raw: boolean; length: number
}) {
  return (
    <div className="flex items-center gap-2">
      <Button type="button" onClick={onSubmit}>{submitLabel ?? t('Enregistrer')}</Button>
      {onCancel && <Button type="button" variant="secondary" onClick={onCancel}>{t('Annuler')}</Button>}
      {length > 90_000 && <span className="text-xs text-amber-600">{length.toLocaleString()} / 100 000</span>}
      <button type="button" onClick={onToggle} className="ml-auto text-xs text-slate-500 hover:text-slate-800 dark:hover:text-slate-200"
        title={t('Basculer entre l’éditeur riche et le markdown brut')}>
        {raw ? t('Éditeur riche') : t('Markdown brut')}
      </button>
    </div>
  )
}

function RawEditor({ initial, onSubmit, onCancel, submitLabel, placeholder, autoFocus, onToggle }: Props & { onToggle: () => void }) {
  const [text, setText] = useState(initial)
  return (
    <div className="space-y-2">
      <textarea autoFocus={autoFocus} value={text} onChange={(e) => setText(e.target.value)} rows={6} maxLength={100_000}
        placeholder={placeholder}
        onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) onSubmit(text) }}
        className="w-full rounded-md border border-slate-300 bg-white p-2 font-mono text-sm dark:border-slate-700 dark:bg-slate-900" />
      <Toolbar onSubmit={() => onSubmit(text)} onCancel={onCancel} submitLabel={submitLabel} onToggle={onToggle} raw length={text.length} />
    </div>
  )
}

function RichEditor({ initial, onSubmit, onCancel, submitLabel, placeholder, mentionables = [], uploadImage, autoFocus, onToggle }: Props & { onToggle: () => void }) {
  const [mention, setMention] = useState<MentionState | null>(null)
  const [selected, setSelected] = useState(0)
  const [length, setLength] = useState(initial.length)
  // The suggestion plugin reads these through refs: it is created once with the editor.
  const live = useRef({ mention, selected, mentionables })
  live.current = { mention, selected, mentionables }
  const editorRef = useRef<TiptapEditor | null>(null)
  const submitRef = useRef(onSubmit)
  submitRef.current = onSubmit

  const editor = useEditor({
    autofocus: autoFocus ? 'end' : false,
    extensions: [
      StarterKit.configure({ heading: { levels: [2, 3, 4, 5] } }),
      TaskList,
      TaskItem.configure({ nested: true }),
      Image,
      Placeholder.configure({ placeholder: placeholder ?? '' }),
      Markdown.configure({ html: false, linkify: true, breaks: true, transformPastedText: true }),
      MentionSuggest.configure({
        items: (q) => live.current.mentionables
          .filter((u) => (u.username + ' ' + u.displayName).toLowerCase().includes(q.toLowerCase())).slice(0, 6),
        onState: (s) => { setMention(s); setSelected(0) },
        onKeyDown: (e) => {
          const m = live.current.mention
          if (!m || m.items.length === 0) return false
          if (e.key === 'ArrowDown') { setSelected((i) => (i + 1) % m.items.length); return true }
          if (e.key === 'ArrowUp') { setSelected((i) => (i - 1 + m.items.length) % m.items.length); return true }
          if (e.key === 'Enter' || e.key === 'Tab') { m.command(m.items[live.current.selected]); return true }
          return false
        },
      }),
    ],
    content: initial,
    editorProps: {
      attributes: { class: 'markdown min-h-24 px-3 py-2 outline-none' },
      handleKeyDown: (_view, e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { submit(); return true }
        return false
      },
      handlePaste: (_view, e) => insertImages(e.clipboardData?.files),
      handleDrop: (_view, e) => insertImages((e as DragEvent).dataTransfer?.files),
    },
    onUpdate: ({ editor }) => setLength(markdownOf(editor).length),
  })

  editorRef.current = editor

  // editorProps callbacks are created once: they must read the editor and callbacks through refs.
  function insertImages(files: FileList | null | undefined) {
    const images = [...(files ?? [])].filter((f) => f.type.startsWith('image/'))
    if (!images.length || !uploadImage) return false
    images.forEach(async (f) => {
      const src = await uploadImage(f)
      editorRef.current?.chain().focus().setImage({ src, alt: f.name }).run()
    })
    return true
  }

  function submit() {
    if (editorRef.current) submitRef.current(markdownOf(editorRef.current))
  }

  useEffect(() => () => editor?.destroy(), [editor])

  return (
    <div className="space-y-2">
      <div className="relative rounded-md border border-slate-300 bg-white focus-within:border-indigo-500 focus-within:ring-2 focus-within:ring-indigo-500/30 dark:border-slate-700 dark:bg-slate-900">
        <EditorContent editor={editor} />
        {mention && mention.items.length > 0 && mention.rect && (
          <ul role="listbox" className="fixed z-50 w-56 rounded-md border border-slate-200 bg-white py-1 text-sm shadow-lg dark:border-slate-700 dark:bg-slate-900"
            style={{ top: mention.rect.bottom + 4, left: mention.rect.left }}>
            {mention.items.map((u, i) => (
              <li key={u.id} role="option" aria-selected={i === selected}
                onMouseDown={(e) => { e.preventDefault(); mention.command(u) }}
                className={`cursor-pointer px-3 py-1 ${i === selected ? 'bg-indigo-50 dark:bg-indigo-950' : ''}`}>
                {u.displayName} <span className="text-slate-500">@{u.username}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <Toolbar onSubmit={submit} onCancel={onCancel} submitLabel={submitLabel} onToggle={onToggle} raw={false} length={length} />
    </div>
  )
}

function markdownOf(editor: TiptapEditor): string {
  return (editor.storage as unknown as { markdown: { getMarkdown: () => string } }).markdown.getMarkdown()
}
