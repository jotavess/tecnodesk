'use strict';

const API_URL = '/api/customers';
const PAGE_SIZE = 10;
let csrfToken = '';
let currentUsername = '';
let allCustomers = [];
let allCompanies = [];
let allServices = [];
let allWorkOrders = [];
let allInventory = [];
let allStockMovements = [];
let allFinance = [];
let allAppointments = [];
let allWarranties = [];
let currentPage = 1;
let activeCustomer = null;
let toastTimer = null;

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const loginForm = $('#loginForm');
const loginError = $('#loginError');
const loginButton = $('#loginButton');
const usernameInput = $('#usernameInput');
const passwordInput = $('#passwordInput');
const customerForm = $('#customerForm');
const formError = $('#formError');
const saveButton = $('#saveButton');
const toastElement = $('#toast');
const searchInput = $('#searchInput');
const statusFilter = $('#statusFilter');
const detailsModal = $('#detailsModal');

async function apiRequest(url, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken) headers['X-CSRF-Token'] = csrfToken;
  const response = await fetch(url, { ...options, method, credentials: 'same-origin', headers });
  const text = await response.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch { data = {}; }
  if (!response.ok) {
    const error = new Error(data.error || `Erro HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return data;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]);
}

function initials(name) {
  return String(name || '?').trim().split(/\s+/).slice(0, 2).map(word => word[0] || '').join('').toLocaleUpperCase('pt-BR');
}

function whatsappUrl(phone) {
  const digits = String(phone || '').replace(/\D/g, '');
  if (digits.length < 10) return '';
  const international = digits.startsWith('55') ? digits : `55${digits}`;
  return `https://wa.me/${international}`;
}

function statusClass(status) {
  return ({ Ativo: 'status-active', Pendente: 'status-pending', Inativo: 'status-inactive' })[status] || 'status-inactive';
}

function statusBadge(status) {
  const safeStatus = ['Ativo', 'Pendente', 'Inativo'].includes(status) ? status : 'Inativo';
  return `<span class="status-badge ${statusClass(safeStatus)}">${safeStatus}</span>`;
}

function customerIdentity(customer) {
  const secondary = customer.personType === 'PJ' ? (customer.tradeName || customer.company || customer.email || customer.phone || '') : (customer.email || customer.phone || customer.company || '');
  const kind = `${customer.personType || 'PF'} · ${customer.recordType || 'Cliente avulso'}`;
  return `<div class="client-cell"><span class="client-avatar" aria-hidden="true">${escapeHtml(initials(customer.name))}</span><span class="client-meta"><span class="client-name">${escapeHtml(customer.name)}</span><span class="client-secondary">${escapeHtml(kind)}${secondary ? ` · ${escapeHtml(secondary)}` : ''}</span></span></div>`;
}

function customerRow(customer, compact = false) {
  const contact = customer.email || customer.phone || '—';
  const place = [customer.city, customer.state].filter(Boolean).join(' / ') || '—';
  if (compact) {
    return `<tr>
      <td>${customerIdentity(customer)}</td>
      <td>${escapeHtml(contact)}</td>
      <td>${statusBadge(customer.status)}</td>
      <td>${escapeHtml(customer.createdAt || '—')}</td>
      <td class="actions-cell"><div class="row-actions"><button type="button" class="table-action" data-action="view" data-id="${Number(customer.id)}" title="Ver detalhes" aria-label="Ver detalhes de ${escapeHtml(customer.name)}">⌕</button></div></td>
    </tr>`;
  }
  return `<tr>
    <td>${customerIdentity(customer)}</td>
    <td>${escapeHtml(customer.cpf || '—')}</td>
    <td><span>${escapeHtml(customer.email || '—')}</span><span class="client-secondary">${escapeHtml(customer.phone || 'Sem telefone')}</span>${whatsappUrl(customer.phone) ? `<a class="whatsapp-contact-link" href="${whatsappUrl(customer.phone)}" target="_blank" rel="noopener noreferrer">Abrir WhatsApp ↗</a>` : ''}${customer.contactName ? `<small class="client-secondary">Contato: ${escapeHtml(customer.contactName)}</small>` : ''}</td>
    <td><span class="service-type-pill">${escapeHtml(customer.serviceType || 'Assistência técnica / manutenção')}</span></td>
    <td>${escapeHtml(place)}</td>
    <td>${statusBadge(customer.status)}</td>
    <td>${escapeHtml(customer.createdAt || '—')}</td>
    <td class="actions-cell"><div class="row-actions">
      <button type="button" class="table-action" data-action="view" data-id="${Number(customer.id)}" title="Ver detalhes" aria-label="Ver detalhes de ${escapeHtml(customer.name)}">⌕</button>
      <button type="button" class="table-action" data-action="edit" data-id="${Number(customer.id)}" title="Editar cadastro" aria-label="Editar ${escapeHtml(customer.name)}">✎</button>
      <button type="button" class="table-action delete" data-action="delete" data-id="${Number(customer.id)}" title="Excluir cadastro" aria-label="Excluir ${escapeHtml(customer.name)}">×</button>
    </div></td>
  </tr>`;
}

function showEmptyRow(colspan, title, message) {
  return `<tr><td colspan="${colspan}" class="empty-cell"><span class="empty-state-icon">▤</span><strong>${escapeHtml(title)}</strong>${escapeHtml(message)}</td></tr>`;
}

async function loadCustomers() {
  try {
    allCustomers = await apiRequest(API_URL);
    allCustomers = Array.isArray(allCustomers) ? allCustomers : [];
    $('#lastUpdated').textContent = new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    updateDashboard();
    renderCustomerTable();
    await loadBusinessData();
    try {
      const health = await apiRequest('/api/health');
      const dbName = health.database === 'PostgreSQL' ? 'PostgreSQL' : 'SQLite local';
      $('#databaseType').textContent = dbName;
      $('#dashboardDatabaseType').textContent = dbName;
    } catch { /* O cadastro já foi carregado; mantém o estado visual sem interromper a tela. */ }
  } catch (error) {
    if (error.status === 401) { showLogin('Sua sessão expirou. Entre novamente para continuar.'); return; }
    showToast(error.message || 'Não foi possível carregar os clientes.', true);
    $('#recentTableBody').innerHTML = showEmptyRow(5, 'Falha ao carregar os registros', 'Confira a conexão com o servidor e tente novamente.');
    $('#customersTableBody').innerHTML = showEmptyRow(8, 'Falha ao carregar os registros', 'Confira a conexão com o servidor e tente novamente.');
  }
}

function updateDashboard() {
  $('#totalCustomers').textContent = String(allCustomers.length);
  const contractorCount = allCustomers.filter(c => c.recordType === 'Empresa contratante').length + allCompanies.filter(c => !allCustomers.some(n => Number(n.legacyCompanyId || 0) === Number(c.id))).length;
  if ($('#dashboardCompanyCount')) $('#dashboardCompanyCount').textContent = String(contractorCount);
  $('#activeCustomers').textContent = String(allCustomers.filter(c => c.status === 'Ativo').length);
  $('#pendingCustomers').textContent = String(allCustomers.filter(c => c.status === 'Pendente').length);
  $('#emailCustomers').textContent = String(allCustomers.filter(c => String(c.email || '').trim()).length);
  const recent = [...allCustomers].sort((a, b) => Number(b.id) - Number(a.id)).slice(0, 5);
  $('#recentTableBody').innerHTML = recent.length ? recent.map(c => customerRow(c, true)).join('') : showEmptyRow(5, 'Sua base começa aqui', 'Cadastre o primeiro cliente para visualizar a atividade recente.');
  $('#recentSummary').textContent = `${allCustomers.length} ${allCustomers.length === 1 ? 'cliente cadastrado' : 'clientes cadastrados'} no total`;
  $('#clientCountPill').textContent = String(allCustomers.length);
}

function filteredCustomers() {
  const term = searchInput.value.trim().toLocaleLowerCase('pt-BR');
  const status = statusFilter.value;
  return allCustomers.filter(customer => {
    const text = [customer.name, customer.email, customer.phone, customer.cpf, customer.company, customer.tradeName, customer.contactName, customer.personType, customer.recordType, customer.serviceType, customer.city, customer.state, customer.address, customer.zipCode, customer.status]
      .filter(Boolean).join(' ').toLocaleLowerCase('pt-BR');
    return (!term || text.includes(term)) && (!status || customer.status === status);
  }).sort((a, b) => Number(b.id) - Number(a.id));
}

function renderCustomerTable() {
  const results = filteredCustomers();
  const pageCount = Math.max(1, Math.ceil(results.length / PAGE_SIZE));
  currentPage = Math.min(Math.max(1, currentPage), pageCount);
  const start = (currentPage - 1) * PAGE_SIZE;
  const displayed = results.slice(start, start + PAGE_SIZE);
  $('#customersTableBody').innerHTML = displayed.length
    ? displayed.map(customer => customerRow(customer, false)).join('')
    : showEmptyRow(8, (searchInput.value || statusFilter.value) ? 'Nenhum resultado encontrado' : 'Nenhum cadastro encontrado', (searchInput.value || statusFilter.value) ? 'Tente ajustar os termos da pesquisa ou limpar os filtros.' : 'Use o botão “Novo cadastro” para adicionar seu primeiro cliente.');
  const shownFrom = results.length ? start + 1 : 0;
  const shownTo = Math.min(start + PAGE_SIZE, results.length);
  $('#tableSummary').textContent = `${shownFrom}–${shownTo} de ${results.length} ${results.length === 1 ? 'cliente' : 'clientes'}`;
  $('#pageIndicator').textContent = `Página ${currentPage} de ${pageCount}`;
  $('#prevPageButton').disabled = currentPage <= 1;
  $('#nextPageButton').disabled = currentPage >= pageCount;
  $('#clientCountPill').textContent = String(results.length);
  const filtersActive = Boolean(searchInput.value.trim() || statusFilter.value);
  $('#activeFilters').classList.toggle('hidden', !filtersActive);
  $('#filterDescription').textContent = [searchInput.value.trim() ? `Busca: “${searchInput.value.trim()}”` : '', statusFilter.value ? `Status: ${statusFilter.value}` : ''].filter(Boolean).join(' · ');
}

function setView(view) {
  const valid = ['dashboard', 'customers', 'new', 'services', 'work-orders', 'inventory', 'finance', 'appointments', 'warranties', 'reports'].includes(view) ? view : 'dashboard';
  $$('.app-view').forEach(section => section.classList.toggle('active', section.id === `view-${valid}`));
  $$('[data-view]').forEach(button => {
    if (button.classList.contains('nav-link')) {
      const active = button.dataset.view === valid;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    }
  });
  const labels = { dashboard: 'Visão geral', customers: 'Pessoas e contratantes', new: $('#customerId').value ? 'Editar cadastro' : 'Novo cadastro', services: 'Catálogo de serviços', 'work-orders': 'Ordens de serviço', inventory: 'Estoque de peças', finance: 'Financeiro', appointments: 'Agenda técnica', warranties: 'Garantias', reports: 'Relatórios' };
  $('#breadcrumbCurrent').textContent = labels[valid];
  window.scrollTo({ top: 0, behavior: 'smooth' });
  if (valid === 'customers') renderCustomerTable();
  if (valid === 'companies') renderCompaniesTable();
  if (valid === 'services') renderServicesTable();
  if (valid === 'work-orders') renderOrdersTable();
  if (valid === 'inventory') { renderInventoryTable(); renderStockMovementsTable(); }
  if (valid === 'finance') renderFinanceTable();
  if (valid === 'appointments') renderAppointmentsTable();
  if (valid === 'warranties') renderWarrantiesTable();
  if (valid === 'reports') renderReports();
}

function resetCustomerForm() {
  customerForm.reset();
  $('#customerId').value = '';
  $('#personTypeInput').value = 'PF';
  $('#recordTypeInput').value = 'Cliente avulso';
  $('#serviceTypeInput').value = 'Assistência técnica / manutenção';
  $('#statusInput').value = 'Ativo';
  $('#formPageTitle').textContent = 'Novo cadastro';
  $('#formPageSubtitle').textContent = 'Preencha os campos para adicionar um cliente à sua base.';
  saveButton.innerHTML = 'Salvar cadastro <span>→</span>';
  formError.classList.add('hidden');
  formError.textContent = '';
  $('#notesCounter').textContent = '0 / 2000';
  updatePersonTypeFields();
}

function updatePersonTypeFields() {
  const isCompany = $('#personTypeInput').value === 'PJ';
  $('#nameInputLabel').innerHTML = `${isCompany ? 'Razão social / nome empresarial' : 'Nome completo'} <span class="required-mark">*</span>`;
  $('#nameInput').placeholder = isCompany ? 'Ex.: Soluções de Tecnologia Ltda.' : 'Ex.: Maria da Silva';
  $('#nameInputHelp').textContent = isCompany ? 'Informe a razão social ou nome empresarial.' : 'Informe o nome completo da pessoa.';
  $('#documentInputLabel').textContent = isCompany ? 'CNPJ' : 'CPF';
  $('#cpfInput').placeholder = isCompany ? '00.000.000/0000-00' : '000.000.000-00';
  $('#birthDateField').classList.toggle('hidden', isCompany);
  $('#contactNameField').classList.toggle('hidden', !isCompany);
  $('#companyInputLabel').textContent = isCompany ? 'Nome fantasia' : 'Empresa / organização (opcional)';
  $('#companyInput').placeholder = isCompany ? 'Nome fantasia da empresa' : 'Nome da organização, se houver';
  $('#companyInputHelp').textContent = isCompany ? 'Preencha caso a empresa use um nome comercial diferente.' : 'Campo opcional para vincular a pessoa a uma organização.';
  $('#birthDateInput').disabled = isCompany;
}

function openNewForm() {
  resetCustomerForm();
  setView('new');
  window.setTimeout(() => $('#nameInput').focus(), 80);
}

function openEditForm(customer) {
  if (!customer) return;
  resetCustomerForm();
  $('#customerId').value = customer.id;
  const fields = {
    personTypeInput: 'personType', recordTypeInput: 'recordType', serviceTypeInput: 'serviceType',
    nameInput: 'name', cpfInput: 'cpf', birthDateInput: 'birthDate', companyInput: 'tradeName', contactNameInput: 'contactName',
    emailInput: 'email', phoneInput: 'phone', addressInput: 'address', neighborhoodInput: 'neighborhood',
    zipCodeInput: 'zipCode', cityInput: 'city', stateInput: 'state', statusInput: 'status', notesInput: 'notes'
  };
  Object.entries(fields).forEach(([elementId, key]) => { $(`#${elementId}`).value = customer[key] || ''; });
  if (!customer.tradeName) $('#companyInput').value = customer.company || '';
  updatePersonTypeFields();
  $('#formPageTitle').textContent = 'Editar cadastro';
  $('#formPageSubtitle').textContent = `Atualize as informações de ${customer.name}.`;
  saveButton.innerHTML = 'Salvar alterações <span>→</span>';
  $('#notesCounter').textContent = `${String(customer.notes || '').length} / 2000`;
  setView('new');
  window.setTimeout(() => $('#nameInput').focus(), 80);
}

function formPayload() {
  return {
    personType: $('#personTypeInput').value,
    recordType: $('#recordTypeInput').value,
    serviceType: $('#serviceTypeInput').value,
    name: $('#nameInput').value.trim(),
    cpf: $('#cpfInput').value.trim(),
    birthDate: $('#personTypeInput').value === 'PJ' ? '' : $('#birthDateInput').value,
    company: $('#companyInput').value.trim(),
    tradeName: $('#companyInput').value.trim(),
    contactName: $('#personTypeInput').value === 'PJ' ? $('#contactNameInput').value.trim() : '',
    email: $('#emailInput').value.trim(),
    phone: $('#phoneInput').value.trim(),
    address: $('#addressInput').value.trim(),
    neighborhood: $('#neighborhoodInput').value.trim(),
    zipCode: $('#zipCodeInput').value.trim(),
    city: $('#cityInput').value.trim(),
    state: $('#stateInput').value.trim().toUpperCase(),
    status: $('#statusInput').value,
    notes: $('#notesInput').value.trim()
  };
}

async function saveCustomer(event) {
  event.preventDefault();
  formError.classList.add('hidden');
  const payload = formPayload();
  if (!payload.name) {
    formError.textContent = 'O nome completo é obrigatório.';
    formError.classList.remove('hidden');
    $('#nameInput').focus();
    return;
  }
  if (payload.state && payload.state.length !== 2) {
    formError.textContent = 'O estado deve ser informado com duas letras, como RO ou SP.';
    formError.classList.remove('hidden');
    $('#stateInput').focus();
    return;
  }
  const id = $('#customerId').value;
  saveButton.disabled = true;
  saveButton.textContent = 'Salvando...';
  try {
    await apiRequest(id ? `${API_URL}/${encodeURIComponent(id)}` : API_URL, {
      method: id ? 'PUT' : 'POST', body: JSON.stringify(payload)
    });
    await loadCustomers();
    setView('customers');
    resetCustomerForm();
    showToast(id ? 'Cadastro atualizado com sucesso.' : 'Pessoa ou contratante cadastrado com sucesso.');
  } catch (error) {
    if (error.status === 401) { showLogin('Sua sessão expirou. Entre novamente.'); return; }
    formError.textContent = error.message || 'Não foi possível salvar o cadastro.';
    formError.classList.remove('hidden');
  } finally {
    saveButton.disabled = false;
    saveButton.innerHTML = $('#customerId').value ? 'Salvar alterações <span>→</span>' : 'Salvar cadastro <span>→</span>';
  }
}

function showDetails(customer) {
  if (!customer) return;
  activeCustomer = customer;
  $('#detailAvatar').textContent = initials(customer.name);
  $('#detailsTitle').textContent = customer.name || 'Cliente';
  $('#detailSubtitle').textContent = `Cadastro #${customer.id} · Criado em ${customer.createdAt || 'data não informada'}`;
  const fullAddress = [customer.address, customer.neighborhood, customer.city, customer.state, customer.zipCode].filter(Boolean).join(', ');
  const field = (label, value, full = false) => `<div class="detail-field${full ? ' full' : ''}"><label>${escapeHtml(label)}</label><span>${escapeHtml(value || 'Não informado')}</span></div>`;
  const customerOrders = [...allWorkOrders].filter(order =>
    String(order.customerId || '') === String(customer.id) ||
    (customer.legacyCompanyId && Number(order.companyId || 0) === Number(customer.legacyCompanyId))
  ).sort((a, b) => Number(b.id) - Number(a.id));
  const orderHistory = customerOrders.length ? customerOrders.slice(0, 12).map(order => `
    <div class="detail-field full">
      <label>OS-${String(order.id).padStart(5, '0')} · ${escapeHtml(order.serviceType || 'Área não informada')}</label>
      <span>${escapeHtml(order.title || 'Chamado sem título')}</span>
      <small class="client-secondary">${escapeHtml(order.serviceName || 'Sem serviço do catálogo')} · ${escapeHtml(order.status || 'Sem status')} · ${escapeHtml(order.scheduledDate || order.createdAt || 'Sem data')}</small>
    </div>`).join('') : `<div class="detail-field full"><label>Histórico de ordens</label><span>Nenhuma ordem de serviço vinculada a este cadastro ainda.</span></div>`;
  $('#detailContent').innerHTML = `
    <h3 class="detail-section-title">IDENTIFICAÇÃO E CONTRATO</h3>
    <div class="detail-grid">${field(customer.personType === 'PJ' ? 'Razão social' : 'Nome completo', customer.name)}${field(customer.personType === 'PJ' ? 'CNPJ' : 'CPF', customer.cpf)}${field('Tipo de pessoa', customer.personType || 'PF')}${field('Relacionamento', customer.recordType || 'Cliente avulso')}${field('Área principal contratada', customer.serviceType || 'Assistência técnica / manutenção', true)}${field('Nome fantasia / organização', customer.tradeName || customer.company)}${field('Responsável / contato principal', customer.contactName)}${customer.personType !== 'PJ' ? field('Nascimento', customer.birthDate ? customer.birthDate.split('-').reverse().join('/') : '') : ''}</div>
    <h3 class="detail-section-title">CONTATO E LOCALIZAÇÃO</h3>
    <div class="detail-grid">${field('E-mail', customer.email)}${field('Telefone / WhatsApp', customer.phone)}${whatsappUrl(customer.phone) ? `<div class="detail-field"><label>Contato rápido</label><a class="whatsapp-contact-link" href="${whatsappUrl(customer.phone)}" target="_blank" rel="noopener noreferrer">Abrir conversa no WhatsApp ↗</a></div>` : ''}${field('Endereço', fullAddress, true)}</div>
    <h3 class="detail-section-title">HISTÓRICO DE CHAMADOS</h3>
    <div class="detail-grid">${orderHistory}</div>
    <h3 class="detail-section-title">ACOMPANHAMENTO</h3>
    <div class="detail-grid">${field('Status', customer.status)}${field('Atualizado em', customer.updatedAt)}${field('Observações', customer.notes, true)}</div>`;
  detailsModal.classList.remove('hidden');
  document.body.style.overflow = 'hidden';
}

function closeDetails() {
  detailsModal.classList.add('hidden');
  document.body.style.overflow = '';
  activeCustomer = null;
}

async function performDelete(customer) {
  if (!customer) return;
  const accepted = window.confirm(`Deseja excluir definitivamente o cadastro de ${customer.name}? Esta ação não pode ser desfeita.`);
  if (!accepted) return;
  try {
    await apiRequest(`${API_URL}/${encodeURIComponent(customer.id)}`, { method: 'DELETE' });
    closeDetails();
    await loadCustomers();
    showToast('Cadastro excluído com sucesso.');
  } catch (error) {
    showToast(error.message || 'Não foi possível excluir o cadastro.', true);
  }
}

async function handleTableAction(event) {
  const button = event.target.closest('button[data-action]');
  if (!button) return;
  const customer = allCustomers.find(item => String(item.id) === button.dataset.id);
  if (!customer) return;
  if (button.dataset.action === 'view') showDetails(customer);
  else if (button.dataset.action === 'edit') openEditForm(customer);
  else if (button.dataset.action === 'delete') performDelete(customer);
}

function exportCsv(customers = allCustomers) {
  if (!customers.length) {
    showToast('Não há clientes para exportar.', true);
    return;
  }
  const headers = ['ID', 'Tipo de pessoa', 'Relacionamento', 'Nome / Razão social', 'CPF / CNPJ', 'Nascimento', 'Nome fantasia / organização', 'Área principal', 'Responsável', 'E-mail', 'Telefone', 'Endereço', 'Bairro', 'Cidade', 'UF', 'CEP', 'Status', 'Observações', 'Criado em', 'Atualizado em'];
  const keys = ['id', 'personType', 'recordType', 'name', 'cpf', 'birthDate', 'tradeName', 'serviceType', 'contactName', 'email', 'phone', 'address', 'neighborhood', 'city', 'state', 'zipCode', 'status', 'notes', 'createdAt', 'updatedAt'];
  const quote = value => `"${String(value ?? '').replace(/"/g, '""')}"`;
  const content = '\uFEFF' + [headers, ...customers.map(customer => keys.map(key => customer[key] ?? ''))].map(row => row.map(quote).join(';')).join('\r\n');
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `clientes-${new Date().toISOString().slice(0, 10)}.csv`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
  showToast(`${customers.length} ${customers.length === 1 ? 'cadastro exportado' : 'cadastros exportados'} em CSV.`);
}

function showToast(message, isError = false) {
  toastElement.textContent = message;
  toastElement.classList.toggle('error', isError);
  toastElement.classList.remove('hidden');
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toastElement.classList.add('hidden'), 3600);
}

function showLogin(message = '') {
  document.body.classList.remove('authenticated');
  document.body.classList.add('auth-required');
  loginError.textContent = message;
  loginError.classList.toggle('hidden', !message);
  passwordInput.value = '';
  window.setTimeout(() => usernameInput.focus(), 50);
}

function showApplication(username) {
  currentUsername = username || 'admin';
  const short = currentUsername.length > 20 ? `${currentUsername.slice(0, 18)}…` : currentUsername;
  const avatar = initials(currentUsername);
  $('#sidebarUsername').textContent = short;
  $('#topUsername').textContent = short;
  $('#sidebarAvatar').textContent = avatar;
  $('#topAvatar').textContent = avatar;
  $('#greetingName').textContent = short;
  $('#todayLabel').textContent = new Date().toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' });
  document.body.classList.remove('auth-required');
  document.body.classList.add('authenticated');
  loginError.classList.add('hidden');
  setView('dashboard');
  loadCustomers();
}

async function initializeAuth() {
  try {
    const csrf = await apiRequest('/api/csrf');
    csrfToken = csrf.csrfToken;
    try {
      const me = await apiRequest('/api/me');
      showApplication(me.username);
    } catch (error) {
      if (error.status === 401) showLogin();
      else showLogin('Não foi possível validar sua sessão. Atualize a página e tente novamente.');
    }
  } catch {
    showLogin('Não foi possível conectar ao servidor. Verifique se a aplicação Java está em execução.');
  }
}

loginForm.addEventListener('submit', async event => {
  event.preventDefault();
  loginError.classList.add('hidden');
  loginButton.disabled = true;
  loginButton.innerHTML = 'Verificando acesso...';
  try {
    const result = await apiRequest('/api/login', {
      method: 'POST',
      body: JSON.stringify({ username: usernameInput.value.trim(), password: passwordInput.value })
    });
    csrfToken = result.csrfToken;
    passwordInput.value = '';
    showApplication(result.username);
  } catch (error) {
    loginError.textContent = error.message || 'Não foi possível entrar.';
    loginError.classList.remove('hidden');
    if (error.status === 403) {
      try { const csrf = await apiRequest('/api/csrf'); csrfToken = csrf.csrfToken; } catch { /* O erro de autenticação já é exibido. */ }
    }
  } finally {
    loginButton.disabled = false;
    loginButton.innerHTML = 'Entrar no painel <span aria-hidden="true">→</span>';
  }
});

$('#togglePassword').addEventListener('click', () => {
  const show = passwordInput.type === 'password';
  passwordInput.type = show ? 'text' : 'password';
  $('#togglePassword').setAttribute('aria-label', show ? 'Ocultar senha' : 'Mostrar senha');
});

$('#logoutButton').addEventListener('click', async () => {
  const button = $('#logoutButton');
  button.disabled = true;
  try {
    await apiRequest('/api/logout', { method: 'POST', body: '{}' });
    csrfToken = '';
    const csrf = await apiRequest('/api/csrf');
    csrfToken = csrf.csrfToken;
    showLogin('Você saiu do sistema. Entre novamente para continuar.');
  } catch (error) {
    showToast(error.message || 'Não foi possível encerrar a sessão.', true);
  } finally { button.disabled = false; }
});

document.addEventListener('click', event => {
  const navButton = event.target.closest('[data-view], [data-nav]');
  if (navButton) {
    event.preventDefault();
    const view = navButton.dataset.view || navButton.dataset.nav;
    if (view === 'new') openNewForm();
    else setView(view);
    return;
  }
});

$('#dashboardNewButton').addEventListener('click', openNewForm);
$('#quickNewButton').addEventListener('click', openNewForm);
$('#customersNewButton').addEventListener('click', openNewForm);
$('#backToCustomersButton').addEventListener('click', () => setView('customers'));
$('#cancelFormButton').addEventListener('click', () => { resetCustomerForm(); setView('customers'); });
$('#dashboardRefreshButton').addEventListener('click', loadCustomers);
$('#exportDashboardButton').addEventListener('click', () => exportCsv(allCustomers));
$('#exportCustomersButton').addEventListener('click', () => exportCsv(filteredCustomers()));
customerForm.addEventListener('submit', saveCustomer);
$('#personTypeInput').addEventListener('change', () => { updatePersonTypeFields(); formatTaxDocument(); });
$('#customersTableBody').addEventListener('click', handleTableAction);
$('#recentTableBody').addEventListener('click', handleTableAction);
searchInput.addEventListener('input', () => { currentPage = 1; renderCustomerTable(); });
statusFilter.addEventListener('change', () => { currentPage = 1; renderCustomerTable(); });
$('#clearFiltersButton').addEventListener('click', () => { searchInput.value = ''; statusFilter.value = ''; currentPage = 1; renderCustomerTable(); });
$('#prevPageButton').addEventListener('click', () => { currentPage -= 1; renderCustomerTable(); });
$('#nextPageButton').addEventListener('click', () => { currentPage += 1; renderCustomerTable(); });
$('#closeDetailsButton').addEventListener('click', closeDetails);
$('#detailCloseButton').addEventListener('click', closeDetails);
$('#detailEditButton').addEventListener('click', () => { const customer = activeCustomer; closeDetails(); openEditForm(customer); });
$('#detailDeleteButton').addEventListener('click', () => performDelete(activeCustomer));
detailsModal.addEventListener('click', event => { if (event.target === detailsModal) closeDetails(); });
$('#notesInput').addEventListener('input', () => { $('#notesCounter').textContent = `${$('#notesInput').value.length} / 2000`; });
function formatTaxDocument() {
  const input = $('#cpfInput');
  const isCompany = $('#personTypeInput').value === 'PJ';
  const digits = input.value.replace(/\D/g, '').slice(0, isCompany ? 14 : 11);
  if (isCompany) {
    let value = digits;
    if (value.length > 2) value = value.replace(/^(\d{2})(\d)/, '$1.$2');
    if (value.length > 6) value = value.replace(/^(\d{2})\.(\d{3})(\d)/, '$1.$2.$3');
    if (value.length > 10) value = value.replace(/^(\d{2})\.(\d{3})\.(\d{3})(\d)/, '$1.$2.$3/$4');
    if (value.length > 15) value = value.replace(/(\d{4})(\d{1,2})$/, '$1-$2');
    input.value = value;
  } else {
    let value = digits;
    if (value.length > 3) value = value.replace(/^(\d{3})(\d)/, '$1.$2');
    if (value.length > 7) value = value.replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3');
    if (value.length > 11) value = value.replace(/(\d{3})(\d{1,2})$/, '$1-$2');
    input.value = value;
  }
}
$('#cpfInput').addEventListener('input', formatTaxDocument);

$('#zipCodeInput').addEventListener('input', event => {
  const digits = event.target.value.replace(/\D/g, '').slice(0, 8);
  event.target.value = digits.length > 5 ? `${digits.slice(0, 5)}-${digits.slice(5)}` : digits;
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && !detailsModal.classList.contains('hidden')) closeDetails();
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && document.body.classList.contains('authenticated')) {
    event.preventDefault();
    setView('customers');
    searchInput.focus();
  }
});



// Módulos de empresas, catálogo técnico e ordens de serviço.
function money(value) {
  return Number(value || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

function moduleStatusBadge(status) {
  const active = ['Ativo', 'Ativa', 'Concluída', 'Pago', 'Agendado', 'Vigente'].includes(status);
  const pending = ['Pendente', 'Solicitação recebida', 'Aberta', 'Em andamento', 'Aguardando peça', 'Atrasado', 'Acionada'].includes(status);
  const cls = active ? 'status-active' : pending ? 'status-pending' : 'status-inactive';
  return `<span class="status-badge ${cls}">${escapeHtml(status || '—')}</span>`;
}

function orderPriorityBadge(priority) {
  const cls = priority === 'Urgente' ? 'priority-urgent' : priority === 'Alta' ? 'priority-high' : priority === 'Baixa' ? 'priority-low' : 'priority-normal';
  return `<span class="priority-badge ${cls}">${escapeHtml(priority || 'Normal')}</span>`;
}

async function loadBusinessData() {
  try {
    const [companies, services, orders, inventory, movements, finance, appointments, warranties] = await Promise.all([
      apiRequest('/api/companies'), apiRequest('/api/services'), apiRequest('/api/work-orders'),
      apiRequest('/api/inventory'), apiRequest('/api/stock-movements'), apiRequest('/api/finance'),
      apiRequest('/api/appointments'), apiRequest('/api/warranties')
    ]);
    allCompanies = Array.isArray(companies) ? companies : [];
    allServices = Array.isArray(services) ? services : [];
    allWorkOrders = Array.isArray(orders) ? orders : [];
    allInventory = Array.isArray(inventory) ? inventory : [];
    allStockMovements = Array.isArray(movements) ? movements : [];
    allFinance = Array.isArray(finance) ? finance : [];
    allAppointments = Array.isArray(appointments) ? appointments : [];
    allWarranties = Array.isArray(warranties) ? warranties : [];
    renderCompaniesTable();
    renderServicesTable();
    renderOrdersTable();
    fillOrderSelects();
    fillOperationsSelects();
    fillPixOrderSelect();
    renderInventoryTable();
    renderStockMovementsTable();
    renderFinanceTable();
    renderAppointmentsTable();
    renderWarrantiesTable();
    renderOperationsDashboard();
    updateDashboard();
    await loadPixSettings();
    fillPixOrderSelect();
    const orderCount = $('#dashboardOrderCount');
    if (orderCount) orderCount.textContent = String(allWorkOrders.filter(order => !['Concluída', 'Cancelada'].includes(order.status)).length);
  } catch (error) {
    if (error.status === 401) showLogin('Sua sessão expirou. Entre novamente para continuar.');
    else showToast(error.message || 'Não foi possível carregar empresas e serviços.', true);
  }
}

function filteredModuleRows(rows, query, keys) {
  const needle = String(query || '').trim().toLocaleLowerCase('pt-BR');
  return rows.filter(row => !needle || keys.map(key => row[key] ?? '').join(' ').toLocaleLowerCase('pt-BR').includes(needle));
}

function renderCompaniesTable() {
  const body = $('#companiesTableBody');
  if (!body) return;
  const rows = filteredModuleRows(allCompanies, $('#companySearch')?.value, ['name', 'tradeName', 'cnpj', 'email', 'phone', 'contactName', 'city', 'state', 'status']);
  $('#companyCount').textContent = String(rows.length);
  body.innerHTML = rows.length ? rows.map(company => `<tr>
    <td><div class="module-primary-cell"><span class="module-avatar">${escapeHtml(initials(company.tradeName || company.name))}</span><div><strong>${escapeHtml(company.tradeName || company.name)}</strong><small>${escapeHtml(company.tradeName && company.name !== company.tradeName ? company.name : (company.email || 'Empresa contratante'))}</small></div></div></td>
    <td>${escapeHtml(company.cnpj || '—')}</td><td><span>${escapeHtml(company.contactName || '—')}</span><span class="client-secondary">${escapeHtml(company.phone || company.email || 'Sem contato')}</span></td>
    <td>${escapeHtml([company.city, company.state].filter(Boolean).join(' / ') || '—')}</td><td>${moduleStatusBadge(company.status)}</td>
    <td><div class="row-actions"><button type="button" class="table-action" data-module-action="edit-company" data-id="${Number(company.id)}" title="Editar empresa">✎</button><button type="button" class="table-action delete" data-module-action="delete-company" data-id="${Number(company.id)}" title="Excluir empresa">×</button></div></td>
  </tr>`).join('') : `<tr><td class="empty-cell" colspan="6"><span class="empty-state-icon">▦</span><strong>Nenhuma empresa encontrada</strong>Cadastre uma empresa contratante para vinculá-la às ordens de serviço.</td></tr>`;
}

function renderServicesTable() {
  const body = $('#servicesTableBody');
  if (!body) return;
  const rows = filteredModuleRows(allServices, $('#serviceSearch')?.value, ['name', 'category', 'description', 'status']);
  $('#serviceCount').textContent = String(rows.length);
  body.innerHTML = rows.length ? rows.map(service => `<tr>
    <td><div class="module-primary-cell"><span class="module-avatar service-avatar">⚙</span><div><strong>${escapeHtml(service.name)}</strong><small>${escapeHtml(service.description || 'Serviço do catálogo')}</small></div></div></td>
    <td>${escapeHtml(service.category || 'Outros')}</td><td><strong class="price-cell">${Number(service.defaultPrice)>0?money(service.defaultPrice):'Sob orçamento'}</strong></td><td>${Number(service.minPrice||0)>0||Number(service.maxPrice||0)>0?`${money(service.minPrice)}–${money(service.maxPrice)}`:'—'}</td><td>${escapeHtml(service.pricingUnit||'por atendimento')}</td><td>${service.publicEnabled?'<span class="status-badge status-active">Publicado</span>':'<span class="status-badge status-inactive">Interno</span>'}</td><td>${Number(service.estimatedHours || 0).toLocaleString('pt-BR')} h</td><td>${moduleStatusBadge(service.status)}</td>
    <td><div class="row-actions"><button type="button" class="table-action" data-module-action="edit-service" data-id="${Number(service.id)}" title="Editar serviço">✎</button><button type="button" class="table-action delete" data-module-action="delete-service" data-id="${Number(service.id)}" title="Excluir serviço">×</button></div></td>
  </tr>`).join('') : `<tr><td class="empty-cell" colspan="9"><span class="empty-state-icon">⚙</span><strong>Seu catálogo está vazio</strong>Adicione os serviços que você presta, com preço e tempo estimado.</td></tr>`;
}

function renderOrdersTable() {
  const body = $('#ordersTableBody');
  if (!body) return;
  const query = $('#orderSearch')?.value || '';
  const status = $('#orderStatusFilter')?.value || '';
  const rows = filteredModuleRows(allWorkOrders, query, ['id', 'title', 'companyName', 'companyTradeName', 'customerName', 'serviceName', 'serviceType', 'technician', 'status', 'priority']).filter(order => !status || order.status === status);
  $('#orderCount').textContent = String(rows.length);
  body.innerHTML = rows.length ? rows.map(order => {
    const customerOrCompany = order.companyTradeName || order.companyName || order.customerName || 'Sem vínculo';
    const number = `OS-${String(order.id).padStart(5, '0')}`;
    const publicOrigin = String(order.notes || '').includes('Origem: portal público');
    return `<tr>
      <td><strong class="os-number">${number}</strong><small class="client-secondary">${escapeHtml(order.scheduledDate || order.createdAt || '')}</small></td>
      <td><div class="module-table-main">${escapeHtml(customerOrCompany)}</div><small class="client-secondary">${escapeHtml(order.customerName && (order.companyName || order.companyTradeName) ? `Contato: ${order.customerName}` : 'Contratante')}</small></td>
      <td><div class="module-table-main">${escapeHtml(order.title)}</div><small class="client-secondary">${escapeHtml(order.serviceType || 'Área não informada')}${order.serviceName ? ` · ${escapeHtml(order.serviceName)}` : ''}</small>${publicOrigin ? '<span class="service-type-pill">Portal público</span>' : ''}</td>
      <td>${escapeHtml(order.technician || 'Não atribuído')}</td><td>${moduleStatusBadge(order.status)}</td><td>${orderPriorityBadge(order.priority)}</td><td><strong class="price-cell">${money(order.price)}</strong></td>
      <td><div class="row-actions"><button type="button" class="table-action" data-module-action="edit-order" data-id="${Number(order.id)}" title="Editar ordem">✎</button><button type="button" class="table-action delete" data-module-action="delete-order" data-id="${Number(order.id)}" title="Excluir ordem">×</button></div></td>
    </tr>`;
  }).join('') : `<tr><td class="empty-cell" colspan="8"><span class="empty-state-icon">▣</span><strong>Nenhuma ordem de serviço encontrada</strong>Abra uma ordem para registrar o serviço contratado e acompanhar seu andamento.</td></tr>`;
}

function makePartyOptions(emptyLabel, includeLegacy = true) {
  const unified = [...allCustomers].sort((a,b) => String(a.name).localeCompare(String(b.name), 'pt-BR'));
  const customerOptions = unified.map(c => {
    const type = c.personType || 'PF';
    const relation = c.recordType || 'Cliente avulso';
    const area = c.serviceType || 'Assistência técnica / manutenção';
    const statusLabel = c.status === 'Inativo' ? ' · INATIVO' : '';
    return `<option value="customer:${Number(c.id)}">[${escapeHtml(type)} · ${escapeHtml(relation)}${statusLabel}] ${escapeHtml(c.name)} — ${escapeHtml(area)}</option>`;
  }).join('');
  const imported = new Set(unified.map(c => Number(c.legacyCompanyId || 0)).filter(Boolean));
  const legacyOptions = includeLegacy ? allCompanies.filter(c => c.status !== 'Inativa' && !imported.has(Number(c.id))).map(c => `<option value="company:${Number(c.id)}">[Empresa legada] ${escapeHtml(c.tradeName || c.name)}</option>`).join('') : '';
  return `<option value="">${escapeHtml(emptyLabel)}</option>${customerOptions}${legacyOptions}`;
}
function fillOrderSelects() {
  const partySelect = $('#woParty');
  const serviceSelect = $('#woService');
  if (!partySelect || !serviceSelect) return;
  const oldParty = partySelect.value, oldService = serviceSelect.value;
  partySelect.innerHTML = makePartyOptions('Selecione a pessoa ou empresa contratante');
  serviceSelect.innerHTML = '<option value="">Selecione um serviço específico (opcional)</option>' + allServices.filter(s => s.status === 'Ativo').map(s => `<option value="${Number(s.id)}" data-category="${escapeHtml(s.category || '')}" data-price="${Number(s.defaultPrice || 0)}">${escapeHtml(s.name)} — ${money(s.defaultPrice)}</option>`).join('');
  if ([...partySelect.options].some(option => option.value === oldParty)) partySelect.value = oldParty;
  if ([...serviceSelect.options].some(option => option.value === oldService)) serviceSelect.value = oldService;
}

function resetCompanyForm() {
  $('#companyForm').reset(); $('#companyId').value = ''; $('#coStatus').value = 'Ativa';
  $('#companyFormTitle').textContent = 'Cadastrar empresa'; $('#companySaveButton').textContent = 'Salvar empresa →';
  $('#companyFormError').classList.add('hidden');
}
function openCompanyForm(company = null) {
  resetCompanyForm();
  if (company) {
    $('#companyId').value = company.id;
    const fields = { coName: 'name', coTradeName: 'tradeName', coCnpj: 'cnpj', coStatus: 'status', coContact: 'contactName', coPhone: 'phone', coEmail: 'email', coAddress: 'address', coCity: 'city', coState: 'state', coNotes: 'notes' };
    Object.entries(fields).forEach(([id, key]) => { $(`#${id}`).value = company[key] || ''; });
    $('#companyFormTitle').textContent = 'Editar empresa'; $('#companySaveButton').textContent = 'Salvar alterações →';
  }
  $('#companyFormPanel').classList.remove('hidden');
  $('#companyFormPanel').scrollIntoView({ behavior: 'smooth', block: 'start' });
  $('#coName').focus();
}
function closeCompanyForm() { $('#companyFormPanel').classList.add('hidden'); resetCompanyForm(); }
async function saveCompany(event) {
  event.preventDefault(); const id = $('#companyId').value; const errorBox = $('#companyFormError'); errorBox.classList.add('hidden');
  const payload = { name: $('#coName').value.trim(), tradeName: $('#coTradeName').value.trim(), cnpj: $('#coCnpj').value.trim(), email: $('#coEmail').value.trim(), phone: $('#coPhone').value.trim(), contactName: $('#coContact').value.trim(), address: $('#coAddress').value.trim(), city: $('#coCity').value.trim(), state: $('#coState').value.trim().toUpperCase(), status: $('#coStatus').value, notes: $('#coNotes').value.trim() };
  try {
    await apiRequest(id ? `/api/companies/${encodeURIComponent(id)}` : '/api/companies', { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) });
    closeCompanyForm(); await loadBusinessData(); showToast(id ? 'Empresa atualizada.' : 'Empresa cadastrada.');
  } catch (error) { errorBox.textContent = error.message || 'Não foi possível salvar a empresa.'; errorBox.classList.remove('hidden'); }
}

function resetServiceForm() {
  $('#serviceForm').reset(); $('#serviceId').value = ''; $('#svStatus').value = 'Ativo'; $('#svPrice').value = '0'; $('#svMinPrice').value='0'; $('#svMaxPrice').value='0'; $('#svPricingUnit').value='por atendimento'; $('#svPublicEnabled').checked=false; $('#svHours').value = '1';
  $('#serviceFormTitle').textContent = 'Cadastrar serviço'; $('#serviceSaveButton').textContent = 'Salvar serviço →'; $('#serviceFormError').classList.add('hidden');
}
function openServiceForm(service = null) {
  resetServiceForm();
  if (service) {
    $('#serviceId').value = service.id;
    const fields = { svName: 'name', svCategory: 'category', svPrice: 'defaultPrice', svMinPrice: 'minPrice', svMaxPrice: 'maxPrice', svPricingUnit: 'pricingUnit', svHours: 'estimatedHours', svStatus: 'status', svDescription: 'description' };
    Object.entries(fields).forEach(([id, key]) => { $(`#${id}`).value = service[key] ?? ''; });
    $('#svPublicEnabled').checked = Boolean(service.publicEnabled);
    $('#serviceFormTitle').textContent = 'Editar serviço'; $('#serviceSaveButton').textContent = 'Salvar alterações →';
  }
  $('#serviceFormPanel').classList.remove('hidden'); $('#serviceFormPanel').scrollIntoView({ behavior: 'smooth', block: 'start' }); $('#svName').focus();
}
function closeServiceForm() { $('#serviceFormPanel').classList.add('hidden'); resetServiceForm(); }
async function saveService(event) {
  event.preventDefault(); const id = $('#serviceId').value; const errorBox = $('#serviceFormError'); errorBox.classList.add('hidden');
  const payload = { name: $('#svName').value.trim(), category: $('#svCategory').value, description: $('#svDescription').value.trim(), defaultPrice: $('#svPrice').value || '0', minPrice: $('#svMinPrice').value || '0', maxPrice: $('#svMaxPrice').value || '0', pricingUnit: $('#svPricingUnit').value, publicEnabled: $('#svPublicEnabled').checked, estimatedHours: $('#svHours').value || '0', status: $('#svStatus').value };
  try { await apiRequest(id ? `/api/services/${encodeURIComponent(id)}` : '/api/services', { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) }); closeServiceForm(); await loadBusinessData(); showToast(id ? 'Serviço atualizado.' : 'Serviço cadastrado.'); }
  catch (error) { errorBox.textContent = error.message || 'Não foi possível salvar o serviço.'; errorBox.classList.remove('hidden'); }
}

function resetOrderForm() {
  $('#orderForm').reset(); $('#orderId').value = ''; $('#woStatus').value = 'Aberta'; $('#woPriority').value = 'Normal'; $('#woPrice').value = '0';
  $('#orderFormTitle').textContent = 'Nova ordem de serviço'; $('#orderSaveButton').textContent = 'Salvar ordem →'; $('#orderFormError').classList.add('hidden'); fillOrderSelects();
}
function openOrderForm(order = null) {
  resetOrderForm();
  if (order) {
    $('#orderId').value = order.id;
    const importedCompany = order.companyId ? allCustomers.find(c => Number(c.legacyCompanyId || 0) === Number(order.companyId)) : null;
    const partyRef = order.customerId ? `customer:${order.customerId}` : importedCompany ? `customer:${importedCompany.id}` : order.companyId ? `company:${order.companyId}` : '';
    $('#woParty').value = partyRef;
    const fields = { woService: 'serviceId', woServiceType: 'serviceType', woTitle: 'title', woDescription: 'description', woStatus: 'status', woPriority: 'priority', woTechnician: 'technician', woScheduled: 'scheduledDate', woCompleted: 'completedDate', woPrice: 'price', woNotes: 'notes' };
    Object.entries(fields).forEach(([id, key]) => { $(`#${id}`).value = order[key] == null ? '' : order[key]; });
    $('#orderFormTitle').textContent = `Editar ordem OS-${String(order.id).padStart(5, '0')}`; $('#orderSaveButton').textContent = 'Salvar alterações →';
  }
  $('#orderFormPanel').classList.remove('hidden'); $('#orderFormPanel').scrollIntoView({ behavior: 'smooth', block: 'start' }); $('#woTitle').focus();
}
function closeOrderForm() { $('#orderFormPanel').classList.add('hidden'); resetOrderForm(); }
async function saveOrder(event) {
  event.preventDefault(); const id = $('#orderId').value; const errorBox = $('#orderFormError'); errorBox.classList.add('hidden');
  const partyRef = $('#woParty').value;
  const partyParts = partyRef.split(':');
  const payload = { companyId: partyParts[0] === 'company' ? partyParts[1] : '', customerId: partyParts[0] === 'customer' ? partyParts[1] : '', serviceId: $('#woService').value, serviceType: $('#woServiceType').value, title: $('#woTitle').value.trim(), description: $('#woDescription').value.trim(), status: $('#woStatus').value, priority: $('#woPriority').value, technician: $('#woTechnician').value.trim(), scheduledDate: $('#woScheduled').value, completedDate: $('#woCompleted').value, price: $('#woPrice').value || '0', notes: $('#woNotes').value.trim() };
  if (!partyRef) { errorBox.textContent = 'Selecione a pessoa ou empresa contratante.'; errorBox.classList.remove('hidden'); return; }
  try { await apiRequest(id ? `/api/work-orders/${encodeURIComponent(id)}` : '/api/work-orders', { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) }); closeOrderForm(); await loadBusinessData(); showToast(id ? 'Ordem de serviço atualizada.' : 'Ordem de serviço aberta.'); }
  catch (error) { errorBox.textContent = error.message || 'Não foi possível salvar a ordem de serviço.'; errorBox.classList.remove('hidden'); }
}

async function deleteModuleRecord(kind, id) {
  const config = { company: { endpoint: '/api/companies', noun: 'empresa' }, service: { endpoint: '/api/services', noun: 'serviço' }, order: { endpoint: '/api/work-orders', noun: 'ordem de serviço' } }[kind];
  if (!config || !window.confirm(`Deseja realmente excluir este registro (${config.noun})? Esta ação não pode ser desfeita.`)) return;
  try { await apiRequest(`${config.endpoint}/${encodeURIComponent(id)}`, { method: 'DELETE' }); await loadBusinessData(); showToast(`${config.noun[0].toUpperCase()}${config.noun.slice(1)} excluído(a).`); }
  catch (error) { showToast(error.message || 'Não foi possível excluir o registro.', true); }
}

document.addEventListener('click', event => {
  const action = event.target.closest('[data-module-action]');
  if (!action) return;
  const id = Number(action.dataset.id); const type = action.dataset.moduleAction;
  if (type === 'edit-company') openCompanyForm(allCompanies.find(item => Number(item.id) === id));
  else if (type === 'edit-service') openServiceForm(allServices.find(item => Number(item.id) === id));
  else if (type === 'edit-order') openOrderForm(allWorkOrders.find(item => Number(item.id) === id));
  else if (type === 'delete-company') deleteModuleRecord('company', id);
  else if (type === 'delete-service') deleteModuleRecord('service', id);
  else if (type === 'delete-order') deleteModuleRecord('order', id);
});

$('#companyNewButton').addEventListener('click', () => openCompanyForm());
$('#companyCloseForm').addEventListener('click', closeCompanyForm);
$('#companyCancelButton').addEventListener('click', closeCompanyForm);
$('#companyForm').addEventListener('submit', saveCompany);
$('#companySearch').addEventListener('input', renderCompaniesTable);
$('#serviceNewButton').addEventListener('click', () => openServiceForm());
$('#serviceCloseForm').addEventListener('click', closeServiceForm);
$('#serviceCancelButton').addEventListener('click', closeServiceForm);
$('#serviceForm').addEventListener('submit', saveService);
$('#serviceSearch').addEventListener('input', renderServicesTable);
$('#orderNewButton').addEventListener('click', () => openOrderForm());
$('#orderCloseForm').addEventListener('click', closeOrderForm);
$('#orderCancelButton').addEventListener('click', closeOrderForm);
$('#orderForm').addEventListener('submit', saveOrder);
$('#woParty').addEventListener('change', () => { const raw = $('#woParty').value; if (raw.startsWith('customer:')) { const customer = byId(allCustomers, raw.split(':')[1]); if (customer?.serviceType) $('#woServiceType').value = customer.serviceType; } });
function serviceAreaFromCategory(category) {
  const raw = String(category || '').trim();
  const exact = ['Assistência técnica / manutenção','Software e sistemas','Redes e infraestrutura','Suporte técnico','Consultoria','Outro'];
  if (exact.includes(raw)) return raw;
  const normalized = raw.toLocaleLowerCase('pt-BR');
  if (normalized.includes('computador') || normalized.includes('impressora') || normalized.includes('instalação') || normalized.includes('configuração') || normalized.includes('manutenção')) return 'Assistência técnica / manutenção';
  if (normalized.includes('software') || normalized.includes('sistema')) return 'Software e sistemas';
  if (normalized.includes('rede') || normalized.includes('infraestrutura')) return 'Redes e infraestrutura';
  if (normalized.includes('suporte') || normalized.includes('remoto')) return 'Suporte técnico';
  if (normalized.includes('consultoria')) return 'Consultoria';
  return 'Outro';
}
$('#woService').addEventListener('change', () => { const category = $('#woService').selectedOptions[0]?.dataset.category || ''; if (category) $('#woServiceType').value = serviceAreaFromCategory(category); });
$('#orderSearch').addEventListener('input', renderOrdersTable);
$('#orderStatusFilter').addEventListener('change', renderOrdersTable);
$('#woService').addEventListener('change', () => {
  const service = allServices.find(item => String(item.id) === $('#woService').value);
  if (!service) return;
  if (!$('#orderId').value && !$('#woTitle').value.trim()) $('#woTitle').value = service.name;
  if (!$('#orderId').value && (Number($('#woPrice').value || 0) === 0)) $('#woPrice').value = Number(service.defaultPrice || 0).toFixed(2);
});


initializeAuth();

// Módulos operacionais: estoque, financeiro, agenda, garantias e relatórios.
const OPERATION_CONFIG = {
  inventory: { endpoint: '/api/inventory', panel: 'inventoryFormPanel', form: 'inventoryForm', id: 'inventoryId', error: 'inventoryFormError', title: 'inventoryFormTitle', save: 'inventorySaveButton', fields: { stockName: 'name', stockSku: 'sku', stockCategory: 'category', stockQuantity: 'quantity', stockMinimum: 'minimumQuantity', stockCost: 'costPrice', stockSale: 'salePrice', stockSupplier: 'supplier', stockNotes: 'notes' }, rows: () => allInventory, noun: 'item de estoque' },
  finance: { endpoint: '/api/finance', panel: 'financeFormPanel', form: 'financeForm', id: 'financeId', error: 'financeFormError', title: 'financeFormTitle', save: 'financeSaveButton', fields: { financeType: 'type', financeDescription: 'description', financeCategory: 'category', financeAmount: 'amount', financeDueDate: 'dueDate', financePaymentDate: 'paymentDate', financeStatus: 'status', financeParty: 'partyRef', financeOrder: 'workOrderId', financeNotes: 'notes' }, rows: () => allFinance, noun: 'lançamento' },
  appointments: { endpoint: '/api/appointments', panel: 'appointmentFormPanel', form: 'appointmentForm', id: 'appointmentId', error: 'appointmentFormError', title: 'appointmentFormTitle', save: 'appointmentSaveButton', fields: { appointmentTitle: 'title', appointmentType: 'type', appointmentDate: 'scheduledDateTime', appointmentParty: 'partyRef', appointmentOrder: 'workOrderId', appointmentTechnician: 'technician', appointmentStatus: 'status', appointmentNotes: 'notes' }, rows: () => allAppointments, noun: 'agendamento' },
  warranties: { endpoint: '/api/warranties', panel: 'warrantyFormPanel', form: 'warrantyForm', id: 'warrantyId', error: 'warrantyFormError', title: 'warrantyFormTitle', save: 'warrantySaveButton', fields: { warrantyItem: 'itemOrService', warrantyParty: 'partyRef', warrantyOrder: 'workOrderId', warrantyStart: 'startDate', warrantyEnd: 'endDate', warrantyStatus: 'status', warrantyTerms: 'terms', warrantyNotes: 'notes' }, rows: () => allWarranties, noun: 'garantia' }
};

function escapeCsvCell(value) {
  const text = String(value ?? '');
  return `"${text.replace(/"/g, '""')}"`;
}
function downloadCsv(filename, rows) {
  if (!rows.length) { showToast('Não há dados para exportar.', true); return; }
  const csv = '\uFEFF' + rows.map(row => row.map(escapeCsvCell).join(';')).join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
}
function displayDate(value) {
  if (!value) return '—';
  const normalized = String(value).slice(0, 10);
  const match = normalized.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  return match ? `${match[3]}/${match[2]}/${match[1]}` : String(value).replace('T', ' ');
}
function displayDateTime(value) {
  if (!value) return '—';
  const parts = String(value).split('T');
  return `${displayDate(parts[0])}${parts[1] ? ` · ${parts[1].slice(0, 5)}` : ''}`;
}
function byId(rows, id) { return rows.find(row => String(row.id) === String(id)) || null; }
function companyLabel(id) { const item = byId(allCompanies, id); return item ? (item.tradeName || item.name) : ''; }
function customerLabel(id) { const item = byId(allCustomers, id); return item ? item.name : ''; }
function orderLabel(id) { const item = byId(allWorkOrders, id); return item ? `OS-${String(item.id).padStart(5, '0')} · ${item.title}` : ''; }
function relatedParty(companyId, customerId) { return customerLabel(customerId) || companyLabel(companyId) || '—'; }
function partyRefFromIds(companyId, customerId) {
  if (customerId) return `customer:${customerId}`;
  if (companyId) {
    const imported = allCustomers.find(item => Number(item.legacyCompanyId || 0) === Number(companyId));
    if (imported) return `customer:${imported.id}`;
    return `company:${companyId}`;
  }
  return '';
}
function parsePartyRef(ref) { const [kind, rawId] = String(ref || '').split(':'); return { companyId: kind === 'company' ? rawId : '', customerId: kind === 'customer' ? rawId : '' }; }
function fillPartySelect(id, emptyText) { const select = $(`#${id}`); if (!select) return; const current = select.value; select.innerHTML = makePartyOptions(emptyText); if ([...select.options].some(option => option.value === current)) select.value = current; }
function operationActionButtons(module, row) {
  return `<div class="module-actions"><button type="button" class="table-action" data-ops-action="edit-${module}" data-id="${Number(row.id)}" title="Editar" aria-label="Editar">✎</button><button type="button" class="table-action delete" data-ops-action="delete-${module}" data-id="${Number(row.id)}" title="Excluir" aria-label="Excluir">×</button></div>`;
}
function moduleEmpty(colspan, title, message) { return `<tr><td class="empty-cell" colspan="${colspan}"><strong>${escapeHtml(title)}</strong><br><span>${escapeHtml(message)}</span></td></tr>`; }
function fillSelectOptions(id, emptyText, rows, labelFunction) {
  const select = $(`#${id}`); if (!select) return;
  const current = select.value;
  select.innerHTML = `<option value="">${escapeHtml(emptyText)}</option>` + rows.map(row => `<option value="${Number(row.id)}">${escapeHtml(labelFunction(row))}</option>`).join('');
  if ([...select.options].some(option => option.value === current)) select.value = current;
}
function fillOperationsSelects() {
  fillSelectOptions('movementItem', 'Selecione uma peça', allInventory, item => `${item.name} · saldo ${Number(item.quantity || 0)}`);
  fillPartySelect('financeParty', 'Nenhum contratante');
  fillSelectOptions('financeOrder', 'Nenhuma OS', allWorkOrders, order => `OS-${String(order.id).padStart(5, '0')} · ${order.title}`);
  fillPartySelect('appointmentParty', 'Nenhum contratante');
  fillSelectOptions('appointmentOrder', 'Nenhuma OS', allWorkOrders, order => `OS-${String(order.id).padStart(5, '0')} · ${order.title}`);
  fillPartySelect('warrantyParty', 'Nenhum contratante');
  fillSelectOptions('warrantyOrder', 'Nenhuma OS', allWorkOrders, order => `OS-${String(order.id).padStart(5, '0')} · ${order.title}`);
}
function statusForWarranty(item) {
  if (item.status === 'Vigente' && item.endDate && item.endDate < new Date().toISOString().slice(0, 10)) return 'Expirada';
  return item.status || 'Vigente';
}
function isLowStock(item) { return Number(item.quantity || 0) <= Number(item.minimumQuantity || 0); }

function renderInventoryTable() {
  const body = $('#inventoryTableBody'); if (!body) return;
  const term = ($('#inventorySearch')?.value || '').trim().toLocaleLowerCase('pt-BR');
  const rows = allInventory.filter(item => [item.name, item.sku, item.category, item.supplier].some(value => String(value || '').toLocaleLowerCase('pt-BR').includes(term)));
  $('#inventoryCount').textContent = String(allInventory.length);
  body.innerHTML = rows.length ? rows.map(item => {
    const low = isLowStock(item);
    return `<tr><td><div class="operational-row-primary"><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.supplier || 'Fornecedor não informado')}</small></div></td><td>${escapeHtml(item.sku || '—')}</td><td>${escapeHtml(item.category || '—')}</td><td><span class="quantity-cell ${low ? 'quantity-low' : ''}">${Number(item.quantity || 0)}</span><small class="client-secondary">Mínimo: ${Number(item.minimumQuantity || 0)}</small></td><td class="price-cell">${money(item.costPrice)}</td><td>${low ? '<span class="status-badge status-pending">Estoque baixo</span>' : '<span class="status-badge status-active">Normal</span>'}</td><td class="actions-cell">${operationActionButtons('inventory', item)}</td></tr>`;
  }).join('') : moduleEmpty(7, term ? 'Nenhum item encontrado' : 'Estoque vazio', term ? 'Experimente outro termo de busca.' : 'Cadastre a primeira peça ou equipamento para começar.');
  renderOperationsDashboard();
}
function renderStockMovementsTable() {
  const body = $('#stockMovementsTableBody'); if (!body) return;
  $('#stockMovementCount').textContent = String(allStockMovements.length);
  body.innerHTML = allStockMovements.length ? allStockMovements.slice(0, 30).map(item => `<tr><td>${escapeHtml(displayDateTime(item.createdAt))}</td><td>${escapeHtml(item.inventoryName || byId(allInventory, item.inventoryId)?.name || 'Item removido')}</td><td><span class="${item.kind === 'Entrada' ? 'movement-in' : 'movement-out'}">${escapeHtml(item.kind || '—')}</span></td><td class="quantity-cell">${Number(item.quantity || 0)}</td><td>${escapeHtml(item.reason || '—')}</td></tr>`).join('') : moduleEmpty(5, 'Nenhuma movimentação', 'Registre uma entrada ou saída para manter o histórico de estoque.');
}
function renderFinanceTable() {
  const body = $('#financeTableBody'); if (!body) return;
  const term = ($('#financeSearch')?.value || '').trim().toLocaleLowerCase('pt-BR');
  const status = $('#financeStatusFilter')?.value || '';
  const rows = allFinance.filter(item => (!status || item.status === status) && [item.description, item.category, item.type, item.notes].some(value => String(value || '').toLocaleLowerCase('pt-BR').includes(term)));
  $('#financeCount').textContent = String(allFinance.length);
  const paidIncome = allFinance.filter(item => item.type === 'Receita' && item.status === 'Pago').reduce((sum, item) => sum + Number(item.amount || 0), 0);
  const paidExpenses = allFinance.filter(item => item.type === 'Despesa' && item.status === 'Pago').reduce((sum, item) => sum + Number(item.amount || 0), 0);
  const toReceive = allFinance.filter(item => item.type === 'Receita' && ['Pendente', 'Atrasado'].includes(item.status)).reduce((sum, item) => sum + Number(item.amount || 0), 0);
  const toPay = allFinance.filter(item => item.type === 'Despesa' && ['Pendente', 'Atrasado'].includes(item.status)).reduce((sum, item) => sum + Number(item.amount || 0), 0);
  $('#financePaidIncome').textContent = money(paidIncome); $('#financePaidExpenses').textContent = money(paidExpenses);
  $('#financeToReceive').textContent = money(toReceive); $('#financeToPay').textContent = money(toPay);
  body.innerHTML = rows.length ? rows.map(item => `<tr><td>${item.type === 'Receita' ? '<span class="positive-money">Receita</span>' : '<span class="negative-money">Despesa</span>'}</td><td><div class="operational-row-primary"><strong>${escapeHtml(item.description)}</strong><small>${escapeHtml(item.category || 'Sem categoria')}</small></div></td><td>${escapeHtml(relatedParty(item.companyId, item.customerId))}<small class="client-secondary">${escapeHtml(orderLabel(item.workOrderId) || '')}</small></td><td>${escapeHtml(displayDate(item.dueDate))}</td><td class="price-cell">${money(item.amount)}</td><td>${moduleStatusBadge(item.status)}</td><td class="actions-cell">${operationActionButtons('finance', item)}</td></tr>`).join('') : moduleEmpty(7, term || status ? 'Nenhum lançamento encontrado' : 'Sem lançamentos financeiros', 'Adicione uma receita ou despesa para começar a acompanhar o caixa.');
  renderOperationsDashboard();
}
function renderAppointmentsTable() {
  const body = $('#appointmentsTableBody'); if (!body) return;
  const term = ($('#appointmentSearch')?.value || '').trim().toLocaleLowerCase('pt-BR');
  const status = $('#appointmentStatusFilter')?.value || '';
  const rows = [...allAppointments].filter(item => (!status || item.status === status) && [item.title, item.type, item.technician, companyLabel(item.companyId), customerLabel(item.customerId)].some(value => String(value || '').toLocaleLowerCase('pt-BR').includes(term))).sort((a,b) => String(a.scheduledDateTime || '').localeCompare(String(b.scheduledDateTime || '')));
  $('#appointmentCount').textContent = String(allAppointments.length);
  body.innerHTML = rows.length ? rows.map(item => `<tr><td>${escapeHtml(displayDateTime(item.scheduledDateTime))}</td><td><div class="operational-row-primary"><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.type || 'Visita técnica')}${item.workOrderId ? ` · ${escapeHtml(orderLabel(item.workOrderId))}` : ''}</small></div></td><td>${escapeHtml(relatedParty(item.companyId, item.customerId))}</td><td>${escapeHtml(item.technician || '—')}</td><td>${moduleStatusBadge(item.status)}</td><td class="actions-cell">${operationActionButtons('appointments', item)}</td></tr>`).join('') : moduleEmpty(6, term || status ? 'Nenhum agendamento encontrado' : 'Agenda vazia', 'Cadastre uma visita, retorno ou manutenção preventiva.');
  renderOperationsDashboard();
}
function renderWarrantiesTable() {
  const body = $('#warrantiesTableBody'); if (!body) return;
  const term = ($('#warrantySearch')?.value || '').trim().toLocaleLowerCase('pt-BR');
  const filterStatus = $('#warrantyStatusFilter')?.value || '';
  const rows = allWarranties.filter(item => (!filterStatus || statusForWarranty(item) === filterStatus) && [item.itemOrService, item.terms, item.notes, companyLabel(item.companyId), customerLabel(item.customerId)].some(value => String(value || '').toLocaleLowerCase('pt-BR').includes(term)));
  $('#warrantyCount').textContent = String(allWarranties.length);
  body.innerHTML = rows.length ? rows.map(item => `<tr><td><div class="operational-row-primary"><strong>${escapeHtml(item.itemOrService)}</strong><small>${escapeHtml(item.terms || 'Sem termos registrados')}</small></div></td><td>${escapeHtml(relatedParty(item.companyId, item.customerId))}</td><td>${escapeHtml(displayDate(item.startDate))} <small class="client-secondary">até ${escapeHtml(displayDate(item.endDate))}</small></td><td>${escapeHtml(item.workOrderId ? orderLabel(item.workOrderId) : '—')}</td><td>${moduleStatusBadge(statusForWarranty(item))}</td><td class="actions-cell">${operationActionButtons('warranties', item)}</td></tr>`).join('') : moduleEmpty(6, term || filterStatus ? 'Nenhuma garantia encontrada' : 'Nenhuma garantia registrada', 'Cadastre a garantia oferecida para um serviço ou equipamento.');
}
function renderOperationsDashboard() {
  const lowStock = allInventory.filter(isLowStock).length;
  const pendingFinance = allFinance.filter(item => ['Pendente', 'Atrasado'].includes(item.status)).length;
  const now = new Date();
  const upcoming = allAppointments.filter(item => item.status === 'Agendado' && item.scheduledDateTime && new Date(item.scheduledDateTime) >= now).length;
  const revenue = allFinance.filter(item => item.type === 'Receita' && item.status === 'Pago').reduce((sum, item) => sum + Number(item.amount || 0), 0);
  if ($('#dashboardLowStockCount')) $('#dashboardLowStockCount').textContent = String(lowStock);
  if ($('#dashboardPendingFinanceCount')) $('#dashboardPendingFinanceCount').textContent = String(pendingFinance);
  if ($('#dashboardUpcomingCount')) $('#dashboardUpcomingCount').textContent = String(upcoming);
  if ($('#dashboardRevenueTotal')) $('#dashboardRevenueTotal').textContent = money(revenue);
}
function renderReports() {
  const paidRevenue = allFinance.filter(item => item.type === 'Receita' && item.status === 'Pago').reduce((sum,item) => sum + Number(item.amount || 0), 0);
  const paidExpenses = allFinance.filter(item => item.type === 'Despesa' && item.status === 'Pago').reduce((sum,item) => sum + Number(item.amount || 0), 0);
  const openOrders = allWorkOrders.filter(item => !['Concluída','Cancelada'].includes(item.status));
  $('#reportRevenue').textContent = money(paidRevenue); $('#reportExpenses').textContent = money(paidExpenses); $('#reportBalance').textContent = money(paidRevenue - paidExpenses);
  $('#reportOpenOrders').textContent = String(openOrders.length); $('#reportLowStock').textContent = String(allInventory.filter(isLowStock).length);
  const services = new Map();
  allWorkOrders.forEach(order => { const name = order.serviceName || order.title || 'Serviço sem categoria'; const item = services.get(name) || { name, count: 0, value: 0 }; item.count++; item.value += Number(order.price || 0); services.set(name,item); });
  const serviceRows = [...services.values()].sort((a,b) => b.count-a.count).slice(0,10);
  $('#reportServicesBody').innerHTML = serviceRows.length ? serviceRows.map(item => `<tr><td>${escapeHtml(item.name)}</td><td>${item.count}</td><td class="price-cell">${money(item.value)}</td></tr>`).join('') : moduleEmpty(3,'Sem ordens cadastradas','As estatísticas de serviços aparecem quando houver ordens.');
  const companies = new Map();
  allWorkOrders.forEach(order => { const name = order.companyName || order.customerName || 'Cliente / contratante não vinculado'; const item = companies.get(name) || { name, count: 0, value: 0 }; item.count++; item.value += Number(order.price || 0); companies.set(name,item); });
  const companyRows = [...companies.values()].sort((a,b) => b.count-a.count).slice(0,10);
  $('#reportCompaniesBody').innerHTML = companyRows.length ? companyRows.map(item => `<tr><td>${escapeHtml(item.name)}</td><td>${item.count}</td><td class="price-cell">${money(item.value)}</td></tr>`).join('') : moduleEmpty(3,'Sem dados por empresa','Vincule as ordens às empresas contratantes para comparar atendimentos.');
  const now = new Date();
  const upcoming = allAppointments.filter(item => item.status === 'Agendado' && item.scheduledDateTime && new Date(item.scheduledDateTime) >= now).sort((a,b) => String(a.scheduledDateTime).localeCompare(String(b.scheduledDateTime))).slice(0,8);
  $('#reportAppointmentsBody').innerHTML = upcoming.length ? upcoming.map(item => `<tr><td>${escapeHtml(displayDateTime(item.scheduledDateTime))}</td><td>${escapeHtml(item.title)}</td><td>${escapeHtml(item.technician || '—')}</td><td>${moduleStatusBadge(item.status)}</td></tr>`).join('') : moduleEmpty(4,'Nenhum compromisso futuro','A agenda está livre ou não há agendamentos em aberto.');
  const pendingFinance = allFinance.filter(item => ['Pendente','Atrasado'].includes(item.status)).sort((a,b) => String(a.dueDate || '').localeCompare(String(b.dueDate || ''))).slice(0,10);
  $('#reportPendingFinanceBody').innerHTML = pendingFinance.length ? pendingFinance.map(item => `<tr><td>${escapeHtml(item.type)}</td><td>${escapeHtml(item.description)}</td><td>${escapeHtml(displayDate(item.dueDate))}</td><td class="price-cell">${money(item.amount)}</td></tr>`).join('') : moduleEmpty(4,'Sem pendências','Nenhuma receita ou despesa pendente foi encontrada.');
}
function resetOperationsForm(module) {
  const config = OPERATION_CONFIG[module]; if (!config) return;
  $(`#${config.form}`).reset(); $(`#${config.id}`).value = ''; $(`#${config.error}`).classList.add('hidden');
  if (module === 'inventory') { $('#stockQuantity').readOnly = false; $('#stockQuantity').value = '0'; $('#stockMinimum').value = '1'; $('#stockCost').value = '0'; $('#stockSale').value = '0'; $('#inventoryFormTitle').textContent = 'Nova peça / equipamento'; $('#inventorySaveButton').textContent = 'Salvar peça →'; }
  if (module === 'finance') { $('#financeType').value = 'Receita'; $('#financeStatus').value = 'Pendente'; $('#financeAmount').value = '0'; $('#financeFormTitle').textContent = 'Novo lançamento'; $('#financeSaveButton').textContent = 'Salvar lançamento →'; }
  if (module === 'appointments') { $('#appointmentType').value = 'Visita técnica'; $('#appointmentStatus').value = 'Agendado'; $('#appointmentFormTitle').textContent = 'Novo agendamento'; $('#appointmentSaveButton').textContent = 'Salvar agendamento →'; }
  if (module === 'warranties') { $('#warrantyStatus').value = 'Vigente'; $('#warrantyFormTitle').textContent = 'Nova garantia'; $('#warrantySaveButton').textContent = 'Salvar garantia →'; }
  fillOperationsSelects();
}
function openOperationsForm(module, row = null) {
  const config = OPERATION_CONFIG[module]; if (!config) return;
  resetOperationsForm(module);
  if (row) {
    $(`#${config.id}`).value = row.id;
    Object.entries(config.fields).forEach(([elementId,key]) => {
      const element = $(`#${elementId}`); let value = row[key];
      if (key === 'partyRef') value = partyRefFromIds(row.companyId, row.customerId);
      element.value = value == null ? '' : String(value);
    });
    if (module === 'inventory') { $('#stockQuantity').readOnly = true; $('#inventoryFormTitle').textContent = `Editar item #${row.id}`; $('#inventorySaveButton').textContent = 'Salvar alterações →'; }
    if (module === 'finance') { $('#financeFormTitle').textContent = `Editar lançamento #${row.id}`; $('#financeSaveButton').textContent = 'Salvar alterações →'; }
    if (module === 'appointments') { $('#appointmentFormTitle').textContent = `Editar agendamento #${row.id}`; $('#appointmentSaveButton').textContent = 'Salvar alterações →'; }
    if (module === 'warranties') { $('#warrantyFormTitle').textContent = `Editar garantia #${row.id}`; $('#warrantySaveButton').textContent = 'Salvar alterações →'; }
  }
  $(`#${config.panel}`).classList.remove('hidden');
  $(`#${config.panel}`).scrollIntoView({ behavior:'smooth', block:'start' });
  const firstInput = $(`#${config.form} input:not([type=hidden]), #${config.form} select`); firstInput?.focus();
}
function closeOperationsForm(module) {
  const config = OPERATION_CONFIG[module]; if (!config) return;
  $(`#${config.panel}`).classList.add('hidden'); resetOperationsForm(module);
}
async function saveOperationsForm(event, module) {
  event.preventDefault(); const config = OPERATION_CONFIG[module]; if (!config) return;
  const id = $(`#${config.id}`).value; const errorBox = $(`#${config.error}`); errorBox.classList.add('hidden');
  const payload = {};
  Object.entries(config.fields).forEach(([elementId,key]) => { payload[key] = $(`#${elementId}`).value.trim(); });
  if (['finance','appointments','warranties'].includes(module)) {
    const party = parsePartyRef(payload.partyRef); delete payload.partyRef; payload.companyId = party.companyId; payload.customerId = party.customerId;
  }
  if (module === 'inventory' && id) payload.quantity = String(byId(allInventory,id)?.quantity ?? 0);
  try {
    await apiRequest(id ? `${config.endpoint}/${encodeURIComponent(id)}` : config.endpoint, { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) });
    closeOperationsForm(module); await loadBusinessData(); showToast(id ? 'Registro atualizado com sucesso.' : 'Registro cadastrado com sucesso.');
  } catch (error) { errorBox.textContent = error.message || `Não foi possível salvar ${config.noun}.`; errorBox.classList.remove('hidden'); }
}
async function deleteOperationsRecord(module, id) {
  const config = OPERATION_CONFIG[module]; if (!config) return;
  if (!window.confirm(`Deseja realmente excluir este ${config.noun}? Esta ação não pode ser desfeita.`)) return;
  try { await apiRequest(`${config.endpoint}/${encodeURIComponent(id)}`, { method:'DELETE' }); await loadBusinessData(); showToast(`${config.noun[0].toUpperCase()}${config.noun.slice(1)} excluído(a).`); }
  catch (error) { showToast(error.message || 'Não foi possível excluir o registro.', true); }
}
async function saveStockMovement(event) {
  event.preventDefault(); $('#movementFormError').classList.add('hidden');
  const payload = { inventoryId: $('#movementItem').value, kind: $('#movementKind').value, quantity: $('#movementQuantity').value, reason: $('#movementReason').value.trim() };
  try {
    await apiRequest('/api/stock-movements', { method:'POST', body:JSON.stringify(payload) });
    $('#stockMovementPanel').classList.add('hidden'); $('#stockMovementForm').reset(); $('#movementQuantity').value='1';
    await loadBusinessData(); showToast('Movimentação registrada e saldo atualizado.');
  } catch (error) { $('#movementFormError').textContent = error.message || 'Não foi possível registrar a movimentação.'; $('#movementFormError').classList.remove('hidden'); }
}
function exportFinanceCsv() {
  downloadCsv('tecnodesk-financeiro.csv', [['Tipo','Descrição','Categoria','Valor','Vencimento','Pagamento','Status','Cliente / contratante','Ordem de serviço'], ...allFinance.map(item => [item.type,item.description,item.category,item.amount,item.dueDate,item.paymentDate,item.status,relatedParty(item.companyId,item.customerId),orderLabel(item.workOrderId)])]);
}
function exportReportsCsv() {
  const rows = [['Indicador','Valor'],['Receitas pagas',allFinance.filter(i=>i.type==='Receita'&&i.status==='Pago').reduce((s,i)=>s+Number(i.amount||0),0)],['Despesas pagas',allFinance.filter(i=>i.type==='Despesa'&&i.status==='Pago').reduce((s,i)=>s+Number(i.amount||0),0)],['Saldo gerencial',allFinance.filter(i=>i.type==='Receita'&&i.status==='Pago').reduce((s,i)=>s+Number(i.amount||0),0)-allFinance.filter(i=>i.type==='Despesa'&&i.status==='Pago').reduce((s,i)=>s+Number(i.amount||0),0)],['Cadastros unificados',allCustomers.length],['Empresas contratantes',allCustomers.filter(c=>c.recordType==='Empresa contratante').length + allCompanies.filter(c=>!allCustomers.some(n=>Number(n.legacyCompanyId||0)===Number(c.id))).length],['Ordens de serviço',allWorkOrders.length],['Ordens em aberto',allWorkOrders.filter(i=>!['Concluída','Cancelada'].includes(i.status)).length],['Itens de estoque',allInventory.length],['Itens abaixo do mínimo',allInventory.filter(isLowStock).length],['Agendamentos futuros',allAppointments.filter(i=>i.status==='Agendado'&&i.scheduledDateTime&&new Date(i.scheduledDateTime)>=new Date()).length],['Garantias cadastradas',allWarranties.length]];
  downloadCsv('tecnodesk-relatorio-gerencial.csv', rows);
}

// Eventos dos módulos operacionais.
async function loadPixSettings() {
  if (!$('#pixSettingsForm')) return;
  try {
    const settings = await apiRequest('/api/payment-settings');
    $('#pixKeyInput').value = settings.pixKey || '';
    $('#pixReceiverInput').value = settings.receiverName || '';
    $('#pixCityInput').value = settings.city || '';
    $('#pixSettingsMessage').textContent = settings.configured ? 'Pix configurado. Gere cobranças apenas depois de aprovar o valor da OS.' : 'Configure a chave Pix para habilitar a geração de cobranças.';
    $('#pixSettingsMessage').classList.remove('hidden');
    $('#pixSettingsMessage').classList.toggle('success-message', Boolean(settings.configured));
  } catch (error) {
    if (error.status === 401) showLogin('Sua sessão expirou. Entre novamente para continuar.');
    else { $('#pixSettingsMessage').textContent = error.message || 'Não foi possível carregar as configurações Pix.'; $('#pixSettingsMessage').classList.remove('hidden'); }
  }
}
function fillPixOrderSelect() {
  const select = $('#pixOrderSelect'); if (!select) return;
  const previous = select.value;
  const orders = allWorkOrders.filter(order => order.status === 'Aguardando pagamento' && Number(order.price || 0) > 0).sort((a,b)=>Number(b.id)-Number(a.id));
  select.innerHTML = '<option value="">Selecione uma OS aprovada</option>' + orders.map(order => `<option value="${Number(order.id)}">OS-${String(order.id).padStart(5,'0')} · ${escapeHtml(order.customerName || order.companyTradeName || order.companyName || 'Cliente')} · ${money(order.price)}</option>`).join('');
  if (orders.some(o => String(o.id) === previous)) select.value = previous;
}
async function savePixSettings(event) {
  event.preventDefault(); const button=$('#pixSettingsSaveButton'); const msg=$('#pixSettingsMessage'); button.disabled=true; msg.classList.add('hidden');
  try { await apiRequest('/api/payment-settings',{method:'PUT',body:JSON.stringify({pixKey:$('#pixKeyInput').value.trim(),receiverName:$('#pixReceiverInput').value.trim(),city:$('#pixCityInput').value.trim()})}); msg.textContent='Configuração Pix salva com sucesso.'; msg.classList.remove('hidden'); msg.classList.add('success-message'); showToast('Configuração Pix salva.'); }
  catch(error){msg.textContent=error.message||'Não foi possível salvar a configuração Pix.';msg.classList.remove('hidden');msg.classList.remove('success-message');}
  finally{button.disabled=false;}
}
async function generatePix() {
  const id=$('#pixOrderSelect').value; const errorBox=$('#pixGenerateError'); errorBox.classList.add('hidden');
  if(!id){errorBox.textContent='Selecione uma OS com orçamento revisado e status “Aguardando pagamento”.';errorBox.classList.remove('hidden');return;}
  const button=$('#pixGenerateButton');button.disabled=true;button.textContent='Gerando QR Code…';
  try{
    const result=await apiRequest('/api/pix/generate',{method:'POST',body:JSON.stringify({orderId:id})});
    $('#pixResultTitle').textContent=result.requestNumber;$('#pixResultCustomer').textContent=`${result.customerName} · ${result.title}`;$('#pixResultAmount').textContent=money(result.amount);$('#pixQrImage').src=`data:image/png;base64,${result.qrPngBase64}`;$('#pixCopyPaste').value=result.pixPayload;$('#pixResult').dataset.orderId=String(result.orderId);$('#pixResult').classList.remove('hidden');$('#pixResult').scrollIntoView({behavior:'smooth',block:'center'});showToast('QR Code Pix gerado. Confira os dados do recebedor no app bancário.');
  }catch(error){errorBox.textContent=error.message||'Não foi possível gerar o QR Code.';errorBox.classList.remove('hidden');}
  finally{button.disabled=false;button.innerHTML='<span class="button-symbol">▦</span> Gerar QR Code Pix';}
}
async function confirmPixReceived() {
  const orderId=$('#pixResult').dataset.orderId;if(!orderId)return;
  if(!window.confirm('Você já conferiu no aplicativo do Itaú que o valor foi creditado? Esta ação marcará o pagamento como recebido e criará uma receita no financeiro.'))return;
  try{const result=await apiRequest('/api/pix/confirm',{method:'POST',body:JSON.stringify({orderId})});$('#pixResult').classList.add('hidden');showToast(result.message||'Recebimento registrado.');await loadBusinessData();}
  catch(error){showToast(error.message||'Não foi possível registrar o pagamento.',true);}
}
async function copyPixPayload(){const value=$('#pixCopyPaste').value;try{await navigator.clipboard.writeText(value);showToast('Código Pix copiado.');}catch{const field=$('#pixCopyPaste');field.focus();field.select();const ok=document.execCommand('copy');showToast(ok?'Código Pix copiado.':'Selecione e copie o código manualmente.',!ok);}}
function downloadPixQr(){const img=$('#pixQrImage');if(!img.src)return;const a=document.createElement('a');a.href=img.src;a.download=`pix-${$('#pixResultTitle').textContent.toLowerCase()}.png`;a.click();}

$('#inventoryNewButton').addEventListener('click', () => openOperationsForm('inventory'));
$('#inventoryCloseForm').addEventListener('click', () => closeOperationsForm('inventory'));
$('#inventoryCancelButton').addEventListener('click', () => closeOperationsForm('inventory'));
$('#inventoryForm').addEventListener('submit', event => saveOperationsForm(event, 'inventory'));
$('#inventorySearch').addEventListener('input', renderInventoryTable);
$('#stockMoveOpenButton').addEventListener('click', () => { fillOperationsSelects(); $('#stockMovementPanel').classList.remove('hidden'); $('#stockMovementPanel').scrollIntoView({behavior:'smooth',block:'start'}); });
$('#stockMoveCloseButton').addEventListener('click', () => $('#stockMovementPanel').classList.add('hidden'));
$('#stockMoveCancelButton').addEventListener('click', () => $('#stockMovementPanel').classList.add('hidden'));
$('#stockMovementForm').addEventListener('submit', saveStockMovement);
$('#financeNewButton').addEventListener('click', () => openOperationsForm('finance'));
$('#financeCloseForm').addEventListener('click', () => closeOperationsForm('finance'));
$('#financeCancelButton').addEventListener('click', () => closeOperationsForm('finance'));
$('#financeForm').addEventListener('submit', event => saveOperationsForm(event, 'finance'));
$('#financeSearch').addEventListener('input', renderFinanceTable);
$('#financeStatusFilter').addEventListener('change', renderFinanceTable);
$('#financeExportButton').addEventListener('click', exportFinanceCsv);
$('#pixSettingsForm').addEventListener('submit', savePixSettings);
$('#pixGenerateButton').addEventListener('click', generatePix);
$('#pixCopyButton').addEventListener('click', copyPixPayload);
$('#pixDownloadButton').addEventListener('click', downloadPixQr);
$('#pixConfirmButton').addEventListener('click', confirmPixReceived);
$('#appointmentNewButton').addEventListener('click', () => openOperationsForm('appointments'));
$('#appointmentCloseForm').addEventListener('click', () => closeOperationsForm('appointments'));
$('#appointmentCancelButton').addEventListener('click', () => closeOperationsForm('appointments'));
$('#appointmentForm').addEventListener('submit', event => saveOperationsForm(event, 'appointments'));
$('#appointmentSearch').addEventListener('input', renderAppointmentsTable);
$('#appointmentStatusFilter').addEventListener('change', renderAppointmentsTable);
$('#warrantyNewButton').addEventListener('click', () => openOperationsForm('warranties'));
$('#warrantyCloseForm').addEventListener('click', () => closeOperationsForm('warranties'));
$('#warrantyCancelButton').addEventListener('click', () => closeOperationsForm('warranties'));
$('#warrantyForm').addEventListener('submit', event => saveOperationsForm(event, 'warranties'));
$('#warrantySearch').addEventListener('input', renderWarrantiesTable);
$('#warrantyStatusFilter').addEventListener('change', renderWarrantiesTable);
$('#reportsRefreshButton').addEventListener('click', async () => { await loadCustomers(); await loadBusinessData(); renderReports(); showToast('Relatórios atualizados.'); });
$('#reportsExportButton').addEventListener('click', exportReportsCsv);
document.addEventListener('click', event => {
  const action = event.target.closest('[data-ops-action]'); if (!action) return;
  const [verb, module] = action.dataset.opsAction.split('-'); const id = Number(action.dataset.id);
  if (verb === 'edit' && OPERATION_CONFIG[module]) openOperationsForm(module, byId(OPERATION_CONFIG[module].rows(), id));
  else if (verb === 'delete' && OPERATION_CONFIG[module]) deleteOperationsRecord(module, id);
});

