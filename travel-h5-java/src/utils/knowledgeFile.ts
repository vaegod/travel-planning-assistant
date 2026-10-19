export function decodeKnowledgeFile(name: string, bytes: Uint8Array): string {
  if (!/\.(txt|md)$/i.test(name) || bytes.byteLength > 100000)
    throw Error("请选择100KB以内的TXT或Markdown文本");
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true })
      .decode(bytes)
      .replace(/\r\n?/g, "\n");
  } catch {
    throw Error("资料必须是UTF-8编码文本");
  }
  if (text.length > 12000) throw Error("资料正文不能超过12000字符");
  if (/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(text))
    throw Error("资料包含二进制控制字符");
  return text;
}
