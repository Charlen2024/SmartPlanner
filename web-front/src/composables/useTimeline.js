export function timeToMinutes(dt) {
  if (!dt) return null
  const s = String(dt)
  const t = s.includes('T') ? s.split('T')[1] : s
  const hhmm = t.slice(0, 5)
  const [h, m] = hhmm.split(':')
  const hh = Number(h)
  const mm = Number(m)
  if (!Number.isFinite(hh) || !Number.isFinite(mm)) return null
  return hh * 60 + mm
}

export function dateDowValue(dateStr) {
  if (!dateStr) return null
  const d = new Date(`${dateStr}T00:00:00`)
  const js = d.getDay()
  return ((js + 6) % 7) + 1
}

export function computeWeekNumber(dateStr, fwmStr) {
  if (!dateStr || !fwmStr) return null
  const d = new Date(`${dateStr}T00:00:00`)
  const fwm = new Date(`${fwmStr}T00:00:00`)
  const diffDays = Math.floor((d - fwm) / (1000 * 60 * 60 * 24))
  return Math.floor(diffDays / 7) + 1
}

export function matchesWeekFn(c, weekNumber) {
  if (!c) return true
  const ws = c.weekStart, we = c.weekEnd
  if (ws == null || we == null) return true
  if (weekNumber == null) return true
  if (weekNumber < ws || weekNumber > we) return false
  const wt = c.weekType
  if (!wt) return true
  if (wt === 'even' || wt === '双') return weekNumber % 2 === 0
  if (wt === 'odd' || wt === '单') return weekNumber % 2 === 1
  return true
}

export function clampedDurationMinutes(start, end, timelineStartHour, timelineEndHour) {
  const s = timeToMinutes(start)
  const e = timeToMinutes(end)
  if (s == null || e == null) return null
  const startMin = timelineStartHour * 60
  const endMin = timelineEndHour * 60
  const topMin = Math.max(s, startMin)
  const bottomMin = Math.min(e, endMin)
  const d = bottomMin - topMin
  return d > 0 ? d : null
}

export function blockStyle(start, end, pxPerMinute, timelineStartHour, timelineEndHour, minBlockHeightPx, blockGapPx) {
  const s = timeToMinutes(start)
  const e = timeToMinutes(end)
  if (s == null || e == null) return null
  const startMin = timelineStartHour * 60
  const endMin = timelineEndHour * 60
  const topMin = Math.max(s, startMin) - startMin
  const bottomMin = Math.min(e, endMin) - startMin
  const hMin = bottomMin - topMin
  if (hMin <= 0) return null
  const ppm = pxPerMinute
  const topPx = Math.round(topMin * ppm)
  const heightPx = Math.round(hMin * ppm)
  const finalHeight = Math.max(minBlockHeightPx, heightPx - blockGapPx)
  return { top: `${topPx}px`, height: `${finalHeight}px` }
}

export function layoutOverlappingBlocks(blocks) {
  if (!blocks || !blocks.length) return blocks
  const sorted = [...blocks].sort((a, b) => (a._start || '').localeCompare(b._start || ''))
  const groups = []
  let cur = [sorted[0]]
  let curEnd = sorted[0]._end || ''
  for (let i = 1; i < sorted.length; i++) {
    const s = sorted[i]._start || ''
    if (s < curEnd) {
      cur.push(sorted[i])
      if ((sorted[i]._end || '') > curEnd) curEnd = sorted[i]._end || ''
    } else {
      groups.push(cur)
      cur = [sorted[i]]
      curEnd = sorted[i]._end || ''
    }
  }
  groups.push(cur)
  const margin = 1.5
  for (const g of groups) {
    if (g.length <= 1) continue
    const n = g.length
    const pct = (100 - margin * (n + 1)) / n
    g.forEach((b, col) => {
      b.style = { ...b.style, left: `${margin + col * (pct + margin)}%`, width: `${pct}%` }
    })
  }
  return blocks
}
