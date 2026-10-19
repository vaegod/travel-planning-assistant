import test from 'node:test'
import assert from 'node:assert/strict'
import { createChatRequest, readChatResponse } from '../src/utils/chatStream.ts'
import { validateTrip } from '../src/utils/validation.js'

const id = '00000000-0000-4000-8000-000000000001'
const frame = (type, extra = {}, requestId = id) => `data: ${JSON.stringify({ type, requestId, ...extra })}\n\n`
const streamResponse = text => new Response(text, { headers: { 'Content-Type': 'text/event-stream' } })

test('complete stream calls chunks in order and releases the reader', async () => {
  const response = streamResponse(frame('start') + frame('chunk', { content: '首段' }) + frame('chunk', { content: '末段' }) + frame('done'))
  const chunks = []
  await readChatResponse(response, id, chunk => chunks.push(chunk))
  assert.deepEqual(chunks, ['首段', '末段'])
  assert.equal(response.body.locked, false)
})

test('EOF without done is an error, even after valid content', async () => {
  const chunks = []
  await assert.rejects(readChatResponse(streamResponse(frame('start') + frame('chunk', { content: '半句话' })), id, chunk => chunks.push(chunk)), { code: 'STREAM_INTERRUPTED' })
  assert.deepEqual(chunks, ['半句话'])
})

test('incomplete final frame and malformed JSON are errors', async () => {
  for (const text of [frame('start') + 'data: {"type":', 'data: invalid\n\n']) {
    await assert.rejects(readChatResponse(streamResponse(text), id, () => {}), { code: 'STREAM_PROTOCOL_ERROR' })
  }
})

test('provider error preserves received chunks and exposes a structured code', async () => {
  const chunks = []
  await assert.rejects(readChatResponse(streamResponse(frame('start') + frame('chunk', { content: '保留' }) + frame('error', { code: 'MODEL_TIMEOUT', message: '模型响应超时' })), id, chunk => chunks.push(chunk)), { code: 'MODEL_TIMEOUT', message: '模型响应超时' })
  assert.deepEqual(chunks, ['保留'])
})

test('HTTP validation errors and non-SSE responses are not treated as completed answers', async () => {
  await assert.rejects(readChatResponse(new Response(JSON.stringify({ errorCode: 'INVALID_INPUT', message: '消息过长' }), { status: 400 }), id, () => {}), { code: 'INVALID_INPUT', message: '消息过长' })
  await assert.rejects(readChatResponse(new Response('<html>error</html>'), id, () => {}), { code: 'STREAM_PROTOCOL_ERROR' })
})

test('wrong request IDs, unknown events and chunks before start are rejected', async () => {
  for (const text of [frame('start', {}, 'wrong-id'), frame('unknown'), frame('chunk', { content: 'x' })]) {
    await assert.rejects(readChatResponse(streamResponse(text), id, () => {}), { code: 'STREAM_PROTOCOL_ERROR' })
  }
})

test('cancel before first token aborts POST and sends an independent DELETE', async () => {
  const calls = []
  const fetchImpl = (url, options) => {
    calls.push({ url, options })
    if (options.method === 'DELETE') return Promise.resolve(new Response(JSON.stringify({success:true,data:{cancelled:true}})))
    return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true }))
  }
  const request = createChatRequest({ baseUrl: '/api/travel', conversationId: 'conversation-test', message: '测试', requestId: id, onChunk: () => {}, fetchImpl })
  assert.equal(await request.cancel(), true)
  assert.deepEqual(await request.finished, { status: 'cancelled' })
  assert.equal(calls[0].options.signal.aborted, true)
  assert.equal(calls[1].options.method, 'DELETE')
  assert.equal(calls[1].options.keepalive, true)
  assert.notEqual(calls[1].options.signal, calls[0].options.signal)
  assert.equal(calls[1].options.signal.aborted, false)
  assert.equal(calls[1].url, `/api/travel/chat/${id}`)
  await request.cancel()
  assert.equal(calls.length, 2)
})

test('stopped request cannot deliver late content; a new request still completes', async () => {
  let controller
  const response = new Response(new ReadableStream({ start(value) { controller = value } }), { headers: { 'Content-Type': 'text/event-stream' } })
  const oldChunks = []
  const old = createChatRequest({ baseUrl: '/api/travel', conversationId: 'conversation-test', message: 'old', requestId: id, onChunk: chunk => oldChunks.push(chunk),
    fetchImpl: async (url, options) => options.method === 'DELETE' ? new Response(JSON.stringify({success:true,data:{cancelled:true}})) : response })
  await Promise.resolve()
  await old.cancel()
  controller.enqueue(new TextEncoder().encode(frame('start') + frame('chunk', { content: '旧结果' }) + frame('done')))
  assert.deepEqual(await old.finished, { status: 'cancelled' })
  assert.deepEqual(oldChunks, [])
  const fresh = []
  const next = createChatRequest({ baseUrl: '/api/travel', conversationId: 'conversation-test', message: 'new', requestId: id, onChunk: chunk => fresh.push(chunk),
    fetchImpl: async () => streamResponse(frame('start') + frame('chunk', { content: '新结果' }) + frame('done')) })
  assert.deepEqual(await next.finished, { status: 'done' })
  assert.deepEqual(fresh, ['新结果'])
})

test('client timeout aborts and notifies server, but is reported as timeout', async () => {
  let deletes = 0
  const request = createChatRequest({ baseUrl: '/api/travel', conversationId: 'conversation-test', message: '测试', requestId: id, onChunk: () => {}, timeoutMs: 15,
    fetchImpl: (url, options) => {
      if (options.method === 'DELETE') { deletes++; return Promise.resolve(new Response(JSON.stringify({success:true,data:{cancelled:true}}))) }
      return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))
    } })
  await assert.rejects(request.finished, { code: 'CLIENT_TIMEOUT' })
  assert.equal(deletes, 1)
})

test('failed cancellation confirmation is observable without an unhandled rejection', async () => {
  const request = createChatRequest({ baseUrl: '/api/travel', conversationId: 'conversation-test', message: '测试', requestId: id, onChunk: () => {},
    fetchImpl: (url, options) => {
      if (options.method === 'DELETE') return Promise.reject(new TypeError('offline'))
      return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))
    } })
  assert.equal(await request.cancel(), false)
  assert.deepEqual(await request.finished, { status: 'cancelled' })
})

test('a cancellation deadline can fail independently after the original POST is aborted', async t => {
  const deadline=new AbortController();
  t.mock.method(AbortSignal,'timeout',()=>deadline.signal);
  let postSignal;
  const request=createChatRequest({baseUrl:'/api/travel',conversationId:'test',message:'question',requestId:id,onChunk:()=>{},
    fetchImpl:(url,options)=>new Promise((resolve,reject)=>{
      if(options.method==='DELETE'){
        assert.equal(options.signal,deadline.signal);
        options.signal.addEventListener('abort',()=>reject(new DOMException('Timeout','TimeoutError')),{once:true});
      }else{
        postSignal=options.signal;
        options.signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')),{once:true});
      }
    })});
  const confirmation=request.cancel();
  assert.equal(postSignal.aborted,true);assert.equal(deadline.signal.aborted,false);
  deadline.abort();
  assert.equal(await confirmation,false);
  assert.deepEqual(await request.finished,{status:'cancelled'});
});

test('HTTP success with cancelled=false is not reported as confirmed cancellation', async () => {
  const request=createChatRequest({baseUrl:'/api/travel',conversationId:'test',message:'question',requestId:id,onChunk:()=>{},
    fetchImpl:(url,options)=>options.method==='DELETE'
      ? Promise.resolve(new Response(JSON.stringify({success:true,data:{cancelled:false}})))
      : new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError'))))});
  assert.equal(await request.cancel(),false);
  assert.deepEqual(await request.finished,{status:'cancelled'});
});

test('trip form rejects blank cities, fractional days, infinity and out-of-range numbers', () => {
  const valid = { city: '杭州', days: '1', budget: '1000' }
  assert.equal(validateTrip(valid), '')
  for (const patch of [{ city: ' ' }, { city: '杭'.repeat(51) }, { days: '1.5' }, { days: '31' }, { budget: 'Infinity' }, { budget: 'abc' }, { budget: 99 }, { budget: 1000001 }]) {
    assert.notEqual(validateTrip({ ...valid, ...patch }), '')
  }
})
