/** @fileoverview Ledger controls with exact string amount transport. */

import {RequestStore} from './request-store.js';

const requests = new RequestStore({
  getItem: (key) => localStorage.getItem(key),
  setItem: (key, value) => localStorage.setItem(key, value),
  removeItem: (key) => localStorage.removeItem(key),
});
// These worker failures follow the successful-key lookup and confirm rollback.
// Other errors cannot resolve a prior attempt with an unknown outcome.
const resolvedFailureCodes = new Set([
  'INSUFFICIENT_FUNDS', 'BALANCE_OVERFLOW', 'ALREADY_REVERSED',
  'MISSING_FX_RATE', 'INVALID_FX_RATE', 'ZERO_FX_CREDIT',
  'FX_NOT_REPRESENTABLE', 'CLOCK_REGRESSION', 'OPERATION_FAILED',
]);
let isInFlight = false;
let historyCursor = null;
let historyAccount = null;
let isHistoryLoading = false;

/** @param {string} id Identifier of an existing page control. */
function getElement(id) {
  return document.getElementById(id);
}

/** Displays an operation's current outcome. */
function showStatus(message, isError = false) {
  getElement('status').textContent = message;
  getElement('status').className = isError ? 'error' : 'success';
}

/** Displays exact API values without interpreting markup. */
function showResult(value) {
  getElement('result').textContent = JSON.stringify(value, null, 2);
}

/** Updates controls while an original request remains pending. */
function renderPending() {
  const saved = requests.pending;
  getElement('pending').hidden = !saved;
  let description = '';
  if (saved) {
    description = saved.uncertain ?
      'Its outcome may be unknown; retry before another action.' :
      'It was not posted; retry it or start a new action.';
    description = `Saved ${saved.kind}. ${description}`;
  }
  getElement('pending-description').textContent = description;
  getElement('new-action').hidden = !saved || saved.uncertain;
  getElement('retry').disabled = isInFlight;
  getElement('transfer-submit').disabled =
    isInFlight || !!saved || requests.isBlocked;
  getElement('reversal-submit').disabled =
    isInFlight || !!saved || requests.isBlocked;
}

/** Sends an API request and preserves the server's reported outcome. */
async function callApi(path, options = {}) {
  const response = await fetch(path, options);
  let data;
  try {
    data = await response.json();
  } catch (error) {
    throw Object.assign(new Error('Response could not be read.'), {
      outcome: 'UNKNOWN',
    });
  }
  if (!response.ok) {
    throw Object.assign(new Error(data.message || 'Request failed.'), data);
  }
  return data;
}

/** Posts a new request or retries the retained original identity. */
async function submitFinancial(request) {
  if (isInFlight || requests.isBlocked) {
    return;
  }
  const hasUnresolvedOutcome = !request && !!requests.pending?.uncertain;
  try {
    if (request) {
      requests.retain({...request, key: crypto.randomUUID(), uncertain: true});
    }
    if (!requests.pending) {
      return;
    }
    requests.retain({...requests.pending, uncertain: true});
  } catch (error) {
    showStatus('Cannot retain a safe retry: ' + error.message, true);
    renderPending();
    return;
  }
  isInFlight = true;
  renderPending();
  showStatus('Waiting for the result…');
  const saved = requests.pending;
  try {
    const options = {
      method: 'POST',
      headers: {'Idempotency-Key': saved.key},
    };
    if (saved.body) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(saved.body);
    }
    const result = await callApi(saved.path, options);
    showResult(result);
    if (result.type === 'TRANSFER') {
      getElement('original').value = result.transactionId;
    }
    requests.clear();
    showStatus('Committed.');
    try {
      await refreshAccounts();
    } catch (error) {
      showStatus('Committed. Balances or rates could not be refreshed: ' +
        error.message, true);
    }
  } catch (error) {
    const isUncertain = error.outcome !== 'NOT_POSTED' ||
      (hasUnresolvedOutcome && !resolvedFailureCodes.has(error.code));
    if (requests.pending) {
      const pending = {
        ...requests.pending,
        uncertain: isUncertain,
      };
      try {
        requests.retain(pending);
      } catch (storageError) {
        // The original identity remains in memory if storage is unavailable.
      }
    }
    const suffix = requests.pending && requests.pending.uncertain ?
      ' Use Retry to resolve the saved request.' : '';
    showStatus(error.message + suffix, true);
    showResult({
      code: error.code || 'CONNECTION_ERROR',
      message: error.message,
      outcome: isUncertain ? 'UNKNOWN' : 'NOT_POSTED',
    });
  } finally {
    isInFlight = false;
    renderPending();
  }
}

/** Reloads user currency holdings and retains account selections. */
async function refreshAccounts() {
  const users = await callApi('/users');
  const accounts = users.flatMap((user) => user.accounts);
  getElement('accounts').replaceChildren();
  for (const user of users) {
    for (const account of user.accounts) {
      appendRow('accounts', [user.name, account.currency, account.id,
        account.balance, account.openingBalance]);
    }
  }
  for (const id of ['source', 'destination', 'history-account']) {
    const control = getElement(id);
    const selected = control.value;
    control.replaceChildren();
    for (const user of users) {
      const group = document.createElement('optgroup');
      group.label = user.name;
      for (const account of user.accounts) {
        const option = document.createElement('option');
        option.value = account.id;
        option.textContent = `${user.name} — ${account.currency}`;
        group.append(option);
      }
      control.append(group);
    }
    if (accounts.some((account) => account.id === selected)) {
      control.value = selected;
    } else if (id === 'destination' && accounts.length > 1) {
      control.selectedIndex = users.length > 1 ?
        users[0].accounts.length : 1;
    }
  }
  await refreshExchangeRates();
}

/** Displays current stored quotes exactly without browser arithmetic. */
async function refreshExchangeRates() {
  const configuration = await callApi('/configuration');
  getElement('exchange-rates').replaceChildren();
  for (const currency of configuration.currencies) {
    const forwardRate = currency.code === 'USD' ? '1' :
      configuration.rates[`USD-${currency.code}`] || 'Unavailable';
    const reverseRate = currency.code === 'USD' ? '1' :
      configuration.rates[`${currency.code}-USD`] || 'Unavailable';
    appendRow('exchange-rates', [currency.code, currency.name,
      currency.minorUnitDigits, forwardRate, reverseRate]);
  }
  getElement('configuration').textContent = JSON.stringify({
    roundingPolicy: configuration.roundingPolicy,
    rateSource: configuration.rateSource,
  }, null, 2);
}

/** Adds text cells to a results table. */
function appendRow(tableId, values) {
  const row = document.createElement('tr');
  for (const value of values) {
    const cell = document.createElement('td');
    cell.textContent = value;
    row.append(cell);
  }
  getElement(tableId).append(row);
}

/** Loads history using the server's fixed snapshot cursor. */
async function loadHistory(isNext = false) {
  if (isHistoryLoading) {
    return;
  }
  isHistoryLoading = true;
  getElement('history-load').disabled = true;
  getElement('history-next').disabled = true;
  try {
    if (!isNext) {
      historyAccount = getElement('history-account').value;
      historyCursor = null;
      getElement('history').replaceChildren();
    }
    const query = historyCursor ?
      `?cursor=${encodeURIComponent(historyCursor)}` : '';
    const accountPath = `/accounts/${encodeURIComponent(historyAccount)}`;
    const result = await callApi(`${accountPath}/transactions${query}`);
    for (const item of result.items) {
      appendRow('history', [
        item.postedAt,
        `${item.type} ${item.transactionId}`,
        `${item.sourceAccount} → ${item.destinationAccount}`,
        `${item.debitAmount} ${item.sourceCurrency}`,
        `${item.creditAmount} ${item.destinationCurrency}`,
      ]);
    }
    historyCursor = result.nextCursor;
    getElement('history-next').hidden = !historyCursor;
    showStatus(`History through posting ${result.postingBoundary}.`);
  } finally {
    isHistoryLoading = false;
    getElement('history-load').disabled = false;
    getElement('history-next').disabled = false;
  }
}

/** Converts an asynchronous button action into a displayed result or error. */
function handleAction(action) {
  return () => action().catch((error) => {
    showStatus(error.message, true);
    showResult({message: error.message, outcome: error.outcome});
  });
}

/** Returns the selected completed UTC month. */
function getSelectedMonth() {
  const month = getElement('month').value;
  if (!month) {
    throw new Error('Select a completed UTC month.');
  }
  return month;
}

/** Loads and displays one reconciliation report. */
async function showReport(path, options = {}) {
  const report = await callApi(path, options);
  showResult(report);
  showStatus(`Ledger check: ${report.status}`, report.status !== 'OK');
}

getElement('transfer-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (requests.pending) {
    return;
  }
  submitFinancial({
    kind: 'transfer',
    path: '/transactions',
    body: {
      sourceAccount: getElement('source').value,
      destinationAccount: getElement('destination').value,
      amount: getElement('amount').value.trim(),
    },
  });
});
getElement('reversal-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (requests.pending) {
    return;
  }
  const original = encodeURIComponent(getElement('original').value.trim());
  submitFinancial({
    kind: 'reversal',
    path: `/transactions/${original}/reversal`,
  });
});
getElement('retry').addEventListener('click', () => submitFinancial());
getElement('new-action').addEventListener('click', handleAction(async () => {
  if (requests.pending && !requests.pending.uncertain && !isInFlight) {
    requests.clear();
    renderPending();
    showStatus('Ready for a new action.');
  }
}));
getElement('refresh').addEventListener('click', handleAction(refreshAccounts));
getElement('history-load').addEventListener('click',
  handleAction(() => loadHistory()));
getElement('history-next').addEventListener('click',
  handleAction(() => loadHistory(true)));
getElement('integrity').addEventListener('click',
  handleAction(() => showReport('/reconciliation')));
getElement('month-close').addEventListener('click', handleAction(() =>
  showReport(`/month-closes/${getSelectedMonth()}`, {method: 'POST'})));
getElement('month-compare').addEventListener('click', handleAction(() =>
  showReport(`/month-closes/${getSelectedMonth()}/comparison`)));
getElement('close-list').addEventListener('click', handleAction(async () =>
  showResult(await callApi('/month-closes'))));

/** Restores the pending identity and loads initial balances and settings. */
async function initialize() {
  try {
    requests.load();
  } catch (error) {
    showStatus(error.message, true);
  }
  renderPending();
  const now = new Date();
  const previous = new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  getElement('month').value = previous.toISOString().slice(0, 7);
  getElement('month').max = getElement('month').value;
  await refreshAccounts();
}
initialize().catch((error) => showStatus(error.message, true));
