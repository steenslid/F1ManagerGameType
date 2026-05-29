// Shared formatting helpers for the new UI. Money values from the backend are
// plain dollar integers (Long), e.g. 200000000 === $200M.

export function fmtMoney(val) {
  const n = Number(val) || 0
  const sign = n < 0 ? '-' : ''
  const abs = Math.abs(n)
  if (abs >= 1_000_000_000) return `${sign}$${(abs / 1_000_000_000).toFixed(2)}B`
  if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(1)}M`
  if (abs >= 1_000) return `${sign}$${Math.round(abs / 1_000)}K`
  return `${sign}$${abs}`
}

export function fmtMoneyFull(val) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0,
  }).format(Number(val) || 0)
}

// "SPRINT_QUALIFYING" -> "Sprint Qualifying"
export function phaseLabel(phase) {
  if (!phase) return ''
  return phase
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

export function crestText(name) {
  if (!name) return '??'
  return name.replace(/[^A-Za-z ]/g, '').trim().substring(0, 2).toUpperCase() || '??'
}
