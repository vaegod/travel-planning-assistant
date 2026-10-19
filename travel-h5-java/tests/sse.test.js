import test from 'node:test'
import assert from 'node:assert/strict'
import { createSseParser } from '../src/utils/sse.js'

test('handles UTF-8 bytes split at every byte, CRLF boundaries, and merged events', () => {
  const events = []
  const parser = createSseParser(event => events.push(event))
  const decoder = new TextDecoder()
  const bytes = new TextEncoder().encode(': keepalive\r\n\r\ndata: {"type":"chunk","content":"你好"}\r\n\r\ndata: {"done":true}\n\n')
  for (const byte of bytes) parser.push(decoder.decode(new Uint8Array([byte]), { stream: true }))
  parser.push(decoder.decode())
  assert.deepEqual(events, [{ type: 'chunk', content: '你好' }, { done: true }])
})

test('does not discard the first event or an error event in the same chunk', () => {
  const events = []
  createSseParser(event => events.push(event)).push('data: {"type":"chunk","content":"首段"}\n\ndata: {"error":"失败"}\n\n')
  assert.deepEqual(events, [{ type: 'chunk', content: '首段' }, { error: '失败' }])
})

test('reports malformed JSON instead of silently losing an event', () => {
  assert.throws(() => createSseParser(() => {}).push('data: invalid\n\n'), SyntaxError)
})
