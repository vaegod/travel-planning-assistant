import test from "node:test";
import assert from "node:assert/strict";
import { validateAccountChange } from "../src/utils/accountSettings.ts";
const fields = (overrides = {}) => ({
  username: "new_name",
  currentPassword: "Current-pass-26",
  newPassword: "New-pass-2026",
  confirmPassword: "New-pass-2026",
  ...overrides,
});
test("credential edits require a current password without trimming passwords", () => {
  assert.match(
    validateAccountChange(
      "username",
      fields({ currentPassword: "" }),
      "old_name",
    ),
    /当前密码/,
  );
  assert.equal(validateAccountChange("password", fields(), "old_name"), "");
});
test("username edits reject invalid and case-only unchanged names", () => {
  assert.match(
    validateAccountChange(
      "username",
      fields({ username: "新用户" }),
      "old_name",
    ),
    /用户名为/,
  );
  assert.match(
    validateAccountChange(
      "username",
      fields({ username: "OLD_NAME" }),
      "old_name",
    ),
    /相同/,
  );
  assert.equal(validateAccountChange("username", fields(), "old_name"), "");
});
test("password edits require matching confirmation and a different password", () => {
  assert.match(
    validateAccountChange(
      "password",
      fields({ confirmPassword: "Different-pass" }),
      "old_name",
    ),
    /不一致/,
  );
  assert.match(
    validateAccountChange(
      "password",
      fields({
        newPassword: "Current-pass-26",
        confirmPassword: "Current-pass-26",
      }),
      "old_name",
    ),
    /不能与当前/,
  );
});
test("UTF8 byte limits prevent BCrypt truncation and blank new passwords are rejected", () => {
  assert.match(
    validateAccountChange(
      "password",
      fields({
        newPassword: "密".repeat(25),
        confirmPassword: "密".repeat(25),
      }),
      "old_name",
    ),
    /72字节/,
  );
  assert.match(
    validateAccountChange(
      "password",
      fields({ newPassword: " ".repeat(8) }),
      "old_name",
    ),
    /8至64/,
  );
});
