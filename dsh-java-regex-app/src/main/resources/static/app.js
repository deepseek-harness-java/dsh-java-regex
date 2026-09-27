const state = {
  conversations: [],
  activeConversationId: null,
  activeStream: null
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.regexlab.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.regexlab.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- regex panels ----------

async function loadParse() {
  const pattern = $('#parsePattern').value.trim();
  if (!pattern) return toast('输入正则表达式', true);
  const flags = $('#parseFlags').value.trim();
  const data = await request(`/api/regex/parse?pattern=${encodeURIComponent(pattern)}&flags=${encodeURIComponent(flags)}`);
  const dangerBadge = data.danger?.dangerous
    ? `<span class="badge warn">⚠ ${escapeHtml(data.danger.level)} 风险</span>` : '<span class="badge ok">✓ 无典型 ReDoS 结构</span>';
  $('#parseResult').innerHTML =
    `<div class="small muted">有效：${data.valid ? '✓' : '✗ ' + escapeHtml(data.compileError || '')} · 分组 ${data.groupCount} 个 · token ${data.tokenCount} 个 ${dangerBadge}</div>` +
    (data.tokens || []).map(t => `
      <div class="branch-row">
        <code>${escapeHtml(t.value)}</code> <span class="badge info">${escapeHtml(t.type)}</span>
        <div class="muted small">${escapeHtml(t.meaning || '')}</div>
      </div>`).join('');
}

async function loadTest() {
  const pattern = $('#testPattern').value.trim();
  if (!pattern) return toast('输入正则表达式', true);
  const lines = $('#testCases').value.split('\n').map(s => s.trim()).filter(Boolean);
  const cases = lines.map(line => {
    const m = line.match(/^(.*)=(true|false)$/);
    return m ? { input: m[1], expect: m[2] === 'true' } : { input: line };
  });
  if (!cases.length) return toast('至少输入一个测试用例', true);
  const data = await request('/api/regex/test', {
    method: 'POST',
    body: JSON.stringify({ pattern, flags: $('#parseFlags').value.trim(), cases })
  });
  $('#testResult').innerHTML =
    `<div class="small muted">共 ${data.caseCount} 例 · 匹配 ${data.matchCount} 例 · ${data.allPass ? '<b class="ok-text">全部通过 ✓</b>' : '<b class="warn-text">存在不符 ✗</b>'}</div>` +
    (data.results || []).map(r => `
      <div class="branch-row">
        <span class="badge ${r.error ? 'warn' : r.matched ? 'ok' : 'info'}">${r.error ? '超时/错误' : r.matched ? '匹配' : '不匹配'}</span>
        <code class="small">${escapeHtml(r.input.length > 40 ? r.input.slice(0, 40) + '…' : r.input)}</code>
        <span class="small muted">${r.elapsedMs}ms${r.pass === false ? ' · 与期望不符' : ''}${r.namedGroups ? ' · ' + escapeHtml(JSON.stringify(r.namedGroups)) : ''}</span>
      </div>`).join('');
}

async function loadLibrary() {
  const data = await request('/api/regex/library');
  const entries = Object.entries(data.categories || {});
  $('#libraryCard').innerHTML =
    `<div class="small muted">共 ${data.count} 个常用模式（中国场景优先）</div>` +
    entries.map(([name, pattern]) => `
      <div class="branch-row">
        <b>${escapeHtml(name)}</b>
        <code class="small" style="word-break:break-all">${escapeHtml(pattern)}</code>
      </div>`).join('');
}

async function loadSamples() {
  const lines = $('#samplesInput').value.split('\n').map(s => s.trim()).filter(Boolean);
  if (!lines.length) return toast('至少输入一个样本', true);
  const data = await request('/api/regex/from-samples', {
    method: 'POST',
    body: JSON.stringify({ samples: lines })
  });
  $('#samplesResult').innerHTML =
    `<div class="branch-row">
      <b>建议正则</b>
      <code style="word-break:break-all">${escapeHtml(data.suggested)}</code>
      <div class="muted small">样本 ${data.sampleCount} 条 · 泛化 ${data.generalizedSegments} 段 · ${escapeHtml(data.note || '')}</div>
    </div>`;
}

async function loadDanger(pattern) {
  const data = await request('/api/regex/danger', {
    method: 'POST',
    body: JSON.stringify({ pattern })
  });
  $('#parseResult').innerHTML =
    `<div class="small muted">${data.dangerous ? `<b class="warn-text">⚠ 危险（${escapeHtml(data.level)}）</b>：${escapeHtml(data.reason || '')}` : '<b class="ok-text">✓ 未发现典型 ReDoS 结构</b>'}</div>` +
    (data.findings || []).map(f => `<div class="branch-row"><span class="badge warn">发现</span><div class="small">${escapeHtml(f)}</div></div>`).join('') +
    `<div class="branch-row"><span class="badge info">建议</span><div class="small">${escapeHtml(data.advice || '')}</div></div>`;
  document.querySelector('[data-resource-tab="parse"]').click();
  document.querySelector('[data-tab="parse"] #parsePattern').value = pattern;
}

async function refreshAll() {
  await loadLibrary().catch(e => toast(e.message, true));
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };
$('#openDanger').onclick = () => $('#dangerModal').classList.remove('hidden');
$('#cancelDangerModal').onclick = () => $('#dangerModal').classList.add('hidden');
$('#dangerForm').onsubmit = async event => {
  event.preventDefault();
  const pattern = new FormData(event.target).get('pattern');
  try {
    await loadDanger(pattern);
    $('#dangerModal').classList.add('hidden');
    event.target.reset();
    toast('扫描完成');
  } catch (error) {
    toast(error.message, true);
  }
};
$('#parseBtn').onclick = () => loadParse().catch(e => toast(e.message, true));
$('#parsePattern').addEventListener('keydown', e => { if (e.key === 'Enter') $('#parseBtn').click(); });
$('#testBtn').onclick = () => loadTest().catch(e => toast(e.message, true));
$('#samplesBtn').onclick = () => loadSamples().catch(e => toast(e.message, true));
$('#refreshResource').onclick = async () => {
  await refreshAll();
  toast('已刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.regexlab.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.regexlab.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
  try {
    await refreshAll();
  } catch (error) {
    toast(error.message, true);
  }
})();
