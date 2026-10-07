/** @fileoverview Retains request identities across uncertain outcomes. */

const STORAGE_KEY = 'ledger.pending-financial-request';

/** Stores one financial request until its committed outcome is known. */
export class RequestStore {
  /** @param {!Storage} storage Browser storage or a compatible test adapter. */
  constructor(storage) {
    this.storage = storage;
    this.pending = null;
    this.isBlocked = false;
  }

  /** Loads the original identity; unreadable storage blocks new operations. */
  load() {
    try {
      const text = this.storage.getItem(STORAGE_KEY);
      if (text) {
        const pending = JSON.parse(text);
        this.validate(pending);
        this.pending = {...pending, uncertain: true};
      }
    } catch (error) {
      this.isBlocked = true;
      throw new Error('Saved request cannot be loaded. Restore storage ' +
        'before starting another financial action.');
    }
  }

  /** @param {!Object} pending Original path, input strings, and request key. */
  retain(pending) {
    if (this.isBlocked) {
      throw new Error('Browser storage must be restored before transferring.');
    }
    this.validate(pending);
    // Persist before admitting a request to the network.
    this.storage.setItem(STORAGE_KEY, JSON.stringify(pending));
    this.pending = pending;
  }

  /** Removes the saved key only after commit or a deliberate new action. */
  clear() {
    // Keep the key in memory when storage fails, so retry is still possible.
    this.storage.removeItem(STORAGE_KEY);
    this.pending = null;
  }

  /** @param {!Object} pending Parsed input that must remain safe to retry. */
  validate(pending) {
    if (!pending || typeof pending.key !== 'string' ||
        !/^[0-9a-f-]{36}$/i.test(pending.key) ||
        typeof pending.uncertain !== 'boolean') {
      throw new Error('Invalid saved request identity.');
    }
    if (pending.kind === 'transfer' && pending.path === '/transactions' &&
        pending.body && typeof pending.body.sourceAccount === 'string' &&
        typeof pending.body.destinationAccount === 'string' &&
        typeof pending.body.amount === 'string') {
      return;
    }
    if (pending.kind === 'reversal' &&
        /^\/transactions\/[a-zA-Z0-9_-]+\/reversal$/.test(pending.path) &&
        pending.body === undefined) {
      return;
    }
    throw new Error('Invalid saved request details.');
  }
}
