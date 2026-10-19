export type AccountChange = "username" | "password";
export interface AccountFields {
  username: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}
export function validateAccountChange(
  kind: AccountChange,
  fields: AccountFields,
  currentUsername: string,
): string {
  if (!fields.currentPassword.trim() || fields.currentPassword.length > 64)
    return "请输入当前密码，最多64个字符";
  if (new TextEncoder().encode(fields.currentPassword).length > 72)
    return "当前密码的UTF-8编码不能超过72字节";
  if (kind === "username") {
    if (!/^[A-Za-z0-9_]{3,32}$/.test(fields.username))
      return "用户名为3至32位字母、数字或下划线";
    if (fields.username.toLowerCase() === currentUsername.toLowerCase())
      return "新用户名与当前用户名相同";
  } else {
    if (
      !fields.newPassword.trim() ||
      fields.newPassword.length < 8 ||
      fields.newPassword.length > 64
    )
      return "新密码长度为8至64个字符";
    if (new TextEncoder().encode(fields.newPassword).length > 72)
      return "新密码的UTF-8编码不能超过72字节";
    if (fields.newPassword !== fields.confirmPassword)
      return "两次输入的新密码不一致";
    if (fields.newPassword === fields.currentPassword)
      return "新密码不能与当前密码相同";
  }
  return "";
}
