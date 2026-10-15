// Lists t('…') / tr('…') keys used in src/ that have no English translation yet (dev helper; the Vitest
// i18n test is the gate). Usage: node scripts/i18n-missing.mjs
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const files = (d) => readdirSync(d).flatMap((f) => {
  const p = join(d, f)
  return statSync(p).isDirectory() ? files(p) : /\.tsx?$/.test(f) && !/\.test\./.test(f) && !f.startsWith('i18n') ? [p] : []
})
const call = /(?<![\w.$])tr?\(\s*(['"])((?:\\.|(?!\1)[^\\\n])*)\1/g
const keys = new Set()
for (const f of files('src')) for (const m of readFileSync(f, 'utf8').matchAll(call)) keys.add(m[2].replace(/\\'/g, "'"))
const en = readFileSync('src/i18n.en.ts', 'utf8')
const missing = [...keys].filter((k) => !en.includes(`'${k.replace(/'/g, "\\'")}':`) && !en.includes(`"${k}":`)).sort()
console.log(missing.join('\n'))
