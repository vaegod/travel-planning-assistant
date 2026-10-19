// SSE frames are independent of network chunks and UTF-8 byte boundaries.
export function createSseParser(onData) {
  let buffer = ''
  return {
    push(text) {
      buffer += text
      if (buffer.length > 1048576) throw new Error('流式消息过大')
      let separator
      while ((separator = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const frame = buffer.slice(0, separator.index)
        buffer = buffer.slice(separator.index + separator[0].length)
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).replace(/^ /, ''))
          .join('\n')
        if (data) onData(JSON.parse(data))
      }
    },
    finish() {
      if (buffer.trim()) throw new Error('流式消息不完整')
    },
  }
}
