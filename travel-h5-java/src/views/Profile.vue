<template>
  <main class="account-page profile-page">
    <van-nav-bar title="我的" />
    <div class="account-content">
      <section class="profile-identity">
        <div class="profile-mark">
          {{ auth.user?.username.slice(0, 1).toUpperCase() }}
        </div>
        <div>
          <div class="account-eyebrow">我的旅行手记</div>
          <h1 class="account-heading">{{ auth.user?.username }}</h1>
          <p>下一次出发，从已保存的想法继续。</p>
        </div>
      </section>
      <p v-if="error" class="account-error" role="alert">{{ error }}</p>
      <div class="profile-counts">
        <button @click="router.push('/trips')">
          <strong>{{ trips }}</strong
          ><span>已保存行程</span></button
        ><button @click="router.push('/conversations')">
          <strong>{{ conversations }}</strong
          ><span>历史会话</span>
        </button>
      </div>
      <van-cell-group inset class="profile-links"
        ><van-cell
          title="我的行程"
          label="查看规划与历史版本"
          is-link
          icon="notes-o"
          to="/trips" /><van-cell
          title="历史会话"
          label="恢复聊天，继续追问"
          is-link
          icon="chat-o"
          to="/conversations" /><van-cell
          title="开始新对话"
          label="开启一个独立的话题"
          is-link
          icon="plus"
          to="/chat"
      /></van-cell-group>
      <section class="profile-settings">
        <h2 class="section-title">账号设置</h2>
        <van-cell-group inset class="profile-links">
          <van-cell
            title="修改用户名"
            label="更新登录名，保留原账号全部资料"
            icon="edit"
            is-link
            @click="openSettings('username')"
          />
          <van-cell
            title="修改密码"
            label="验证当前密码后设置新密码"
            icon="lock"
            is-link
            @click="openSettings('password')"
          />
        </van-cell-group>
      </section>
      <van-button
        block
        plain
        round
        :loading="busy"
        class="logout-button"
        @click="leave"
        >退出登录</van-button
      >
    </div>
    <van-popup
      v-model:show="settingsOpen"
      position="bottom"
      round
      :close-on-click-overlay="!saving"
      :close-on-popstate="!saving"
      @closed="clearAccountForm"
    >
      <section
        class="settings-sheet"
        role="dialog"
        aria-modal="true"
        :aria-label="settingsTitle"
      >
        <h2 class="section-title">{{ settingsTitle }}</h2>
        <p class="settings-note">当前用户名：{{ auth.user?.username }}</p>
        <p class="settings-note">
          修改后需重新登录，其他登录会话也将失效。行程、会话和已保存资料会保留。
        </p>
        <form @submit.prevent="saveSettings">
          <van-cell-group inset>
            <van-field
              v-model="accountForm.currentPassword"
              type="password"
              label="当前密码"
              placeholder="请输入当前密码"
              maxlength="64"
              autocomplete="current-password"
              :disabled="saving"
            />
            <van-field
              v-if="settingsKind === 'username'"
              v-model="accountForm.username"
              label="新用户名"
              placeholder="3至32位字母、数字或下划线"
              maxlength="32"
              autocomplete="username"
              :disabled="saving"
            />
            <template v-else>
              <van-field
                v-model="accountForm.newPassword"
                type="password"
                label="新密码"
                placeholder="8至64个字符"
                maxlength="64"
                autocomplete="new-password"
                :disabled="saving"
              />
              <van-field
                v-model="accountForm.confirmPassword"
                type="password"
                label="确认新密码"
                placeholder="再次输入新密码"
                maxlength="64"
                autocomplete="new-password"
                :disabled="saving"
              />
            </template>
          </van-cell-group>
          <p v-if="settingsError" class="account-error" role="alert">
            {{ settingsError }}
          </p>
          <div class="settings-actions">
            <van-button
              block
              round
              :disabled="saving"
              @click="settingsOpen = false"
              >取消</van-button
            >
            <van-button
              block
              round
              type="primary"
              native-type="submit"
              :loading="saving"
              :disabled="saving"
              >保存并重新登录</van-button
            >
          </div>
        </form>
      </section>
    </van-popup>
  </main>
</template>
<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { auth, logout, updateUsername, updatePassword } from "../utils/auth";
import { showSuccessToast } from "vant";
import {
  validateAccountChange,
  type AccountChange,
} from "../utils/accountSettings";
import { listTrips, listConversations } from "../utils/data";
import { messageOf } from "../utils/http";
const router = useRouter(),
  trips = ref<number | string>("—"),
  conversations = ref<number | string>("—"),
  error = ref(""),
  busy = ref(false);
const settingsOpen = ref(false),
  settingsKind = ref<AccountChange>("username"),
  saving = ref(false),
  settingsError = ref("");
const accountForm = reactive({
  username: "",
  currentPassword: "",
  newPassword: "",
  confirmPassword: "",
});
const settingsTitle = computed(() =>
  settingsKind.value === "username" ? "修改用户名" : "修改密码",
);
const clearAccountForm = () => {
  Object.assign(accountForm, {
    username: "",
    currentPassword: "",
    newPassword: "",
    confirmPassword: "",
  });
  settingsError.value = "";
};
const openSettings = (kind: AccountChange) => {
  clearAccountForm();
  settingsKind.value = kind;
  settingsOpen.value = true;
};
const saveSettings = async () => {
  if (saving.value) return;
  settingsError.value = validateAccountChange(
    settingsKind.value,
    accountForm,
    auth.user?.username || "",
  );
  if (settingsError.value) return;
  saving.value = true;
  try {
    if (settingsKind.value === "username")
      await updateUsername(accountForm.username, accountForm.currentPassword);
    else
      await updatePassword(
        accountForm.currentPassword,
        accountForm.newPassword,
        accountForm.confirmPassword,
      );
    settingsOpen.value = false;
    clearAccountForm();
    await router.replace({ path: "/login", query: { redirect: "/profile" } });
    showSuccessToast("账号信息已更新，请重新登录");
  } catch (e) {
    settingsError.value = messageOf(e);
  } finally {
    saving.value = false;
  }
};
onBeforeUnmount(clearAccountForm);
onMounted(async () => {
  try {
    const [a, b] = await Promise.all([listTrips(), listConversations()]);
    trips.value = a.total;
    conversations.value = b.total;
  } catch (e) {
    error.value = messageOf(e);
  }
});
const leave = async () => {
  busy.value = true;
  try {
    await logout();
    await router.replace("/login");
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    busy.value = false;
  }
};
</script>
<style scoped>
.profile-identity {
  display: flex;
  align-items: center;
  gap: 16px;
  margin: 12px 0 25px;
}
.profile-mark {
  display: grid;
  place-items: center;
  width: 58px;
  height: 68px;
  border-radius: 14px;
  color: #fff;
  background: #1678c8;
  font:
    32px "Georgia",
    serif;
  flex-shrink: 0;
}
.profile-identity h1 {
  font-size: 23px;
  overflow-wrap: anywhere;
}
.profile-identity p {
  margin: 0;
  font-size: 12px;
  color: #7a8791;
  line-height: 1.6;
}
.profile-counts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 22px;
}
.profile-counts button {
  border: 1px solid #e7edf1;
  background: white;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  cursor: pointer;
}
.profile-counts strong {
  font:
    32px "Georgia",
    serif;
  color: #226497;
}
.profile-counts span {
  font-size: 13px;
  color: #647583;
}
.profile-links {
  margin: 0;
}
.logout-button {
  margin-top: 28px;
}
.profile-settings {
  margin-top: 24px;
}
.profile-settings h2 {
  font-size: 16px;
}
.settings-sheet {
  padding: 24px 16px calc(24px + env(safe-area-inset-bottom));
  max-height: 85vh;
  overflow-y: auto;
}
.settings-note {
  font-size: 13px;
  line-height: 1.7;
  color: #646566;
  margin: 0 0 12px;
  overflow-wrap: anywhere;
}
.settings-sheet :deep(.van-cell-group--inset) {
  margin: 20px 0;
  border: 1px solid #ebedf0;
  border-radius: 8px;
}
.settings-actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}
.settings-actions .van-button:first-child {
  flex: 1;
}
.settings-actions .van-button:last-child {
  flex: 2;
}
.profile-page .account-content { max-width: 780px; }
.profile-identity { background: #eaf1fc; border: 1px solid #e1e9f6; border-radius: 18px; padding: 26px; margin: 0 0 22px; }
.profile-mark { width: 62px; height: 62px; border-radius: 18px; font: 600 27px "Segoe UI", sans-serif; background: var(--primary); }
.profile-identity h1 { font-family: inherit; margin-bottom: 4px; font-size: 25px; }
.profile-counts button { border-color: var(--line); border-radius: 16px; padding: 22px 24px; transition: border-color .15s; }
.profile-counts button:hover { border-color: #a8c2f5; }
.profile-counts strong { font: 650 32px "Segoe UI", sans-serif; color: var(--primary); line-height: 1.2; }
.profile-links :deep(.van-cell__left-icon) { color: #6f8db8; margin-right: 12px; font-size: 19px; }
.profile-links :deep(.van-cell) { padding: 17px 22px; }
.logout-button { max-width: 200px; background: transparent; color: #75869a; border-color: #dce4ef; }
.settings-sheet { padding: 28px; }
@media (max-width: 599px) {
  .profile-identity { padding: 22px 20px; }
  .profile-counts button { padding: 20px; }
  .settings-sheet { padding: 24px 20px calc(24px + env(safe-area-inset-bottom)); }
  .settings-actions { gap: 8px; }
}
</style>
