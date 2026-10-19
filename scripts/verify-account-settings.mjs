// Local-only opt-in account edit smoke. Never changes the existing demo account.
import assert from "node:assert/strict";
import { writeFile, mkdir } from "node:fs/promises";
import { localClient, testAccount } from "./local-api.mjs";
const address = process.env.ACCOUNT_VERIFY_URL || "http://127.0.0.1:3200";
const client = localClient(address),
  second = localClient(address),
  account = testAccount();
const kind = new URL(address).protocol === "https:" ? "https" : "native";
const report = { at: new Date().toISOString(), origin: address, checks: {} };
await mkdir(".runtime", { recursive: true });
const save = () =>
  writeFile(
    `.runtime/account-settings-${kind}.json`,
    JSON.stringify(report, null, 2) + "\n",
  );
try {
  await client.register(account);
  await second.login(account);
  const actor = await client.api("/api/auth/me");
  const slot = {
    spot: "凭据修改验收（虚构）",
    duration: "1小时",
    ticket: "0元",
    transportation: "步行",
    description: "本机测试资料",
  };
  const trip = await client.api("/api/trips", "POST", {
    title: "账号设置保留验证",
    plan: {
      success: true,
      city: "杭州",
      days: 1,
      totalBudget: 500,
      dailyItinerary: [
        { day: 1, morning: slot, afternoon: slot, evening: slot },
      ],
    },
  });
  const conversation = await client.api("/api/conversations", "POST", {
    tripId: trip.trip.id,
    title: "修改凭据保留测试",
  });
  const beforeConversation = await client.api(
    `/api/conversations/${conversation.id}`,
  );
  const checkData = async () => {
    const current = await client.api("/api/auth/me");
    assert.equal(current.id, actor.id);
    assert.deepEqual(await client.api(`/api/trips/${trip.trip.id}`), trip);
    assert.deepEqual(
      await client.api(`/api/conversations/${conversation.id}`),
      beforeConversation,
    );
  };
  assert.equal(
    (
      await client.reply("/api/auth/username", "PATCH", {
        username: "bad name",
        currentPassword: account.password,
      })
    ).status,
    400,
  );
  assert.equal(
    (
      await client.reply("/api/auth/password", "PATCH", {
        currentPassword: "Wrong-password",
        newPassword: "New-password-2026",
        confirmPassword: "New-password-2026",
      })
    ).body.errorCode,
    "CURRENT_PASSWORD_INVALID",
  );
  report.checks.rejectedInvalidEdits = true;
  const previous = { ...account };
  account.username =
    "changed_" + crypto.randomUUID().replaceAll("-", "").slice(0, 18);
  await client.api("/api/auth/username", "PATCH", {
    username: account.username,
    currentPassword: account.password,
  });
  assert.equal((await client.reply("/api/auth/me")).status, 401);
  assert.equal((await second.reply("/api/auth/me")).status, 401);
  const invalid = localClient(address);
  await invalid.login(previous).then(
    () => {
      throw Error("Old username accepted");
    },
    (e) => assert.match(e.message, /401/),
  );
  await client.login(account);
  await checkData();
  report.checks.renamePreservesIdentityAndData = true;
  report.checks.renameRevokesSessions = true;
  await second.login(account);
  const oldPassword = account.password;
  account.password = "Changed-" + crypto.randomUUID();
  await client.api("/api/auth/password", "PATCH", {
    currentPassword: oldPassword,
    newPassword: account.password,
    confirmPassword: account.password,
  });
  assert.equal((await client.reply("/api/auth/me")).status, 401);
  assert.equal((await second.reply("/api/auth/me")).status, 401);
  await invalid.login({ ...account, password: oldPassword }).then(
    () => {
      throw Error("Old password accepted");
    },
    (e) => assert.match(e.message, /401/),
  );
  await client.login(account);
  await checkData();
  report.checks.passwordPreservesIdentityAndData = true;
  report.checks.passwordRevokesSessions = true;
  report.userId = actor.id;
  report.tripId = trip.trip.id;
  report.conversationId = conversation.id;
  report.passed = true;
  await writeFile(
    `.runtime/account-settings-${kind}-account.json`,
    JSON.stringify(account) + "\n",
  );
  await save();
  console.log(JSON.stringify(report));
} catch (error) {
  report.passed = false;
  report.error = error.message;
  await save();
  console.error(error.message);
  process.exitCode = 1;
}
