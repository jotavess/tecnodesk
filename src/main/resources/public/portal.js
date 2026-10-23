(() => {
  'use strict';
  const form = $('#requestForm');
  const alertBox = $('#formAlert');
  const submitButton = $('#submitButton');
  const submitDefault = submitButton.innerHTML;
  let csrfToken = '';
  let publicServices = [];
  const formatMoney = value => Number(value || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  const unitMultiplier = unit => ['por hora', 'por equipamento', 'por instalação'].includes(String(unit || '').toLowerCase());
  function selectedPublicService() { return publicServices.find(item => String(item.id) === $('#publicServiceId').value) || null; }
  function loadPublicServiceOptions() {
    const select = $('#publicServiceId');
    select.innerHTML = '<option value="">Selecione um serviço</option>' + publicServices.map(item => `<option value="${Number(item.id)}">${escapeHtml(item.name)} — ${Number(item.defaultPrice || 0) > 0 ? formatMoney(item.defaultPrice) : 'Sob orçamento'}</option>`).join('');
    updateEstimate();
  }
  function updateEstimate() {
    const item = selectedPublicService();
    const quantity = Math.min(20, Math.max(1, Number.parseInt($('#serviceQuantity').value || '1', 10) || 1));
    const quantityField = $('#quantityField');
    if (!item) { quantityField.classList.add('hidden'); $('#publicEstimateAmount').textContent = 'Selecione um serviço'; $('#publicEstimateDetails').textContent = 'A estimativa não é uma proposta final. Você receberá contato para validar o escopo, valores e disponibilidade.'; return; }
    const isMultiply = unitMultiplier(item.pricingUnit);
    quantityField.classList.toggle('hidden', !isMultiply);
    $('#quantityLabel').textContent = item.pricingUnit === 'por hora' ? 'Quantidade de horas' : item.pricingUnit === 'por equipamento' ? 'Quantidade de equipamentos' : 'Quantidade de instalações';
    $('#quantityHelp').textContent = `Cobrança ${item.pricingUnit}. Quantidade máxima por solicitação: 20.`;
    $('#serviceQuantity').value = String(quantity);
    $('#serviceType').value = item.category || 'Outro';
    if (Number(item.defaultPrice || 0) <= 0 || item.pricingUnit === 'sob orçamento') {
      $('#publicEstimateAmount').textContent = 'Sob orçamento';
      $('#publicEstimateDetails').textContent = item.description || 'Vamos analisar o escopo e entrar em contato com uma proposta. Nenhuma cobrança é gerada nesta etapa.';
      return;
    }
    const multiplier = isMultiply ? quantity : 1;
    const amount = Number(item.defaultPrice) * multiplier;
    const min = Number(item.minPrice || 0) * multiplier;
    const max = Number(item.maxPrice || 0) * multiplier;
    $('#publicEstimateAmount').textContent = formatMoney(amount);
    const range = min > 0 || max > 0 ? `Faixa de referência: ${formatMoney(min)} a ${formatMoney(max)}. ` : '';
    $('#publicEstimateDetails').textContent = `${range}Valor inicial sugerido pela tabela de preços. O administrador revisará o escopo e aprovará o valor final antes de qualquer cobrança. Peças, licenças, materiais e deslocamento podem ser cobrados à parte.`;
  }
  function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]); }
  async function loadPublicServices() {
    const response = await fetch('/api/public-services', { credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
    const data = await response.json().catch(() => []);
    if (!response.ok || !Array.isArray(data) || !data.length) throw new Error('A tabela de serviços está indisponível. Entre em contato pelo canal informado no site.');
    publicServices = data; loadPublicServiceOptions();
  }

  function showAlert(message, type = 'error') {
    alertBox.textContent = message;
    alertBox.classList.remove('hidden', 'success');
    if (type === 'success') alertBox.classList.add('success');
  }
  function clearAlert() { alertBox.classList.add('hidden'); alertBox.textContent = ''; }
  function currentPersonType() { return $('input[name="personType"]:checked').value; }
  function updatePersonType() {
    const isBusiness = currentPersonType() === 'PJ';
    $$('.business-field').forEach(el => el.classList.toggle('hidden', !isBusiness));
    $('#name').labels[0].childNodes[0].textContent = isBusiness ? 'Razão social / nome da empresa ' : 'Nome completo ';
    $('#name').placeholder = isBusiness ? 'Nome empresarial ou razão social' : 'Seu nome completo';
  }
  function $(selector, root = document) { return root.querySelector(selector); }
  function $$(selector, root = document) { return [...root.querySelectorAll(selector)]; }
  async function refreshCsrf() {
    const response = await fetch('/api/csrf', { method: 'GET', credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
    const data = await response.json().catch(() => ({}));
    if (!response.ok || !data.csrfToken) throw new Error('Não foi possível preparar o formulário. Atualize a página.');
    csrfToken = data.csrfToken;
  }
  function trim(value) { return String(value || '').trim(); }
  function setSubmitting(isSubmitting) {
    submitButton.disabled = isSubmitting;
    submitButton.innerHTML = isSubmitting ? 'Enviando solicitação…' : submitDefault;
  }
  function validateForm(payload) {
    if (!payload.name) return 'Informe seu nome ou a razão social da empresa.';
    if (!payload.serviceId) return 'Selecione o serviço que deseja solicitar.';
    if (!payload.phone || payload.phone.replace(/\D/g, '').length < 8) return 'Informe um telefone ou WhatsApp válido.';
    if (payload.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(payload.email)) return 'Confira o endereço de e-mail informado.';
    if (payload.contactPreference === 'E-mail' && !payload.email) return 'Informe um e-mail para que possamos entrar em contato por esse meio.';
    if (!payload.title || payload.title.length < 4) return 'Descreva brevemente o assunto do chamado.';
    if (!payload.description || payload.description.length < 15) return 'Conte um pouco mais sobre o problema (mínimo de 15 caracteres).';
    if (!payload.consent) return 'Marque a autorização para entrarmos em contato sobre a solicitação.';
    return '';
  }
  async function handleSubmit(event) {
    event.preventDefault(); clearAlert();
    const data = new FormData(form);
    const payload = {
      personType: currentPersonType(), name: trim(data.get('name')), tradeName: trim(data.get('tradeName')),
      serviceId: trim(data.get('serviceId')), quantity: trim(data.get('quantity')) || '1',
      contactName: trim(data.get('contactName')), phone: trim(data.get('phone')), email: trim(data.get('email')),
      serviceType: trim(data.get('serviceType')), title: trim(data.get('title')), description: trim(data.get('description')),
      serviceMode: trim(data.get('serviceMode')), contactPreference: trim(data.get('contactPreference')),
      city: trim(data.get('city')), state: trim(data.get('state')).toUpperCase(), address: trim(data.get('address')),
      website: trim(data.get('website')), consent: $('#consent').checked
    };
    const validationError = validateForm(payload);
    if (validationError) { showAlert(validationError); return; }
    setSubmitting(true);
    try {
      if (!csrfToken) await refreshCsrf();
      const response = await fetch('/api/public-requests', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify(payload)
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) {
        if (response.status === 403) { csrfToken = ''; await refreshCsrf(); }
        throw new Error(result.error || 'Não foi possível enviar a solicitação. Tente novamente.');
      }
      $('#requestNumber').textContent = result.requestNumber || 'Solicitação registrada';
      $('#requestEstimate').textContent = result.quoteOnly ? 'Sob orçamento' : formatMoney(result.estimatedPrice);
      $('#requestEstimateNote').textContent = result.quoteOnly ? 'Este serviço exige avaliação do escopo. Você será contatado para receber uma proposta.' : `Estimativa inicial para ${result.serviceName || 'o serviço'}. O valor ainda será revisado e aprovado pelo responsável antes de gerar qualquer cobrança. Não é necessário pagar agora.`;
      form.classList.add('hidden');
      $('#successPanel').classList.remove('hidden');
      clearAlert();
      $('#successPanel').scrollIntoView({ behavior: 'smooth', block: 'center' });
    } catch (error) {
      showAlert(error.message || 'Não foi possível enviar a solicitação. Verifique sua conexão e tente novamente.');
    } finally { setSubmitting(false); }
  }

  form.addEventListener('submit', handleSubmit);
  $$('input[name="personType"]').forEach(input => input.addEventListener('change', updatePersonType));
  $('#description').addEventListener('input', event => { $('#descriptionCount').textContent = `${event.target.value.length} / 3000`; });
  $('#publicServiceId').addEventListener('change', updateEstimate);
  $('#serviceQuantity').addEventListener('input', updateEstimate);
  $$('[data-service]').forEach(button => button.addEventListener('click', () => {
    $('#serviceType').value = button.dataset.service;
    const first = publicServices.find(item => item.category === button.dataset.service);
    if (first) { $('#publicServiceId').value = String(first.id); updateEstimate(); }
    $('#solicitar').scrollIntoView({ behavior: 'smooth' });
    window.setTimeout(() => $('#title').focus({ preventScroll: true }), 350);
  }));
  $('#newRequestButton').addEventListener('click', () => {
    form.reset(); form.classList.remove('hidden'); $('#successPanel').classList.add('hidden');
    $('#descriptionCount').textContent = '0 / 3000'; updatePersonType(); clearAlert(); updateEstimate();
    $('#solicitar').scrollIntoView({ behavior: 'smooth' });
  });
  updatePersonType();
  refreshCsrf().catch(error => showAlert(error.message || 'Não foi possível carregar o formulário. Atualize a página.'));
  loadPublicServices().catch(error => { showAlert(error.message || 'Não foi possível carregar a tabela de serviços.'); $('#submitButton').disabled = true; });
})();
