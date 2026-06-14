import { marked } from 'marked'
import hljs from 'highlight.js'

marked.setOptions({ breaks: false, gfm: true })

// Register highlight.js for code blocks
marked.use({
  renderer: {
    code(token) {
      const lang = token.lang
      const code = token.text
      const language = lang && hljs.getLanguage(lang) ? lang : 'plaintext'
      try {
        return `<pre><code class="hljs language-${language}">${hljs.highlight(code, { language }).value}</code></pre>\n`
      } catch {
        return `<pre><code class="hljs">${hljs.highlightAuto(code).value}</code></pre>\n`
      }
    },
  },
})

// ---- text pre-processing (no Markdown parsing) ----

function mergeShortParagraphs(text) {
  if (!text) return text

  // Protect code fences from line-break processing
  const fences = []
  let work = text.replace(/(```[\s\S]*?```)/g, (m) => {
    fences.push(m)
    return `\x00FENCE${fences.length - 1}\x00`
  })

  // Triple+ newlines are strong paragraph boundaries
  const PARABREAK = '\x00PARA\x00'
  work = work.replace(/\n{3,}/g, PARABREAK)

  const structural = /^(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|[|]|```|[*_-]{3,}\s*$)/
  const continuation = /^[，,。.、；;：:！!？?…\-—）\)\]】」』〉》］"'（【「『《\(]/
  const endsSentence = /[。！？.!?～」』》）]$/

  // Unwrap single-newline breaks within a paragraph
  function unwrapBlock(block) {
    const lines = block.split(/\n/)
    if (lines.length <= 1) return block

    const merged = [lines[0]]
    for (let i = 1; i < lines.length; i++) {
      const line = lines[i]
      const trimmed = line.trim()
      if (!trimmed) { merged.push(line); continue }
      if (structural.test(trimmed)) { merged.push(line); continue }

      const prev = merged[merged.length - 1].trim()
      if (structural.test(prev)) {
        merged.push('')
        merged.push(line)
        continue
      }
      if (!prev) { merged.push(line); continue }

      const prevEndsSentence = endsSentence.test(prev) || structural.test(prev)
      const curStartsContinuation = continuation.test(trimmed)

      if (curStartsContinuation || !prevEndsSentence) {
        const needSpace = /[a-zA-Z0-9]$/.test(prev) && /^[a-zA-Z0-9]/.test(trimmed)
        merged[merged.length - 1] += (needSpace ? ' ' : '') + line
      } else {
        merged.push(line)
      }
    }
    return merged.join('\n')
  }

  // Process a block: split by \n\n+ into candidate paragraphs,
  // merge mid-sentence breaks, keep genuine paragraph boundaries
  function processBlock(block) {
    const paras = block.split(/\n\n+/)
    if (paras.length <= 1) return unwrapBlock(block)

    const merged = [paras[0]]
    for (let i = 1; i < paras.length; i++) {
      const prevEnd = merged[merged.length - 1].trim()
      const curStart = paras[i].trim()

      if (endsSentence.test(prevEnd)) {
        // Prev ends with 。！？etc → genuine paragraph break
        merged.push(paras[i])
      } else if (continuation.test(curStart)) {
        // Cur starts with ，。）etc → mid-sentence break, merge
        merged[merged.length - 1] += '\n' + paras[i]
      } else if (prevEnd.length < 10 || (curStart.length < 15 && !structural.test(curStart))) {
        // Short fragments → likely broken mid-word/phrase, merge
        merged[merged.length - 1] += '\n' + paras[i]
      } else {
        merged.push(paras[i])
      }
    }

    return merged.map(unwrapBlock).join('\n\n')
  }

  let result = work.split(PARABREAK).map(processBlock).join('\n\n')

  // Restore code fences
  result = result.replace(/\x00FENCE(\d+)\x00/g, (_m, i) => fences[+i] || '')

  return result
}

function fixLlmFormatting(text) {
  return String(text)
    .replace(/^(#{1,6})([^\s#])/gmu, '$1 $2')
    .replace(/^(\s*)([-*])([^\s\-*])/gmu, '$1$2 $3')
    .replace(/([^\n#])(#{1,6})([^\s#])/gu, '$1\n$2 $3')
    .replace(/^(.+?[：:]\s*)([^\n]+(?:\s*\/\s*[^\n]+){2,})$/gmu, (_m, prefix, items) => {
      // Skip if this looks like a URL (avoids breaking http://... into bullet list)
      if (/https?:\/\/|ftp:\/\/|^\/\//.test(items)) return _m
      const parts = items.split(/\s*\/\s*/).map(s => s.trim()).filter(Boolean)
      if (parts.length < 2) return _m
      return prefix + '\n' + parts.map(p => '- ' + p).join('\n')
    })
    // Convert newline-separated short items after a colon to markdown list
    // "分类：\n项目A\n项目B" → "分类：\n- 项目A\n- 项目B"
    // Only convert lines that are short (< 25 chars) and contain no punctuation
    .replace(/^(.+[：:]\s*)\n+((?:[^\n]{1,50}\n+)+)/gmu, (_m, prefix, itemsBlock) => {
      const allLines = itemsBlock.split(/\n+/).map(l => l.trim()).filter(Boolean)
      if (allLines.length < 2) return _m
      // Lines that look like list items: short, no punctuation, no markdown syntax
      const isItem = (l) => l.length < 25 && !/[，。！？：、；（）「」《》『』【】]/.test(l) && !/^(#|-|\*|\d+\.|>|\|)/.test(l)
      const items = allLines.filter(isItem)
      if (items.length < 2) return _m
      // Replace only the list-item lines, leave other lines as-is
      let result = prefix + '\n'
      for (const l of allLines) {
        result += (isItem(l) ? '- ' + l : l) + '\n'
      }
      return result.replace(/\n+$/, '\n')
    })
}

function stripCodeRegions(text) {
  return text
    .replace(/```[\s\S]*?```/g, '')
    .replace(/`[^`\n]+`/g, '')
}

function fixIncompleteSyntax(text) {
  const stripped = stripCodeRegions(text)
  let fixed = text

  const boldCount = (stripped.match(/\*\*/g) || []).length
  if (boldCount % 2 === 1) fixed += '**'

  const fenceCount = (stripped.match(/```/g) || []).length
  if (fenceCount % 2 === 1) fixed += '\n```'

  const backtickCount = (stripped.match(/(?<!`)`(?!`)/g) || []).length
  if (backtickCount % 2 === 1) fixed += '`'

  const openBracket = (stripped.match(/\[([^\]]*)\]\(/g) || []).length
  const closeParen = (stripped.match(/\)/g) || []).length
  if (openBracket > closeParen) fixed += ')'

  return fixed
}

// ---- public API ----

export function renderMarkdown(text) {
  if (!text) return ''
  try {
    return marked.parse(mergeShortParagraphs(fixLlmFormatting(String(text))))
  } catch {
    return String(text)
  }
}

export function renderMarkdownStreaming(text) {
  if (!text) return ''
  try {
    const fixed = mergeShortParagraphs(fixLlmFormatting(String(text)))
    return marked.parse(fixIncompleteSyntax(fixed))
  } catch {
    return String(text)
  }
}
