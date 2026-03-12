import {
  GetConfig, SaveConfig, TestConnection,
  BrowseFolder, GetActivity, GetWatchStatus,
  StartWatching, StopWatching
} from '../wailsjs/go/main/App'

import { EventsOn, WindowMinimise } from '../wailsjs/runtime/runtime'

// ── State ─────────────────────────────────────────────────────────────────────

let isWatching = false

// ── Boot ──────────────────────────────────────────────────────────────────────

window.addEventListener('load', async () => {
  await loadSettings()
  await refreshStatus()
  await loadActivity()
  subscribeEvents()
})

// expose globals for inline onclick handlers
window.showTab = showTab
window.toggleWatch = toggleWatch
window.browseFolder = browseFolder
window.saveSettings = saveSettings
window.testConnection = testConnection
window.minimizeWindow = () => WindowMinimise()

// ── Tabs ──────────────────────────────────────────────────────────────────────

function showTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'))
  document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'))
  document.getElementById('tab-' + name).classList.add('active')
  document.getElementById('view-' + name).classList.remove('hidden')
}

// ── Watch toggle ──────────────────────────────────────────────────────────────

async function toggleWatch() {
  if (isWatching) {
    await StopWatching()
    setStatus(false)
  } else {
    const err = await StartWatching()
    if (err) {
      showConnResult('Could not start: ' + err, false)
      showTab('settings')
      return
    }
    setStatus(true)
  }
}

function setStatus(watching) {
  isWatching = watching
  const dot   = document.getElementById('status-dot')
  const label = document.getElementById('status-text')
  const btn   = document.getElementById('btn-toggle')

  dot.className = 'dot ' + (watching ? 'dot-watching' : 'dot-idle')
  label.textContent = watching ? 'WATCHING' : 'IDLE'
  btn.textContent   = watching ? 'STOP' : 'START'
  btn.className     = watching ? 'pill-btn stop' : 'pill-btn'
}

async function refreshStatus() {
  const watching = await GetWatchStatus()
  setStatus(watching)
}

// ── Settings ──────────────────────────────────────────────────────────────────

async function loadSettings() {
  const cfg = await GetConfig()
  document.getElementById('cfg-url').value       = cfg.serverUrl    || ''
  document.getElementById('cfg-user').value      = cfg.username     || ''
  document.getElementById('cfg-pass').value      = cfg.password     || ''
  document.getElementById('cfg-dir').value       = cfg.watchDir     || ''
  document.getElementById('cfg-workers').value   = cfg.concurrency  || 4
  document.getElementById('cfg-chunk').value     = cfg.chunkSizeMB  || 1
  document.getElementById('cfg-autostart').checked = cfg.autoStart !== false

  const watchLabel = document.getElementById('watch-dir')
  watchLabel.textContent = cfg.watchDir ? '→ ' + cfg.watchDir : ''
}

async function saveSettings() {
  const cfg = {
    serverUrl:   document.getElementById('cfg-url').value.trim(),
    username:    document.getElementById('cfg-user').value.trim(),
    password:    document.getElementById('cfg-pass').value,
    watchDir:    document.getElementById('cfg-dir').value.trim(),
    concurrency: parseInt(document.getElementById('cfg-workers').value) || 4,
    chunkSizeMB: parseInt(document.getElementById('cfg-chunk').value)   || 1,
    autoStart:   document.getElementById('cfg-autostart').checked,
  }

  const err = await SaveConfig(cfg)
  if (err) {
    showConnResult('Save failed: ' + err, false)
    return
  }

  document.getElementById('watch-dir').textContent = cfg.watchDir ? '→ ' + cfg.watchDir : ''
  showConnResult('Settings saved', true)
}

async function browseFolder() {
  const dir = await BrowseFolder()
  if (dir) {
    document.getElementById('cfg-dir').value = dir
  }
}

async function testConnection() {
  const btn = document.querySelector('.action-btn.secondary')
  btn.textContent = 'TESTING...'
  btn.disabled = true
  try {
    const result = await TestConnection()
    showConnResult(result.message, result.ok)
  } finally {
    btn.textContent = 'TEST CONNECTION'
    btn.disabled = false
  }
}

function showConnResult(msg, ok) {
  const el = document.getElementById('conn-result')
  el.textContent = msg
  el.className = 'conn-result ' + (ok ? 'ok' : 'err')
  el.classList.remove('hidden')
  setTimeout(() => el.classList.add('hidden'), 4000)
}

// ── Activity ──────────────────────────────────────────────────────────────────

async function loadActivity() {
  const entries = await GetActivity()
  const list = document.getElementById('activity-list')
  if (!entries || entries.length === 0) return
  list.innerHTML = ''
  entries.forEach(e => prependActivity(e))
}

function prependActivity(entry) {
  const list = document.getElementById('activity-list')
  const empty = list.querySelector('.empty-state')
  if (empty) empty.remove()

  const icons = { complete: '✓', uploading: '↑', error: '✗', skipped: '⊘' }
  const el = document.createElement('div')
  el.className = 'activity-entry'
  el.innerHTML = `
    <span class="ae-time">${entry.time}</span>
    <span class="ae-icon">${icons[entry.status] || '·'}</span>
    <div class="ae-body">
      <div class="ae-filename">${entry.filename}</div>
      ${entry.detail ? `<div class="ae-detail">${entry.detail}</div>` : ''}
    </div>
    <span class="ae-status s-${entry.status}">${entry.status.toUpperCase()}</span>
  `
  list.insertBefore(el, list.firstChild)
  // cap at 100 visible entries
  while (list.children.length > 100) list.removeChild(list.lastChild)
}

// ── Events from Go ────────────────────────────────────────────────────────────

function subscribeEvents() {
  EventsOn('watcher:status', watching => setStatus(watching))

  EventsOn('activity:new', entry => prependActivity(entry))

  EventsOn('upload:start', filename => {
    const dot = document.getElementById('status-dot')
    dot.className = 'dot dot-uploading'
    document.getElementById('status-text').textContent = 'UPLOADING'
    showProgress(filename)
  })

  EventsOn('upload:progress', prog => {
    document.getElementById('prog-filename').textContent  = prog.filename
    document.getElementById('prog-bar').style.width       = prog.percent.toFixed(1) + '%'
    document.getElementById('prog-pct').textContent       = prog.percent.toFixed(0) + '%'
    document.getElementById('prog-speed').textContent     = prog.speedMbps.toFixed(1) + ' Mbps'
    document.getElementById('prog-chunks').textContent    = `${prog.chunksDone}/${prog.chunksTotal}`
  })

  EventsOn('upload:complete', () => {
    hideProgress()
    if (isWatching) {
      document.getElementById('status-dot').className = 'dot dot-watching'
      document.getElementById('status-text').textContent = 'WATCHING'
    }
  })

  EventsOn('upload:error', data => {
    hideProgress()
    if (isWatching) {
      document.getElementById('status-dot').className = 'dot dot-watching'
      document.getElementById('status-text').textContent = 'WATCHING'
    }
  })
}

function showProgress(filename) {
  const sec = document.getElementById('progress-section')
  sec.classList.remove('hidden')
  document.getElementById('prog-filename').textContent = filename
  document.getElementById('prog-bar').style.width = '0%'
  document.getElementById('prog-pct').textContent = '0%'
  document.getElementById('prog-speed').textContent = '— Mbps'
  document.getElementById('prog-chunks').textContent = '0/0'
}

function hideProgress() {
  document.getElementById('progress-section').classList.add('hidden')
}
