export function validateCredentials(
  username: string,
  password: string,
  registering: boolean,
): string {
  const pattern = registering ? /^[A-Za-z0-9_]{3,32}$/ : /^[A-Za-z0-9_]{1,32}$/;
  if (!pattern.test(username))
    return registering
      ? "用户名为3至32位字母、数字或下划线"
      : "请输入1至32位字母、数字或下划线的用户名";
  if (
    !password.trim() ||
    password.length > 64 ||
    (registering && password.length < 8)
  )
    return registering ? "密码长度为8至64个字符" : "请输入密码，最多64个字符";
  if (new TextEncoder().encode(password).length > 72)
    return "密码的UTF-8编码不能超过72字节";
  return "";
}
