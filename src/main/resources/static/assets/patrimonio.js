(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const html = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const icon = name => `<svg class="icon" aria-hidden="true"><use href="/assets/icons.svg#${name}"/></svg>`;
  const number = value => new Intl.NumberFormat('pt-BR').format(value);
  const date = value => new Intl.DateTimeFormat('pt-BR', {dateStyle:'short', timeStyle:'short', timeZone:'America/Sao_Paulo'}).format(new Date(value));
  const normalize = value => String(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const kinds = {SPACE:'Espaço', FURNITURE:'Móvel', CONTAINER:'Recipiente', ITEM:'Item'};
  const icons = {SPACE:'room', FURNITURE:'furniture', CONTAINER:'box', ITEM:'cube'};
  const statuses = {AVAILABLE:'Disponível', IN_USE:'Em uso', MAINTENANCE:'Manutenção'};
  const statusClasses = {AVAILABLE:'available', IN_USE:'in-use', MAINTENANCE:'maintenance'};
  const badge = node => `<span class="status ${statusClasses[node.status]}">${statuses[node.status]}</span>`;
  const state = {nodes:[], events:[], current:null, view:'map', query:'', status:'', expanded:new Set(), ready:false, canEdit:false, canAdmin:false};
  let byId = new Map();
  let moveId = null;
  let deleteId = null;
  let toastTimer;

  function children(id) { return state.nodes.filter(node => node.parentId === id); }
  function descendants(id) {
    const result = [];
    const queue = [...children(id)];
    const visited = new Set();
    while (queue.length) {
      const node = queue.shift();
      if (visited.has(node.id)) continue;
      visited.add(node.id); result.push(node); queue.push(...children(node.id));
    }
    return result;
  }
  function ancestors(id) {
    const result = [];
    const visited = new Set();
    while (id && byId.has(id) && !visited.has(id)) {
      visited.add(id); const node = byId.get(id); result.unshift(node); id = node.parentId;
    }
    return result;
  }
  function path(id) { return ancestors(id).map(node => node.name).join(' / ') || 'Raiz dos espaços'; }
  function assets(nodes) { return nodes.filter(node => node.kind !== 'SPACE'); }
  function quantity(nodes) { return assets(nodes).reduce((sum, node) => sum + node.quantity, 0); }
  function filtered(nodes) {
    const query = normalize(state.query);
    return nodes.filter(node => (!state.status || node.status === state.status) && (!query ||
      normalize(`${node.name} ${node.code} ${node.responsible} ${node.description} ${path(node.parentId)}`).includes(query)));
  }
  function isFiltering() { return Boolean(state.query || state.status); }
  function notify(message) {
    clearTimeout(toastTimer); $('#toast').textContent = message; $('#toast').hidden = false;
    toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 4500);
  }
  function feedback(message) {
    $('#feedback').innerHTML = `${html(message)} <button type="button" class="text-button" data-action="retry">Tentar novamente</button>`;
    $('#feedback').hidden = false;
  }
  async function request(url, method = 'GET', body) {
    const headers = {'Accept':'application/json'};
    if (body) headers['Content-Type'] = 'application/json';
    if (method !== 'GET') headers[$('meta[name="csrf-header"]').content] = $('meta[name="csrf-token"]').content;
    let response;
    try { response = await fetch(url, {method, headers, credentials:'same-origin', body:body ? JSON.stringify(body) : undefined}); }
    catch (_) { throw new Error('Sem conexão com o servidor. Verifique se a aplicação está em execução.'); }
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      if (response.status===401) { location.assign('/login'); throw new Error('Entre novamente para continuar.'); }
      throw new Error(error.message || (response.status === 403 ? 'Seu papel não permite esta operação.' : 'Não foi possível concluir a operação. Tente novamente.'));
    }
    return response.status === 204 ? null : response.json();
  }
  async function refresh(initial = false) {
    $('#sync-status').textContent = 'Sincronizando…';
    const [data, session] = await Promise.all([request('/api/inventory'), request('/api/session')]);
    state.canEdit=session.canEdit; state.canAdmin=session.canAdmin;
    if (state.view==='history' && !state.canEdit) state.view='map';
    state.nodes = data.nodes; state.events = data.events;
    byId = new Map(state.nodes.map(node => [node.id, node]));
    if (initial) {
      const params = new URLSearchParams(location.hash.slice(1));
      state.view = ['map','inventory','history'].includes(params.get('visao')) ? params.get('visao') : 'map';
      state.current = params.has('local') ? (byId.has(params.get('local')) ? params.get('local') : null) : (byId.has('sala') ? 'sala' : children(null)[0]?.id || null);
    }
    if (state.current && !byId.has(state.current)) state.current = null;
    ancestors(state.current).forEach(node => state.expanded.add(node.id));
    if (state.view==='history' && !state.canEdit) state.view='map';
    state.ready = true;
    $('#feedback').hidden = true;
    $('#sync-status').textContent = 'Atualizado agora';
    $('#metrics').setAttribute('aria-busy', 'false');
    render();
  }
  function saveLocation() {
    const params = new URLSearchParams({visao:state.view, local:state.current || ''});
    history.replaceState(null, '', `#${params}`);
  }
  function navigate(id) {
    state.current = byId.has(id) ? id : null;
    state.view = 'map'; state.query = ''; state.status = '';
    $('#search').value = ''; $('#status-filter').value = '';
    ancestors(state.current).forEach(node => state.expanded.add(node.id));
    render();
  }
  function renderMetrics() {
    const allAssets = assets(state.nodes);
    const count = quantity(allAssets);
    const available = quantity(allAssets.filter(node => node.status === 'AVAILABLE'));
    const spaces = state.nodes.filter(node => node.kind === 'SPACE').length;
    const maintenance = quantity(allAssets.filter(node => node.status === 'MAINTENANCE'));
    const metric = (label, symbol, value, caption, extra = '') => `<article class="metric"><div class="metric-head"><span>${label}</span>${icon(symbol)}</div><p class="metric-value ${extra}">${value}</p><p class="metric-caption">${caption}</p></article>`;
    $('#metrics').innerHTML = metric('Itens de patrimônio', 'cube', number(count), `${number(allAssets.length)} registros · <span class="positive">${number(available)} unidades disponíveis</span>`) +
      metric('Espaços cadastrados', 'room', number(spaces), 'Salas, laboratórios e outros ambientes') +
      metric('Locais de armazenamento', 'box', number(state.nodes.filter(node => ['FURNITURE','CONTAINER'].includes(node.kind)).length), 'Móveis, gavetas, caixas e divisórias') +
      metric('Em manutenção', 'tool', number(maintenance), `<span class="warning">${maintenance ? 'Itens que precisam de atenção' : 'Nenhuma manutenção pendente'}</span>`);
  }
  function renderTree() {
    // Render only expanded branches, so deep storage chains do not grow the DOM unnecessarily.
    function branch(parent) {
      return `<ul class="tree-branch">${children(parent).map(node => {
        const nested = children(node.id).length;
        const open = state.expanded.has(node.id);
        return `<li><div class="tree-row ${state.current === node.id ? 'current' : ''}">${nested ? `<button type="button" class="tree-toggle" data-toggle="${html(node.id)}" aria-expanded="${open}" aria-label="${open ? 'Recolher' : 'Expandir'} ${html(node.name)}">${icon('chevron')}</button>` : '<span class="tree-placeholder"></span>'}<button type="button" class="tree-link" data-node="${html(node.id)}" ${state.current === node.id ? 'aria-current="location"' : ''} title="${html(node.name)}">${icon(icons[node.kind])}<span>${html(node.name)}</span></button>${nested ? `<span class="tree-counter">${nested}</span>` : ''}</div>${nested && open ? branch(node.id) : ''}</li>`;
      }).join('')}</ul>`;
    }
    $('#tree').innerHTML = `<div class="tree-row ${state.current === null ? 'current' : ''}"><span class="tree-placeholder"></span><button type="button" class="tree-link" data-node="">${icon('grid')}<span>Todos os espaços</span></button></div>${branch(null)}`;
  }
  function renderMap() {
    const node = byId.get(state.current);
    const chain = ancestors(state.current);
    $('#breadcrumbs').innerHTML = `<button type="button" data-node="">Espaços</button>${chain.map((entry, index) => `${icon('chevron')}${index === chain.length - 1 ? `<span aria-current="location">${html(entry.name)}</span>` : `<button type="button" data-node="${html(entry.id)}">${html(entry.name)}</button>`}`).join('')}`;
    $('#map-title').textContent = isFiltering() ? 'Resultados da busca' : node?.name || 'Seus espaços';
    $('#map-kicker').textContent = isFiltering() ? 'TODO O PATRIMÔNIO' : node ? kinds[node.kind] : 'VISÃO DOS AMBIENTES';
    $('#map-description').textContent = isFiltering() ? 'Nome, código, responsável e localização em todos os espaços.' : node?.description || 'Comece por um espaço e explore tudo o que ele guarda.';
    $('.map-symbol').innerHTML = icon(isFiltering() ? 'search' : node ? icons[node.kind] : 'room');
    const entries = isFiltering() ? filtered(state.nodes) : children(state.current);
    $('#map-count').textContent = `${entries.length} ${isFiltering() ? 'resultados encontrados' : 'blocos neste nível'}`;
    $('#scope-count').textContent = `${state.nodes.filter(n => n.kind === 'SPACE').length} espaços`;
    if (!entries.length) {
      $('#tiles').innerHTML = `<div class="empty-tile">${icon(isFiltering() ? 'search' : 'box')}<h3>${isFiltering() ? 'Nenhum patrimônio encontrado' : 'Local vazio'}</h3><p>${isFiltering() ? 'Tente outro termo ou limpe os filtros.' : 'Nenhum conteúdo cadastrado neste nível.'}</p>${isFiltering() || state.canEdit ? `<button type="button" class="button quiet" data-action="${isFiltering() ? 'clear' : 'add'}">${isFiltering() ? 'Limpar filtros' : 'Adicionar aqui'}</button>` : ''}</div>`;
    } else {
      $('#tiles').innerHTML = entries.map(entry => {
        const nested = children(entry.id);
        const inside = descendants(entry.id);
        return `<article class="tile"><button type="button" class="tile-open" data-node="${html(entry.id)}" aria-label="Explorar ${html(entry.name)}"><span class="tile-top"><span class="tile-type">${icon(icons[entry.kind])}${kinds[entry.kind]}</span>${icon('arrow')}</span><h3>${html(entry.name)}</h3><p class="tile-meta">${html(entry.code)} · ${nested.length ? `${nested.length} ${nested.length === 1 ? 'conteúdo direto' : 'conteúdos diretos'}` : `${number(entry.quantity)} ${entry.quantity === 1 ? 'unidade' : 'unidades'}`}</p></button>${nested.length ? `<div class="tile-preview" aria-label="Conteúdo de ${html(entry.name)}">${nested.slice(0, 4).map(child => `<button type="button" class="mini-tile" data-node="${html(child.id)}" title="${html(child.name)}">${icon(icons[child.kind])}<span>${html(child.name)}</span></button>`).join('')}</div>` : ''}<div class="tile-bottom">${nested.length ? `<span>${number(quantity(inside))} itens no conteúdo${nested.length > 4 ? ` · +${nested.length - 4} blocos` : ''}</span>` : badge(entry)}${nested.length ? badge(entry) : ''}</div></article>`;
      }).join('');
    }
    renderDetail(node);
  }
  function renderDetail(node) {
    if (!node) {
      $('#detail').innerHTML = `<div class="detail-top">Organização dos espaços${icon('info')}</div><div class="detail-symbol">${icon('grid')}</div><h2>Selecione um local</h2><p class="detail-note">Selecione um bloco para explorar seus conteúdos, consultar informações ou mudar um patrimônio de lugar.</p>${state.canEdit ? `<div class="detail-actions"><button type="button" class="button quiet" data-action="space">${icon('plus')}Novo espaço</button></div>` : ''}`;
      return;
    }
    const inside = descendants(node.id);
    const all = assets([node, ...inside]);
    const units = quantity(all);
    const available = quantity(all.filter(entry => entry.status === 'AVAILABLE'));
    const percentage = units ? Math.round(available / units * 100) : 0;
    $('#detail').innerHTML = `<div class="detail-top">Detalhes do ${node.kind === 'SPACE' ? 'espaço' : 'patrimônio'}${icon('info')}</div><h2>${html(node.name)}</h2><p class="detail-code">${html(node.code)}</p>${badge(node)}${ancestors(node.id).some(n=>n.visibility==='CONFIDENTIAL') ? '<span class="access-label">Sigiloso</span>' : ''}<dl><div><dt>Tipo</dt><dd>${kinds[node.kind]}</dd></div><div><dt>Responsável</dt><dd>${html(node.responsible || 'Não informado')}</dd></div><div><dt>Localização</dt><dd>${html(path(node.parentId))}</dd></div><div><dt>Conteúdo</dt><dd>${children(node.id).length} blocos · ${quantity(inside)} itens</dd></div>${node.kind !== 'SPACE' ? `<div><dt>Quantidade</dt><dd>${number(node.quantity)} un.</dd></div>` : ''}</dl><p class="detail-note"><strong>Descrição e posição</strong>${html(node.description || 'Adicione uma descrição para facilitar a localização física.')}</p>${units ? `<div class="availability"><div><span>Unidades disponíveis</span><strong>${percentage}%</strong></div><meter min="0" max="${units}" value="${available}" aria-label="${available} de ${units} unidades disponíveis"></meter></div>` : ''}${state.canEdit ? `<div class="detail-actions"><button type="button" class="button quiet" data-edit="${html(node.id)}">${icon('edit')}Editar</button><button type="button" class="button quiet" data-move="${html(node.id)}">${icon('move')}Mover</button><button type="button" class="button primary" data-action="add">${icon('plus')}Adicionar aqui</button></div><button type="button" class="delete-link" data-delete="${html(node.id)}">${icon('trash')}Excluir ${node.kind === 'SPACE' ? 'espaço' : 'patrimônio'}</button>` : ''}<p class="detail-updated">Atualizado em ${html(date(node.updatedAt))}</p>`;
  }
  function renderInventory() {
    const entries = filtered(assets(state.nodes));
    $('#inventory-count').textContent = `${entries.length} registros · ${quantity(entries)} unidades`;
    $('#inventory-rows').innerHTML = entries.length ? entries.map(node => `<tr><td><button type="button" class="table-item" data-node="${html(node.id)}">${icon(icons[node.kind])}<span>${html(node.name)}<small>${html(node.code)} · ${kinds[node.kind]}</small></span></button></td><td>${html(path(node.parentId))}<small>${html(node.responsible || 'Responsável não informado')}</small></td><td>${badge(node)}</td><td>${number(node.quantity)}</td><td>${state.canEdit ? `<div class="table-actions"><button type="button" class="icon-button" data-edit="${html(node.id)}" aria-label="Editar ${html(node.name)}">${icon('edit')}</button><button type="button" class="icon-button" data-move="${html(node.id)}" aria-label="Mover ${html(node.name)}">${icon('move')}</button></div>` : ''}</td></tr>`).join('') : '<tr><td colspan="5" class="empty-row">Nenhum patrimônio encontrado. Ajuste os filtros ou cadastre um item.</td></tr>';
  }
  function renderHistory() {
    const entries = state.events.filter(event => normalize(`${event.name} ${event.action} ${event.origin} ${event.destination}`).includes(normalize(state.query)));
    $('#history-rows').innerHTML = entries.length ? entries.map(event => `<tr><td>${html(date(event.occurredAt))}</td><td>${html(event.name)}</td><td>${html(event.action)}</td><td>${html(event.actor)}</td><td>${html(event.origin || '—')}</td><td>${html(event.destination || '—')}</td></tr>`).join('') : '<tr><td colspan="6" class="empty-row">Nenhuma movimentação encontrada.</td></tr>';
  }
  function render() {
    if (!state.ready) return;
    renderMetrics(); renderTree(); renderMap(); renderInventory(); renderHistory();
    $('#map-view').hidden = state.view !== 'map';
    $('#inventory-view').hidden = state.view !== 'inventory';
    $('#history-view').hidden = state.view !== 'history';
    $('#view-title').textContent = {map:'Mapa de espaços', inventory:'Inventário', history:'Movimentações'}[state.view];
    $('#status-filter').disabled = state.view === 'history';
    $('#export').disabled = state.view === 'history';
    document.querySelectorAll('[data-view]').forEach(button => {
      if (button.dataset.view === state.view) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    $('#new-item').hidden=!state.canEdit; $('#new-space').hidden=!state.canEdit;
    document.querySelector('[data-view=history]').hidden=!state.canEdit;
    saveLocation();
  }
  function parentOptions(id, kind) {
    const excluded = new Set(id ? [id, ...descendants(id).map(node => node.id)] : []);
    return `${kind === 'SPACE' ? '<option value="">Raiz dos espaços</option>' : ''}${state.nodes.filter(node => !excluded.has(node.id)).map(node => `<option value="${html(node.id)}">${html(path(node.id))}</option>`).join('')}`;
  }
  function updateKind() {
    const form = $('#node-form');
    const fields = form.elements;
    const selected = fields.parentId.value;
    fields.parentId.innerHTML = parentOptions(fields.id.value, fields.kind.value);
    if ([...fields.parentId.options].some(option => option.value === selected)) fields.parentId.value = selected;
    const isSpace = fields.kind.value === 'SPACE';
    fields.quantity.disabled = isSpace;
    fields.parentId.required = !isSpace;
    if (isSpace) fields.quantity.value = '1';
  }
  function openForm(id = null, kind = 'ITEM') {
    if (!state.ready || !state.canEdit) return;
    const form = $('#node-form'); form.reset();
    const fields = form.elements;
    const node = byId.get(id);
    fields.id.value = id || '';
    fields.kind.value = node?.kind || kind;
    fields.parentId.innerHTML = parentOptions(id, fields.kind.value);
    const preferredParent = node ? node.parentId : kind === 'SPACE' ? '' : state.current || children(null)[0]?.id;
    if ([...fields.parentId.options].some(option => option.value === (preferredParent || ''))) fields.parentId.value = preferredParent || '';
    fields.name.value = node?.name || '';
    const prefix = {SPACE:'ESP', FURNITURE:'MOV', CONTAINER:'REC', ITEM:'PAT'}[fields.kind.value];
    fields.code.value = node?.code || `${prefix}-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
    fields.status.value = node?.status || 'AVAILABLE';
    fields.visibility.value = node?.visibility || 'PUBLIC';
    fields.quantity.value = node?.quantity || 1;
    fields.responsible.value = node?.responsible || byId.get(state.current)?.responsible || '';
    fields.description.value = node?.description || '';
    $('#dialog-title').textContent = node ? 'Editar patrimônio' : kind === 'SPACE' ? 'Novo espaço' : 'Novo patrimônio';
    $('#form-error').hidden = true; updateKind();
    $('#node-dialog').showModal(); fields.name.focus();
  }
  function openMove(id) {
    const node = byId.get(id); if (!node) return;
    moveId = id;
    $('#move-description').textContent = `${node.name} está em ${path(node.parentId)}.`;
    $('#move-options').innerHTML = parentOptions(id, node.kind);
    if ([...$('#move-options').options].some(option => option.value === (node.parentId || ''))) $('#move-options').value = node.parentId || '';
    $('#move-error').hidden = true;
    $('#move-dialog').showModal();
  }
  function nodePayload(node, parentId) {
    return {parentId:parentId || null, name:node.name, code:node.code, kind:node.kind, status:node.status,
      visibility:node.visibility, quantity:node.quantity, unitValue:node.unitValue, responsible:node.responsible, description:node.description};
  }
  async function afterMutation(message) {
    notify(message);
    try { await refresh(); }
    catch (error) { $('#sync-status').textContent = 'Atualização pendente'; feedback(`Alteração salva. ${error.message}`); }
  }
  function formError(selector, error) { $(selector).textContent = error.message; $(selector).hidden = false; }
  $('#node-form').addEventListener('submit', async event => {
    event.preventDefault();
    const fields = event.currentTarget.elements;
    const id = fields.id.value;
    const payload = {name:fields.name.value.trim(), code:fields.code.value.trim(), parentId:fields.parentId.value || null,
      kind:fields.kind.value, status:fields.status.value, visibility:fields.visibility.value, quantity:Number(fields.quantity.value), unitValue:fields.kind.value === 'SPACE' ? 0 : byId.get(id)?.unitValue || 0,
      responsible:fields.responsible.value.trim(), description:fields.description.value.trim()};
    $('#save-node').disabled = true;
    try {
      const saved = await request(id ? `/api/nodes/${encodeURIComponent(id)}` : '/api/nodes', id ? 'PUT' : 'POST', payload);
      $('#node-dialog').close();
      if (!state.current || payload.kind === 'SPACE') state.current = saved.id;
      await afterMutation(id ? 'Patrimônio atualizado.' : 'Patrimônio cadastrado.');
    } catch (error) { formError('#form-error', error); }
    finally { $('#save-node').disabled = false; }
  });
  $('#move-form').addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.currentTarget.querySelector('[type="submit"]'); button.disabled = true;
    try {
      const node = byId.get(moveId);
      const parent = $('#move-options').value || null;
      if (node.parentId === parent) throw new Error('Escolha um local diferente do atual.');
      await request(`/api/nodes/${encodeURIComponent(moveId)}`, 'PUT', nodePayload(node, parent));
      $('#move-dialog').close();
      await afterMutation('Patrimônio e seus conteúdos movidos.');
    } catch (error) { formError('#move-error', error); }
    finally { button.disabled = false; }
  });
  $('#delete-form').addEventListener('submit', async event => {
    event.preventDefault();
    const button = event.currentTarget.querySelector('[type="submit"]'); button.disabled = true;
    try {
      const parent = byId.get(deleteId)?.parentId || null;
      await request(`/api/nodes/${encodeURIComponent(deleteId)}`, 'DELETE');
      if (state.current === deleteId) state.current = parent;
      $('#delete-dialog').close();
      await afterMutation('Patrimônio excluído. O registro permanece no histórico.');
    } catch (error) { formError('#delete-error', error); }
    finally { button.disabled = false; }
  });
  document.addEventListener('click', event => {
    const button = event.target.closest('button'); if (!button) return;
    if (button.hasAttribute('data-close')) button.closest('dialog').close();
    else if (button.hasAttribute('data-node')) navigate(button.dataset.node);
    else if (button.hasAttribute('data-toggle')) {
      const id = button.dataset.toggle;
      state.expanded.has(id) ? state.expanded.delete(id) : state.expanded.add(id); renderTree();
    } else if (button.dataset.edit) openForm(button.dataset.edit);
    else if (button.dataset.move) openMove(button.dataset.move);
    else if (button.dataset.delete) {
      deleteId = button.dataset.delete;
      $('#delete-description').textContent = `Excluir “${byId.get(deleteId).name}”? A exclusão será registrada no histórico. Locais com conteúdo precisam ser esvaziados antes.`;
      $('#delete-error').hidden = true; $('#delete-dialog').showModal();
    } else if (button.dataset.view) { state.view = button.dataset.view; render(); }
    else if (button.dataset.action === 'add') openForm(null, state.current ? 'ITEM' : 'SPACE');
    else if (button.dataset.action === 'space') openForm(null, 'SPACE');
    else if (button.dataset.action === 'clear') { state.query = ''; state.status = ''; $('#search').value = ''; $('#status-filter').value = ''; render(); }
    else if (button.dataset.action === 'retry') refresh(!state.ready).catch(error => feedback(error.message));
  });
  $('#new-item').addEventListener('click', () => openForm(null, children(null).length ? 'ITEM' : 'SPACE'));
  $('#new-space').addEventListener('click', () => openForm(null, 'SPACE'));
  $('#show-all').addEventListener('click', () => { state.view = 'inventory'; render(); });
  $('#node-form').elements.kind.addEventListener('change', updateKind);
  $('#search').addEventListener('input', event => { state.query = event.target.value.trim(); render(); });
  $('#status-filter').addEventListener('change', event => { state.status = event.target.value; render(); });
  $('#export').addEventListener('click', () => {
    const rows = [['Código','Nome','Tipo','Localização','Situação','Quantidade','Responsável','Descrição'], ...filtered(assets(state.nodes)).map(node => [node.code,node.name,kinds[node.kind],path(node.parentId),statuses[node.status],node.quantity,node.responsible,node.description])];
    // Guard spreadsheet formula prefixes in user-controlled text, including leading whitespace.
    const cell = value => { let text = String(value); if (/^[\s]*[=+@-]/.test(text)) text = `'${text}`; return `"${text.replace(/"/g, '""')}"`; };
    const csv = '\uFEFF' + rows.map(row => row.map(cell).join(';')).join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], {type:'text/csv;charset=utf-8'}));
    const link = document.createElement('a'); link.href = url; link.download = `patrimonio-ieee-${new Date().toISOString().slice(0,10)}.csv`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000); notify('Inventário exportado em CSV.');
  });
  const theme = $('#theme'); theme.value = document.documentElement.dataset.themePreference || 'system';
  theme.addEventListener('change', () => {
    const value = theme.value; document.documentElement.dataset.themePreference = value;
    if (value === 'system') delete document.documentElement.dataset.mode; else document.documentElement.dataset.mode = value;
    try { localStorage.setItem('ieee-hub-theme', value); } catch (_) { /* Preference still applies to this page. */ }
  });
  if (matchMedia('(max-width:700px)').matches) $('#structure-disclosure').open = false;
  refresh(true).catch(error => {
    $('#metrics').setAttribute('aria-busy', 'false'); $('#metrics').innerHTML = '';
    $('#sync-status').textContent = 'Sem conexão'; feedback(error.message);
  });
})();
