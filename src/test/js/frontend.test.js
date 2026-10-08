import assert from 'node:assert/strict';
import {test} from 'node:test';
import {setImmediate as nextTurn} from 'node:timers/promises';

/** Models only the page controls used by the ledger event handlers. */
class PageControl {
  /** Creates a control with isolated listeners and displayed text. */
  constructor() {
    this.listeners = new Map();
    this.value = '';
    this.textContent = '';
    this.children = [];
  }

  /** Registers one application handler for the specified event. */
  addEventListener(event, listener) {
    this.listeners.set(event, listener);
  }

  /** Clears displayed rows or options. */
  replaceChildren() {
    this.children = [];
  }

  /** Appends a rendered text cell, row, or option. */
  append(child) {
    this.children.push(child);
  }

  /** Dispatches a form or button event through its actual handler. */
  dispatch(event) {
    return this.listeners.get(event)({preventDefault() {}});
  }
}

/** Waits for an asynchronous page update within a bounded deadline. */
async function waitForCondition(condition) {
  const deadline = Date.now() + 2000;
  while (!condition()) {
    assert.ok(Date.now() < deadline, 'Page update did not complete');
    await nextTurn();
  }
}

/** Runs an isolated page with controlled network and browser storage. */
async function withPage(name, respond, verify, respondToRead = null) {
  const originals = {
    document: Object.getOwnPropertyDescriptor(globalThis, 'document'),
    localStorage: Object.getOwnPropertyDescriptor(globalThis, 'localStorage'),
    fetch: Object.getOwnPropertyDescriptor(globalThis, 'fetch'),
  };
  const controls = new Map();
  const storage = new Map();
  const getControl = (id) => {
    if (!controls.has(id)) {
      controls.set(id, new PageControl());
    }
    return controls.get(id);
  };
  globalThis.document = {
    getElementById: getControl,
    createElement: () => new PageControl(),
  };
  globalThis.localStorage = {
    getItem: (key) => storage.get(key) || null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key),
  };
  const financialRequests = [];
  globalThis.fetch = async (path, options = {}) => {
    if (options.method === 'POST') {
      financialRequests.push({path, options});
      return respond(financialRequests.length);
    }
    if (respondToRead && path.includes('/transactions')) {
      return respondToRead(path);
    }
    const data = path === '/users' ? [
      {id: 'alice', name: 'Alice', accounts: [
        {id: 'usd-alice', userId: 'alice', currency: 'USD',
          balance: '1000.00', openingBalance: '1000.00'},
        {id: 'sgd-alice', userId: 'alice', currency: 'SGD',
          balance: '1000.00', openingBalance: '1000.00'},
      ]},
      {id: 'bob', name: 'Bob', accounts: [
        {id: 'usd-bob', userId: 'bob', currency: 'USD',
          balance: '500.00', openingBalance: '500.00'},
      ]},
    ] : {
      roundingPolicy: 'HALF_EVEN', rateSource: 'SQLITE',
      currencies: [
        {code: 'USD', name: 'United States dollar', minorUnitDigits: 2},
        {code: 'JPY', name: 'Japanese yen', minorUnitDigits: 0},
        {code: 'SGD', name: 'Singapore dollar', minorUnitDigits: 2},
      ],
      rates: {
        'USD-JPY': '150.000000000000', 'JPY-USD': '0.006666666667',
        'USD-SGD': '1.350000', 'SGD-USD': '0.740740740741',
      },
    };
    return {ok: true, json: async () => data};
  };
  try {
    await import(`../../main/resources/static/app.js?test=${name}`);
    await waitForCondition(() => getControl('configuration').textContent);
    getControl('source').value = 'usd-alice';
    getControl('destination').value = 'usd-bob';
    getControl('amount').value = '0.10';
    await verify(getControl, financialRequests, storage);
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) {
        delete globalThis[key];
      } else {
        Object.defineProperty(globalThis, key, value);
      }
    }
  }
}

test('uncertain transfer retries its original key and exact body', async () => {
  await withPage('unknown', async (attempt) => {
    if (attempt === 1) {
      return {ok: false, json: async () => ({
        message: 'Response deadline', code: 'RESPONSE_TIMEOUT',
        outcome: 'UNKNOWN',
      })};
    }
    return {ok: true, json: async () => ({
      type: 'TRANSFER', confirmation: 'COMMITTED', transactionId: 'original-id',
    })};
  }, async (getControl, requests, storage) => {
    getControl('transfer-form').dispatch('submit');
    await waitForCondition(() => getControl('status').className === 'error');
    assert.equal(getControl('transfer-submit').disabled, true);
    assert.equal(getControl('new-action').hidden, true);
    assert.equal(JSON.parse(requests[0].options.body).amount, '0.10');
    assert.equal(typeof JSON.parse(requests[0].options.body).amount, 'string');
    assert.equal(storage.size, 1);
    await getControl('retry').dispatch('click');
    assert.equal(requests.length, 2);
    assert.deepEqual(requests[0], requests[1]);
    assert.equal(storage.size, 0);
    assert.equal(getControl('transfer-submit').disabled, false);
    assert.equal(getControl('status').textContent, 'Committed.');
  });
});

test('user holdings group currencies and submit selected account identities',
  async () => {
    await withPage('user-holdings', async () => ({
      ok: true, json: async () => ({confirmation: 'COMMITTED'}),
    }), async (getControl, requests) => {
      const rows = getControl('accounts').children;
      assert.deepEqual(rows.map((row) =>
        row.children.map((cell) => cell.textContent)), [
        ['Alice', 'USD', 'usd-alice', '1000.00', '1000.00'],
        ['Alice', 'SGD', 'sgd-alice', '1000.00', '1000.00'],
        ['Bob', 'USD', 'usd-bob', '500.00', '500.00'],
      ]);
      const groups = getControl('source').children;
      assert.deepEqual(groups.map((group) => group.label), ['Alice', 'Bob']);
      assert.deepEqual(groups[0].children.map((option) => option.value),
        ['usd-alice', 'sgd-alice']);
      getControl('destination').value = 'sgd-alice';
      getControl('transfer-form').dispatch('submit');
      await waitForCondition(() => requests.length === 1 &&
        getControl('status').textContent === 'Committed.');
      assert.deepEqual(JSON.parse(requests[0].options.body), {
        sourceAccount: 'usd-alice', destinationAccount: 'sgd-alice',
        amount: '0.10',
      });
      await getControl('refresh').dispatch('click');
      assert.equal(getControl('destination').value, 'sgd-alice');
      assert.equal(getControl('accounts').children.length, 3);
    });
  });

test('confirmed failure permits a new action with a new key', async () => {
  await withPage('rejected', async () => ({
    ok: false,
    json: async () => ({message: 'Insufficient funds', outcome: 'NOT_POSTED'}),
  }), async (getControl, requests, storage) => {
    getControl('transfer-form').dispatch('submit');
    await waitForCondition(() => getControl('status').className === 'error');
    assert.equal(getControl('new-action').hidden, false);
    await getControl('new-action').dispatch('click');
    assert.equal(storage.size, 0);
    getControl('transfer-form').dispatch('submit');
    await waitForCondition(() => requests.length === 2 &&
      getControl('status').className === 'error');
    assert.notEqual(requests[0].options.headers['Idempotency-Key'],
      requests[1].options.headers['Idempotency-Key']);
  });
});

for (const kind of ['transfer', 'reversal']) {
  for (const code of [
    'QUEUE_UNAVAILABLE', 'TOO_MANY_WAITERS', 'DATABASE_UNAVAILABLE',
    'SHUTDOWN', 'INVALID_REQUEST', 'UNRECOGNIZED_FAILURE',
  ]) {
    test(`${kind} retains uncertainty after ${code} and reload`, async () => {
      const name = `${kind}-${code}`;
      await withPage(name, async (attempt) => {
        if (attempt === 1) {
          if (kind === 'reversal') {
            throw new Error('Committed response was lost');
          }
          return {ok: false, json: async () => ({
            message: 'Response deadline', code: 'RESPONSE_TIMEOUT',
            outcome: 'UNKNOWN',
          })};
        }
        if (attempt < 4) {
          return {ok: false, json: async () => ({
            message: 'Retry did not execute', code, outcome: 'NOT_POSTED',
          })};
        }
        return {ok: true, json: async () => ({
          type: kind.toUpperCase(), confirmation: 'COMMITTED',
          transactionId: 'original-result',
        })};
      }, async (getControl, requests, storage) => {
        getControl('original').value = 'original-transfer';
        getControl(`${kind}-form`).dispatch('submit');
        await waitForCondition(() =>
          getControl('status').className === 'error');
        await getControl('retry').dispatch('click');
        assert.equal(getControl('new-action').hidden, true);
        assert.equal(getControl('transfer-submit').disabled, true);
        assert.equal(getControl('reversal-submit').disabled, true);
        assert.equal(JSON.parse([...storage.values()][0]).uncertain, true);
        await getControl('new-action').dispatch('click');
        assert.equal(storage.size, 1);
        assert.equal(
          getControl('result').textContent.includes('UNKNOWN'), true);

        getControl('configuration').textContent = '';
        await import(`../../main/resources/static/app.js?test=reload-${name}`);
        await waitForCondition(() => getControl('configuration').textContent);
        await getControl('retry').dispatch('click');
        assert.equal(getControl('new-action').hidden, true);
        assert.equal(JSON.parse([...storage.values()][0]).uncertain, true);
        await getControl('retry').dispatch('click');
        assert.equal(requests.length, 4);
        for (const request of requests.slice(1)) {
          assert.deepEqual(request, requests[0]);
        }
        assert.equal(storage.size, 0);
        assert.equal(getControl('transfer-submit').disabled, false);
        assert.equal(getControl('reversal-submit').disabled, false);
        assert.equal(getControl('status').textContent, 'Committed.');
      });
    });
  }
}

test('worker rejection resolves an uncertain failed transfer', async () => {
  await withPage('unknown-then-business-rejection', async (attempt) => ({
    ok: false,
    json: async () => attempt === 1 ? {
      message: 'Response deadline', code: 'RESPONSE_TIMEOUT',
      outcome: 'UNKNOWN',
    } : {
      message: 'Insufficient funds', code: 'INSUFFICIENT_FUNDS',
      outcome: 'NOT_POSTED',
    },
  }), async (getControl, requests, storage) => {
    getControl('transfer-form').dispatch('submit');
    await waitForCondition(() => getControl('status').className === 'error');
    await getControl('retry').dispatch('click');
    assert.equal(getControl('new-action').hidden, false);
    assert.equal(JSON.parse([...storage.values()][0]).uncertain, false);
    assert.deepEqual(requests[1], requests[0]);
    await getControl('new-action').dispatch('click');
    assert.equal(storage.size, 0);
    assert.equal(getControl('transfer-submit').disabled, false);
  });
});

for (const code of ['QUEUE_UNAVAILABLE', 'TOO_MANY_WAITERS']) {
  test(`first-attempt ${code} permits a deliberate new action`, async () => {
    await withPage(`first-${code}`, async () => ({
      ok: false,
      json: async () => ({
        message: 'Not admitted', code, outcome: 'NOT_POSTED',
      }),
    }), async (getControl, requests, storage) => {
      getControl('transfer-form').dispatch('submit');
      await waitForCondition(() =>
        getControl('status').className === 'error');
      assert.equal(getControl('new-action').hidden, false);
      assert.equal(JSON.parse([...storage.values()][0]).uncertain, false);
      await getControl('new-action').dispatch('click');
      assert.equal(storage.size, 0);
      getControl('transfer-form').dispatch('submit');
      await waitForCondition(() => requests.length === 2 &&
        getControl('status').className === 'error');
      assert.notEqual(requests[0].options.headers['Idempotency-Key'],
        requests[1].options.headers['Idempotency-Key']);
    });
  });
}


test('repeated history clicks admit only one page read at a time', async () => {
  let finishRead;
  let readCount = 0;
  await withPage('history', async () => {}, async (getControl) => {
    getControl('history-account').value = 'usd-alice';
    const first = getControl('history-load').dispatch('click');
    const duplicate = getControl('history-load').dispatch('click');
    await waitForCondition(() => readCount === 1);
    assert.equal(getControl('history-load').disabled, true);
    finishRead({ok: true, json: async () => ({
      postingBoundary: '1', nextCursor: null, items: [{
        postedAt: '2026-10-07T00:00:00Z', type: 'TRANSFER', transactionId: 'id',
        sourceAccount: 'usd-alice', destinationAccount: 'usd-bob',
        debitAmount: '0.10', creditAmount: '0.10',
        sourceCurrency: 'USD', destinationCurrency: 'USD',
      }],
    })});
    await Promise.all([first, duplicate]);
    assert.equal(getControl('history').children.length, 1);
    assert.equal(getControl('history-load').disabled, false);
  }, async () => {
    readCount += 1;
    return new Promise((resolve) => {
      finishRead = resolve;
    });
  });
});


test('currency table preserves stored quotes and minor units', async () => {
  await withPage('rates', async () => {}, async (getControl) => {
    const rows = getControl('exchange-rates').children;
    assert.equal(rows.length, 3);
    const values = (row) => row.children.map((cell) => cell.textContent);
    assert.deepEqual(values(rows[1]), [
      'JPY', 'Japanese yen', 0, '150.000000000000', '0.006666666667',
    ]);
    assert.deepEqual(values(rows[2]), [
      'SGD', 'Singapore dollar', 2, '1.350000', '0.740740740741',
    ]);
    assert.equal(JSON.parse(getControl('configuration').textContent)
      .rateSource, 'SQLITE');
    await getControl('refresh').dispatch('click');
    assert.equal(getControl('exchange-rates').children.length, 3);
  });
});
