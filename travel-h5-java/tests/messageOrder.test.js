import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeMessages} from '../src/utils/messageState.ts';

const local = (requestId, role, status = 'error') => ({
  id: 'local-' + requestId + '-' + role, requestId, role,
  seq: Number.MAX_SAFE_INTEGER - (role === 'user' ? 1 : 0),
  content: requestId + '-' + role, status, statusText: '', timestamp: 'now',
});
const stored = (requestId, role, seq) => ({
  id: 'stored-' + requestId + '-' + role, requestId, role, seq,
  content: requestId + '-' + (role === 'assistant' ? 'ai' : 'user'),
  status: 'completed', createdAt: 'now',
});
const keys = rows => rows.map(row => row.requestId + ':' + row.role);

test('an unpersisted failed turn stays before the later successful turn after reload', () => {
  const previous = [local('failed', 'user'), local('failed', 'ai'), local('retry', 'user'), local('retry', 'ai')];
  const saved = [stored('retry', 'user', 20), stored('retry', 'assistant', 21)];
  const merged = mergeMessages(previous, saved);
  assert.deepEqual(keys(merged), ['failed:user', 'failed:ai', 'retry:user', 'retry:ai']);
  assert.equal(merged[1].question, 'failed-user');
  assert.equal(merged[3].question, 'retry-user');
});

test('multiple local failures retain request pairs rather than grouping all user rows first', () => {
  const rows = [local('first', 'user'), local('first', 'ai'), local('second', 'user'), local('second', 'ai')];
  assert.deepEqual(keys(mergeMessages(rows, [])), ['first:user', 'first:ai', 'second:user', 'second:ai']);
});

test('older history and repeated refresh preserve local failure placement and server sequence order', () => {
  const previous = [local('failed', 'user'), local('failed', 'ai'), local('next', 'user'), local('next', 'ai')];
  const next = [stored('next', 'user', 20), stored('next', 'assistant', 21)];
  const merged = mergeMessages(previous, next);
  const older = [stored('older', 'assistant', 3), stored('older', 'user', 2), ...next];
  const again = mergeMessages(merged, older);
  assert.deepEqual(keys(again), ['older:user', 'older:ai', 'failed:user', 'failed:ai', 'next:user', 'next:ai']);
  assert.deepEqual(keys(mergeMessages(again, next)), keys(again));
});

test('late persistence replaces local placeholders and a following live reply keeps its identity', () => {
  const failedUser = local('failed', 'user'), failedAI = local('failed', 'ai');
  const activeUser = local('live', 'user', 'completed'), activeAI = local('live', 'ai', 'streaming');
  const previous = [failedUser, failedAI, activeUser, activeAI];
  const merged = mergeMessages(previous, [stored('live', 'user', 20)], 'live');
  assert.deepEqual(keys(merged), ['failed:user', 'failed:ai', 'live:user', 'live:ai']);
  assert.equal(merged[3], activeAI);
  const final = mergeMessages(merged, [stored('failed', 'user', 10), stored('failed', 'assistant', 11)], 'live');
  assert.deepEqual(keys(final), keys(merged));
  assert.equal(final[1].id, 'stored-failed-assistant');
  assert.equal(final[3], activeAI);
});
