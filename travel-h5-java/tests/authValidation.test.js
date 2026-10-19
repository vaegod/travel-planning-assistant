import test from "node:test";
import assert from "node:assert/strict";
import { validateCredentials } from "../src/utils/authValidation.ts";
import { validateAccountChange } from "../src/utils/accountSettings.ts";
test("login accepts existing short credentials while registration retains its limits", () => {
  assert.equal(validateCredentials("ab", "secret", false), "");
  assert.match(validateCredentials("ab", "Long-pass-2026", true), /3至32/);
  assert.match(validateCredentials("valid_user", "secret", true), /8至64/);
  assert.match(validateCredentials("valid_user", "", false), /请输入密码/);
});
test("existing short current passwords can be verified but new passwords retain their limits", () => {
  const fields = {
    username: "new_user",
    currentPassword: "secret",
    newPassword: "New-password-26",
    confirmPassword: "New-password-26",
  };
  assert.equal(validateAccountChange("password", fields, "ab"), "");
  assert.match(
    validateAccountChange(
      "password",
      { ...fields, newPassword: "short", confirmPassword: "short" },
      "ab",
    ),
    /8至64/,
  );
});
