import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveRoute } from '../src/lib/router.ts';

const at = (pathname: string) => resolveRoute({ pathname, search: '' });
const token = '01a209fe-4641-7000-a156-49a22f818d38';

test('a clean invite path is a join', () => {
  assert.deepEqual(at(`/j/${token}`), { kind: 'join', token });
});

test('an invite path with the share message folded in is still a join', () => {
  assert.deepEqual(
    at(`/j/${token}%0ASee%20what%20you%20owe%20and%20settle%20up%20for%20New%20York%20on%20the%20web.`),
    { kind: 'join', token },
  );
});

test('a bill path with trailing junk is still a bill', () => {
  assert.deepEqual(at('/b/AbCdEfGhIjKlMnOpQr%20extra'), { kind: 'bill', token: 'AbCdEfGhIjKlMnOpQr' });
});

test('a token run into other letters is not truncated into a match', () => {
  assert.deepEqual(at(`/j/${token}${'x'.repeat(40)}`), { kind: 'waitlist' });
});
