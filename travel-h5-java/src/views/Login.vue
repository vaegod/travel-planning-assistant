<template>
  <main class="account-page login-page">
    <div class="login-sheet">
      <div class="account-eyebrow">旅游规划智能助手 · 旅程档案</div>
      <h1 class="account-heading">下一段旅程，<br />从这里继续。</h1>
      <p class="account-description">
        保存行程与聊天记录，下次回来，接着规划。
      </p>
      <div class="login-tabs" role="tablist" aria-label="账号操作">
        <button
          type="button"
          :class="{ selected: !creating }"
          @click="
            creating = false;
            error = '';
          "
        >
          登录
        </button>
        <button
          type="button"
          :class="{ selected: creating }"
          @click="
            creating = true;
            error = '';
          "
        >
          注册账号
        </button>
      </div>
      <form @submit.prevent="submit">
        <van-cell-group inset>
          <van-field
            v-model="username"
            label="用户名"
            :placeholder="
              creating ? '3至32位字母、数字或下划线' : '请输入用户名'
            "
            maxlength="32"
            autocomplete="username"
          />
          <van-field
            v-model="password"
            label="密码"
            type="password"
            :placeholder="creating ? '8至64个字符' : '请输入密码'"
            maxlength="64"
            :autocomplete="creating ? 'new-password' : 'current-password'"
          />
        </van-cell-group>
        <p v-if="error" class="account-error" role="alert">{{ error }}</p>
        <van-button
          native-type="submit"
          type="primary"
          block
          round
          :loading="busy"
          :disabled="busy"
          class="login-submit"
          >{{ creating ? "注册并登录" : "登录，继续我的旅程" }}</van-button
        >
      </form>
      <p class="login-note">行程与对话分别保存在你的账号下。</p>
    </div>
  </main>
</template>
<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { login, register, safeRedirect } from "../utils/auth";
import { messageOf } from "../utils/http";
import { validateCredentials } from "../utils/authValidation";
const username = ref(""),
  password = ref(""),
  creating = ref(false),
  busy = ref(false),
  error = ref("");
const router = useRouter(),
  route = useRoute();
const submit = async () => {
  if (busy.value) return;
  error.value = validateCredentials(
    username.value,
    password.value,
    creating.value,
  );
  if (error.value) return;
  busy.value = true;
  error.value = "";
  try {
    await (creating.value ? register : login)(username.value, password.value);
    password.value = "";
    await router.replace(safeRedirect(route.query.redirect));
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    busy.value = false;
  }
};
</script>
<style scoped>
.login-page {
  display: flex;
  align-items: center;
  background: #edf3f7;
  padding: 28px 20px;
}
.login-sheet {
  width: 100%;
  max-width: 430px;
  margin: auto;
  padding: 32px 20px;
  background: #fff;
  border-radius: 20px;
  border-top: 5px solid var(--primary);
  box-shadow: 0 16px 45px #183b5610;
}
.login-tabs {
  display: flex;
  margin: 22px 0;
  border-bottom: 1px solid #e7edf1;
  gap: 25px;
}
.login-tabs button {
  appearance: none;
  background: none;
  border: 0;
  padding: 10px 0;
  color: #7e8b95;
  font-size: 15px;
  cursor: pointer;
}
.login-tabs button.selected {
  color: #176bb7;
  border-bottom: 2px solid var(--primary);
  font-weight: 600;
}
.login-submit {
  margin-top: 25px;
}
.login-note {
  font-size: 12px;
  color: #89939c;
  line-height: 1.8;
  margin: 20px 0 0;
}
:deep(.van-cell-group--inset) {
  margin: 0;
  border: 1px solid #e9edf1;
  border-radius: 10px;
}
:deep(.van-field__label) {
  width: 56px;
}
.login-page { min-height: 100dvh; background: radial-gradient(ellipse at 80% 10%, #e3edff, transparent 60%), #f3f6fa; }
.login-sheet { max-width: 460px; padding: 36px; border: 1px solid var(--line); border-top: 4px solid var(--primary); border-radius: 22px; box-shadow: 0 20px 70px #34567c0c; }
.login-sheet .account-heading { font-family: inherit; font-size: 27px; letter-spacing: -.5px; }
.login-tabs button { font-size: 14px; }
@media (max-width: 599px) { .login-sheet { padding: 28px 22px; } }
</style>
