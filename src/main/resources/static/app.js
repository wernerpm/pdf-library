'use strict';

// ---- State ----
const state = {
  page: 0,
  pageSize: 50,
  query: '',
  sort: 'fileName',
  order: 'asc',
  total: 0,
  totalPages: 0,
  syncPollingTimer: null,
  searchDebounceTimer: null,
};

// ---- DOM refs ----
const $ = id => document.getElementById(id);
const grid        = $('grid');
const loading     = $('loading');
const emptyState  = $('empty');
const pagination  = $('pagination');
const btnPrev     = $('btn-prev');
const btnNext     = $('btn-next');
const pageLabel   = $('page-label');
const searchInput = $('search-input');
const btnClear    = $('btn-clear-search');
const sortField   = $('sort-field');
const btnSortOrder= $('btn-sort-order');
const pageSizeEl  = $('page-size');
const syncStatus  = $('sync-status');
const syncBadge   = $('sync-badge');
const syncLabel   = $('sync-label');
const progressFill= $('progress-bar-fill');
const modalOverlay= $('modal-overlay');

// ---- API helpers ----
async function api(path) {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

async function apiPost(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

// ---- Fetch & render PDFs ----
async function loadPDFs() {
  loading.style.display = 'block';
  grid.style.display = 'none';
  emptyState.style.display = 'none';
  pagination.style.display = 'none';

  try {
    const params = new URLSearchParams({
      page: state.page,
      size: state.pageSize,
      sort: state.sort,
      order: state.order,
    });
    if (state.query) params.set('q', state.query);

    const resp = await api(`/api/pdfs?${params}`);
    const { data, total, totalPages, page } = resp.data;

    state.total = total;
    state.totalPages = totalPages;
    state.page = page;

    loading.style.display = 'none';

    if (!data || data.length === 0) {
      emptyState.style.display = 'block';
      return;
    }

    renderGrid(data);
    renderPagination();
  } catch (e) {
    loading.textContent = `Error: ${e.message}`;
  }
}

function renderGrid(pdfs) {
  grid.innerHTML = '';
  for (const pdf of pdfs) {
    const card = document.createElement('div');
    card.className = 'pdf-card';
    card.dataset.id = pdf.id;

    const displayTitle = pdf.title || pdf.fileName;
    const thumbSrc = `/api/thumbnails/${pdf.id}`;

    card.innerHTML = `
      <div class="card-thumb-placeholder js-thumb-wrap">
        <span>📄</span>
      </div>
      <div class="card-info">
        <div class="card-title" title="${escHtml(displayTitle)}">${escHtml(displayTitle)}</div>
        ${pdf.author ? `<div class="card-author" title="${escHtml(pdf.author)}">${escHtml(pdf.author)}</div>` : ''}
        <div class="card-pages">${pdf.pageCount} p · ${formatSize(pdf.fileSize)}</div>
      </div>`;

    // Lazy-load thumbnail
    const thumbWrap = card.querySelector('.js-thumb-wrap');
    const img = new Image();
    img.className = 'card-thumb';
    img.alt = displayTitle;
    img.onload = () => thumbWrap.replaceWith(img);
    img.onerror = () => {}; // keep placeholder
    img.src = thumbSrc;

    card.addEventListener('click', () => openModal(pdf.id));
    grid.appendChild(card);
  }

  grid.style.display = 'grid';
}

function renderPagination() {
  if (state.totalPages <= 1) return;
  pagination.style.display = 'flex';
  btnPrev.disabled = state.page <= 0;
  btnNext.disabled = state.page >= state.totalPages - 1;
  pageLabel.textContent = `Page ${state.page + 1} of ${state.totalPages}  (${state.total} PDFs)`;
}

// ---- Detail modal ----
async function openModal(id) {
  modalOverlay.style.display = 'flex';
  document.body.style.overflow = 'hidden';

  $('modal-title').textContent = '…';
  $('modal-author').textContent = '';
  $('meta-table').innerHTML = '';
  $('text-preview-section').style.display = 'none';
  $('modal-thumbnail').src = `/api/thumbnails/${id}`;
  $('modal-thumbnail').onerror = () => { $('modal-thumbnail').style.display = 'none'; };
  $('modal-thumbnail').onload  = () => { $('modal-thumbnail').style.display = ''; };

  try {
    const resp = await api(`/api/pdfs/${id}`);
    const pdf = resp.data;

    $('modal-title').textContent = pdf.title || pdf.fileName;
    $('modal-author').textContent = pdf.author || '';

    const rows = [
      ['Filename',   pdf.fileName],
      ['Path',       pdf.path],
      ['Pages',      pdf.pageCount],
      ['File size',  formatSize(pdf.fileSize)],
      ['PDF version',pdf.pdfVersion || '—'],
      ['Subject',    pdf.subject || '—'],
      ['Keywords',   pdf.keywords?.join(', ') || '—'],
      ['Creator',    pdf.creator || '—'],
      ['Producer',   pdf.producer || '—'],
      ['Created',    pdf.createdDate ? new Date(pdf.createdDate).toLocaleString() : '—'],
      ['Modified',   pdf.modifiedDate ? new Date(pdf.modifiedDate).toLocaleString() : '—'],
      ['Indexed',    pdf.indexedAt ? new Date(pdf.indexedAt).toLocaleString() : '—'],
      ['Encrypted',  pdf.isEncrypted ? 'Yes' : 'No'],
      ['Signed',     pdf.isSignedPdf ? 'Yes' : 'No'],
      ['Has text',   pdf.hasTextContent ? 'Yes' : 'No'],
    ];

    const table = $('meta-table');
    table.innerHTML = rows.map(([k, v]) =>
      `<tr><td>${escHtml(String(k))}</td><td>${escHtml(String(v))}</td></tr>`
    ).join('');

    if (pdf.hasTextContent) {
      loadTextPreview(id);
    }
  } catch (e) {
    $('modal-title').textContent = 'Error loading details';
  }
}

async function loadTextPreview(id) {
  try {
    const res = await fetch(`/api/pdfs/${id}/text`);
    if (!res.ok) return;
    const text = await res.text();
    const preview = text.slice(0, 2000) + (text.length > 2000 ? '\n…' : '');
    $('text-preview').textContent = preview;
    $('text-preview-section').style.display = '';
  } catch (_) {}
}

function closeModal() {
  modalOverlay.style.display = 'none';
  document.body.style.overflow = '';
}

// ---- Stats ----
async function loadStats() {
  try {
    const resp = await api('/api/stats');
    const s = resp.data;
    $('stat-total').textContent = `${s.totalPdfs.toLocaleString()} PDFs`;
    $('stat-pages').textContent = `${s.totalPages.toLocaleString()} pages`;
    $('stat-size').textContent  = formatSize(s.totalSizeBytes);
    $('stat-encrypted').textContent = `${s.encryptedCount} encrypted`;
  } catch (_) {}
}

// ---- Sync status polling ----
async function pollStatus() {
  try {
    const resp = await api('/status');
    const sys = resp.data;
    const ep = sys.extractionProgress;
    if (!ep) return;

    const phase = ep.phase;
    const isActive = phase === 'DISCOVERING' || phase === 'EXTRACTING';

    syncStatus.style.display = 'block';
    syncBadge.textContent = phase;
    syncBadge.className = `sync-badge ${phase}`;

    if (isActive) {
      const pct = Math.round(ep.percentComplete);
      syncLabel.textContent = `${ep.processedFiles} / ${ep.totalFiles} files (${pct}%)`;
      progressFill.style.width = `${pct}%`;
    } else if (phase === 'COMPLETED') {
      syncLabel.textContent = `${ep.successfulFiles} extracted, ${ep.failedFiles} failed`;
      progressFill.style.width = '100%';
    } else if (phase === 'FAILED') {
      syncLabel.textContent = 'Sync failed';
    } else {
      syncStatus.style.display = 'none';
    }

    if (isActive) {
      schedulePolling(2000);
    } else {
      stopPolling();
      if (phase === 'COMPLETED') {
        loadStats();
        loadPDFs();
      }
    }
  } catch (_) {}
}

function schedulePolling(ms = 3000) {
  if (state.syncPollingTimer) return;
  state.syncPollingTimer = setTimeout(() => {
    state.syncPollingTimer = null;
    pollStatus();
  }, ms);
}

function stopPolling() {
  if (state.syncPollingTimer) {
    clearTimeout(state.syncPollingTimer);
    state.syncPollingTimer = null;
  }
}

async function triggerSync(type) {
  try {
    await apiPost('/api/sync', { type });
    schedulePolling(500);
  } catch (e) {
    alert(`Sync failed: ${e.message}`);
  }
}

// ---- Utilities ----
function formatSize(bytes) {
  if (bytes == null) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function escHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ---- Event wiring ----
searchInput.addEventListener('input', () => {
  clearTimeout(state.searchDebounceTimer);
  btnClear.style.display = searchInput.value ? 'block' : 'none';
  state.searchDebounceTimer = setTimeout(() => {
    state.query = searchInput.value.trim();
    state.page = 0;
    loadPDFs();
  }, 300);
});

btnClear.addEventListener('click', () => {
  searchInput.value = '';
  btnClear.style.display = 'none';
  state.query = '';
  state.page = 0;
  loadPDFs();
});

sortField.addEventListener('change', () => {
  state.sort = sortField.value;
  state.page = 0;
  loadPDFs();
});

btnSortOrder.addEventListener('click', () => {
  state.order = state.order === 'asc' ? 'desc' : 'asc';
  btnSortOrder.textContent = state.order === 'asc' ? '↑' : '↓';
  state.page = 0;
  loadPDFs();
});

pageSizeEl.addEventListener('change', () => {
  state.pageSize = Number(pageSizeEl.value);
  state.page = 0;
  loadPDFs();
});

btnPrev.addEventListener('click', () => { state.page--; loadPDFs(); });
btnNext.addEventListener('click', () => { state.page++; loadPDFs(); });

$('btn-incremental-sync').addEventListener('click', () => triggerSync('incremental'));
$('btn-full-sync').addEventListener('click', () => triggerSync('full'));

$('modal-close').addEventListener('click', closeModal);
modalOverlay.addEventListener('click', e => { if (e.target === modalOverlay) closeModal(); });
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

// ---- Boot ----
loadStats();
loadPDFs();
pollStatus();
