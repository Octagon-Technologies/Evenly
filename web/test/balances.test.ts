// The group page's balances, against the cases `BilateralBalanceTest` pins on the Kotlin side.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildBilateralBalances, myDebts } from '../src/lib/money/balances.ts';

const s = (payerUserId: string, participantUserId: string, remainingSubunits: number, currency = 'USD') => ({
  payerUserId,
  participantUserId,
  currency,
  remainingSubunits,
});

test('a pair nets within one currency', () => {
  const debts = buildBilateralBalances([s('joe', 'alice', 1000), s('alice', 'joe', 300)]);
  assert.deepEqual(debts, [{ debtorUserId: 'alice', creditorUserId: 'joe', currency: 'USD', amountSubunits: 700 }]);
});

test('exact cancellation produces nothing', () => {
  assert.deepEqual(buildBilateralBalances([s('joe', 'alice', 500), s('alice', 'joe', 500)]), []);
});

test('a triangle is never simplified', () => {
  const debts = buildBilateralBalances([s('bob', 'alice', 100), s('joe', 'bob', 100)]);
  assert.equal(debts.length, 2);
});

test('currencies never net against each other', () => {
  const debts = buildBilateralBalances([s('joe', 'alice', 100, 'USD'), s('alice', 'joe', 100, 'EUR')]);
  assert.equal(debts.length, 2);
});

test('settled and credit shares are ignored', () => {
  assert.deepEqual(buildBilateralBalances([s('joe', 'alice', 0), s('joe', 'bob', -50)]), []);
});

test('myDebts splits by side', () => {
  const debts = buildBilateralBalances([s('joe', 'alice', 100), s('alice', 'bob', 40)]);
  const mine = myDebts(debts, 'alice');
  assert.equal(mine.iOwe[0].creditorUserId, 'joe');
  assert.equal(mine.owedToMe[0].debtorUserId, 'bob');
});
