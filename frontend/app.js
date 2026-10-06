const $ = (selector) => document.querySelector(selector);
const API = '/api';
const demoOrders = [
  {tokenNo: 103, studentName: 'Neha Kapoor', phone: '98644 21129', printType: 'Colour', pages: 4, copies: 1, notes: 'Front side only', status: 'READY', createdAt: '10:42 AM'},
  {tokenNo: 102, studentName: 'Kabir Singh', phone: '97001 44551', printType: 'B/W', pages: 24, copies: 1, notes: 'Staple top-left', status: 'PRINTING', createdAt: '10:37 AM'},
  {tokenNo: 101, studentName: 'Aarav Sharma', phone: '98765 43210', printType: 'B/W', pages: 18, copies: 2, notes: 'Double side please', status: 'PENDING', createdAt: '10:31 AM'}
];
let activeFilter = 'ALL';
let offlineMode = false;

function serviceName(value) { return ({ 'B/W': 'Black & white', Colour: 'Colour print', Xerox: 'Xerox copy' })[value] || value; }
function amount(type, pages, copies) { return (type === 'Colour' ? 8 : 1) * pages * copies; }
function showError(message = '') { $('#form-error').textContent = message; }
function updateSummary() {
  const type = document.querySelector('[name="printType"]:checked').value;
  const pages = Math.max(1, Number($('#pages').value) || 1);
  const copies = Math.max(1, Number($('#copies').value) || 1);
  $('#summary-type').textContent = serviceName(type);
  $('#summary-pages').textContent = pages;
  $('#summary-copies').textContent = copies;
  $('#summary-cost').textContent = `₹${amount(type, pages, copies)}`;
}
function bindFormControls() {
  document.querySelectorAll('.service').forEach((label) => label.addEventListener('click', () => {
    document.querySelectorAll('.service').forEach((item) => item.classList.remove('selected'));
    label.classList.add('selected'); updateSummary();
  }));
  document.querySelectorAll('[data-step]').forEach((button) => button.addEventListener('click', () => {
    const field = $(`#${button.dataset.step}`); const max = Number(field.max); const next = Math.max(1, Math.min(max, Number(field.value || 1) + Number(button.dataset.change)));
    field.value = next; updateSummary();
  }));
  $('#pages').addEventListener('input', updateSummary); $('#copies').addEventListener('input', updateSummary);
}
async function request(path, options = {}) {
  const response = await fetch(`${API}${path}`, { headers: { 'Content-Type': 'application/json' }, ...options });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'Something went wrong. Please try again.');
  return data;
}
function saveDemoOrder(order) { demoOrders.unshift(order); }
async function submitOrder(event) {
  event.preventDefault(); showError();
  const payload = Object.fromEntries(new FormData(event.currentTarget));
  payload.pages = Number(payload.pages); payload.copies = Number(payload.copies);
  if (payload.studentName.trim().length < 2 || payload.phone.trim().length < 7) { showError('Please add your name and a valid phone number.'); return; }
  const button = event.currentTarget.querySelector('[type="submit"]'); button.disabled = true; button.textContent = 'Saving your order…';
  try {
    let result;
    if (offlineMode) { const tokenNo = Math.max(...demoOrders.map(o => o.tokenNo), 100) + 1; result = { tokenNo, estimatedMinutes: 8 }; saveDemoOrder({ ...payload, tokenNo, status: 'PENDING', createdAt: 'Just now' }); }
    else result = await request('/orders', { method: 'POST', body: JSON.stringify(payload) });
    $('#modal-token').textContent = `#${result.tokenNo}`; $('#modal-wait').textContent = `~${result.estimatedMinutes} minutes`;
    $('#token-modal').classList.add('open'); $('#token-modal').setAttribute('aria-hidden', 'false'); event.currentTarget.reset(); $('#pages').value = 1; $('#copies').value = 1; updateSummary(); loadQueue(); loadDashboard();
  } catch (error) { showError(error.message); }
  finally { button.disabled = false; button.innerHTML = 'Get my token <span>→</span>'; }
}
function renderQueue(items) {
  const queue = $('#queue-list'); const visible = items.filter((order) => ['READY', 'PRINTING'].includes(order.status));
  $('#now-calling').textContent = visible.find(o => o.status === 'READY') ? `#${visible.find(o => o.status === 'READY').tokenNo}` : '#—';
  if (!visible.length) { queue.innerHTML = '<p class="empty-state">No collection calls right now. Your update will appear here.</p>'; return; }
  queue.innerHTML = visible.map((order) => `<div class="queue-row"><span class="queue-token">#${order.tokenNo}</span><span class="queue-person"><b>${escapeHtml(order.studentName)}</b><small>${serviceName(order.printType)} · ${order.pages} pages</small></span><span class="status ${order.status}">${order.status === 'READY' ? 'Collect now' : 'Printing'}</span></div>`).join('');
}
async function loadQueue() {
  try { const data = offlineMode ? { queue: demoOrders } : await request('/queue'); renderQueue(data.queue); }
  catch { offlineMode = true; renderQueue(demoOrders); }
}
function renderStats(data) { $('#stat-total').textContent = data.total; $('#stat-pending').textContent = data.pending; $('#stat-printing').textContent = data.printing; $('#stat-ready').textContent = data.ready; }
function renderOrders(orders) {
  const grid = $('#orders-grid');
  if (!orders.length) { grid.innerHTML = '<p class="empty-state">No matching orders. A calm queue is a good queue.</p>'; return; }
  grid.innerHTML = orders.map((order) => `<article class="order-card"><div class="order-card-head"><div><span class="order-token">#${order.tokenNo}</span><span class="status ${order.status}">${order.status}</span></div><span class="order-time">${escapeHtml(order.createdAt)}</span></div><h3 class="order-name">${escapeHtml(order.studentName)}</h3><p class="order-phone">${escapeHtml(order.phone)}</p><div class="order-details"><span class="chip">${serviceName(order.printType)}</span><span class="chip">${order.pages} pages × ${order.copies}</span><span class="chip">₹${amount(order.printType, order.pages, order.copies)}</span></div>${order.notes ? `<p class="card-notes">“${escapeHtml(order.notes)}”</p>` : '<p class="card-notes">No special instructions.</p>'}<div class="card-footer"><span class="status ${order.status}">${order.status}</span><select class="status-select" data-token="${order.tokenNo}" aria-label="Change status for token ${order.tokenNo}">${['PENDING','PRINTING','READY','COLLECTED'].map(s => `<option value="${s}" ${s === order.status ? 'selected' : ''}>${s === 'PENDING' ? 'Start printing' : s}</option>`).join('')}</select></div></article>`).join('');
  document.querySelectorAll('.status-select').forEach((select) => select.addEventListener('change', () => changeStatus(Number(select.dataset.token), select.value)));
}
async function loadDashboard() {
  try {
    if (offlineMode) { const list = activeFilter === 'ALL' ? demoOrders : demoOrders.filter(o => o.status === activeFilter); renderOrders(list); renderStats({ total: demoOrders.length, pending: demoOrders.filter(o => o.status === 'PENDING').length, printing: demoOrders.filter(o => o.status === 'PRINTING').length, ready: demoOrders.filter(o => o.status === 'READY').length }); return; }
    const [orders, stats] = await Promise.all([request(`/orders?status=${activeFilter}`), request('/stats')]); renderOrders(orders.orders); renderStats(stats);
  } catch { offlineMode = true; loadDashboard(); }
}
async function changeStatus(tokenNo, status) {
  try { if (offlineMode) { const order = demoOrders.find(o => o.tokenNo === tokenNo); if (order) order.status = status; } else await request(`/orders/${tokenNo}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }); loadDashboard(); loadQueue(); }
  catch (error) { alert(error.message); loadDashboard(); }
}
function escapeHtml(value = '') { return String(value).replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[char]); }
function switchView(view) { const shop = view === 'shop'; $('#student-view').hidden = shop; $('#shop-view').hidden = !shop; document.querySelectorAll('.nav-link').forEach(b => b.classList.toggle('active', b.dataset.view === view)); if (shop) loadDashboard(); window.scrollTo({ top: 0, behavior: 'smooth' }); }
function init() {
  bindFormControls(); updateSummary(); $('#orderForm').addEventListener('submit', submitOrder);
  document.querySelectorAll('.nav-link').forEach(button => button.addEventListener('click', () => switchView(button.dataset.view)));
  $('#refresh-orders').addEventListener('click', loadDashboard);
  document.querySelectorAll('.filter').forEach(button => button.addEventListener('click', () => { activeFilter = button.dataset.filter; document.querySelectorAll('.filter').forEach(b => b.classList.toggle('active', b === button)); loadDashboard(); }));
  const closeModal = () => { $('#token-modal').classList.remove('open'); $('#token-modal').setAttribute('aria-hidden', 'true'); }; $('.modal-close').addEventListener('click', closeModal); $('#modal-close-button').addEventListener('click', closeModal); $('#token-modal').addEventListener('click', (e) => { if (e.target.id === 'token-modal') closeModal(); });
  loadQueue(); setInterval(loadQueue, 30000);
}
document.addEventListener('DOMContentLoaded', init);
