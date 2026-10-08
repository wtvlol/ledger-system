import assert from 'node:assert/strict';
import {test} from 'node:test';

import {RequestStore} from '../../main/resources/static/request-store.js';

/** Creates isolated browser storage with controllable write failures. */
function createStorage() {
  return {
    value: null,
    hasFailed: false,
    getItem() {
      return this.value;
    },
    setItem(key, value) {
      if (this.hasFailed) {
        throw new Error('Storage unavailable');
      }
      this.value = value;
    },
    removeItem() {
      if (this.hasFailed) {
        throw new Error('Storage unavailable');
      }
      this.value = null;
    },
  };
}

/** Creates an exact amount request with a stable retry identity. */
function createRequest() {
  return {
    key: '3e9a107e-c3ca-4f30-91ce-71ba4a956056',
    kind: 'transfer',
    path: '/transactions',
    body: {
      sourceAccount: 'account-01',
      destinationAccount: 'account-02',
      amount: '92233720368547758.07',
    },
    uncertain: true,
  };
}

test('restart retains the original key and exact amount string', () => {
  const storage = createStorage();
  new RequestStore(storage).retain(createRequest());
  const restarted = new RequestStore(storage);
  restarted.load();
  assert.deepEqual(restarted.pending, createRequest());
  assert.equal(typeof restarted.pending.body.amount, 'string');
  assert.equal(restarted.pending.key, createRequest().key);
});

test('known failures become uncertain after a browser restart', () => {
  const storage = createStorage();
  new RequestStore(storage).retain({...createRequest(), uncertain: false});
  const restarted = new RequestStore(storage);
  restarted.load();
  assert.equal(restarted.pending.uncertain, true);
  assert.equal(restarted.pending.key, createRequest().key);
});

test('unreadable saved data blocks admission of a new request', () => {
  const storage = createStorage();
  storage.value = '{broken';
  const store = new RequestStore(storage);
  assert.throws(() => store.load(), /Saved request cannot be loaded/);
  assert.equal(store.isBlocked, true);
  assert.throws(() => store.retain(createRequest()), /must be restored/);
});

test('invalid stored paths and numeric money cannot be retried', () => {
  for (const request of [
    {...createRequest(), path: 'https://example.com'},
    {...createRequest(), body: {...createRequest().body, amount: 10}},
    {...createRequest(), key: ''},
  ]) {
    const storage = createStorage();
    storage.value = JSON.stringify(request);
    const store = new RequestStore(storage);
    assert.throws(() => store.load());
    assert.equal(store.isBlocked, true);
  }
});

test('storage failures retain the identity and prevent new admission', () => {
  const storage = createStorage();
  const store = new RequestStore(storage);
  const original = createRequest();
  store.retain(original);
  storage.hasFailed = true;
  assert.throws(() => store.retain({...original, uncertain: false}));
  assert.equal(store.pending, original);
  assert.throws(() => store.clear());
  assert.equal(store.pending, original);
  storage.hasFailed = false;
  store.clear();
  assert.equal(store.pending, null);
  assert.equal(storage.value, null);
});

test('reversals preserve their linked transaction and original key', () => {
  const storage = createStorage();
  const original = {
    key: createRequest().key,
    kind: 'reversal',
    path: '/transactions/original-id/reversal',
    uncertain: true,
  };
  new RequestStore(storage).retain(original);
  const restarted = new RequestStore(storage);
  restarted.load();
  assert.deepEqual(restarted.pending, original);
});
