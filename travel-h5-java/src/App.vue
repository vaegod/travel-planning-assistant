<script setup>
import { ref, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { auth, clearAuth } from "./utils/auth";

const route = useRoute();
const router = useRouter();
const expired = () => {
  clearAuth();
  if (route.path !== "/login")
    void router.replace({
      path: "/login",
      query: { redirect: route.fullPath },
    });
};
onMounted(() => window.addEventListener("auth-expired", expired));
onBeforeUnmount(() => window.removeEventListener("auth-expired", expired));

const active = ref(0);
</script>

<template>
  <div class="app-container">
    <header v-if="route.path !== '/login'" class="desktop-header">
      <div class="desktop-header-inner">
        <router-link to="/" class="brand" aria-label="旅游规划智能助手首页">
          <span class="brand-mark"><van-icon name="guide-o" /></span>
          <span>旅游规划智能助手<small>TRAVEL, WITH A PLAN.</small></span>
        </router-link>
        <nav aria-label="主导航" class="desktop-nav">
          <router-link to="/" :class="{ selected: route.name === 'Home' || route.name === 'Detail' }">规划旅行</router-link>
          <router-link to="/chat" :class="{ selected: ['Chat', 'SavedChat', 'Conversations'].includes(route.name) }">AI 对话</router-link>
          <router-link to="/trips" :class="{ selected: route.path === '/trips' }">我的行程</router-link>
        </nav>
        <router-link to="/profile" class="nav-account" :class="{ selected: route.name === 'Profile' }"><van-icon name="user-o" /><span>{{ auth.user?.username || '我的' }}</span></router-link>
      </div>
    </header>
    <router-view></router-view>
    <van-tabbar
      v-if="['Home', 'Chat', 'SavedChat', 'Profile'].includes(route.name)"
      route
      v-model="active"
    >
      <van-tabbar-item to="/" icon="home-o">首页</van-tabbar-item>
      <van-tabbar-item :to="route.name === 'SavedChat' ? { name: 'SavedChat', params: { id: route.params.id } } : { name: 'Chat' }" icon="chat-o">对话</van-tabbar-item>
      <van-tabbar-item to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>
