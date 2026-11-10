<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useAuth } from '@/auth/keycloak'

const router = useRouter()
const auth = useAuth()
const accountBusy = ref(false)
const accountStatus = computed(() => {
  if (auth.state.status === 'checking') return '验证中'
  return auth.state.authenticated ? '已登录' : '未登录'
})

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/workspace')
}

function handleLogin() {
  router.push({ name: 'login' })
}

async function handleLogout() {
  accountBusy.value = true
  try {
    await auth.logout()
  } catch {
    ElMessage.error('退出登录失败，请重试')
    accountBusy.value = false
  }
}
</script>

<template>
  <main class="settings-page">
    <header class="settings-header">
      <button type="button" class="back-button" aria-label="返回" title="返回" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <h1>设置</h1>
    </header>

    <section class="settings-section" aria-labelledby="account-heading">
      <h2 id="account-heading">账户</h2>
      <dl class="settings-list">
        <div>
          <dt>状态</dt>
          <dd>{{ accountStatus }}</dd>
        </div>
        <div>
          <dt>姓名</dt>
          <dd>{{ auth.state.authenticated ? (auth.state.user?.name || '未设置') : '-' }}</dd>
        </div>
        <div>
          <dt>邮箱</dt>
          <dd>{{ auth.state.authenticated ? (auth.state.user?.email || '未设置') : '-' }}</dd>
        </div>
      </dl>
      <div class="section-actions">
        <el-button
          v-if="auth.state.authenticated"
          :loading="accountBusy"
          @click="handleLogout"
        >退出登录</el-button>
        <el-button
          v-else
          type="primary"
          :disabled="auth.state.status === 'checking'"
          @click="handleLogin"
        >登录 / 注册</el-button>
      </div>
    </section>

  </main>
</template>

<style scoped>
.settings-page {
  min-height: 100dvh;
  color: var(--ink);
  background: #fff;
}

.settings-header {
  display: flex;
  height: 48px;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid var(--line);
}

.settings-header h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
}

.back-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  color: var(--ink-soft);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}

.back-button:hover {
  color: var(--accent-hover);
  background: #f1f3f2;
}

.settings-section {
  width: min(720px, calc(100% - 32px));
  margin: 0 auto;
  padding: 24px 0;
  border-bottom: 1px solid var(--line);
}

.settings-section:last-child {
  border-bottom: 0;
}

.settings-section h2 {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 650;
}

.settings-list {
  margin: 0;
  border-top: 1px solid var(--line);
}

.settings-list > div,
.setting-control {
  display: grid;
  min-height: 42px;
  align-items: center;
  border-bottom: 1px solid var(--line-subtle);
}

.settings-list > div {
  grid-template-columns: 160px minmax(0, 1fr);
}

.settings-list dt,
.setting-control > span {
  color: var(--ink-soft);
  font-size: 11px;
}

.settings-list dd {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--ink);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.setting-control {
  grid-template-columns: minmax(0, 1fr) auto;
  border-top: 1px solid var(--line);
  cursor: pointer;
}

@media (max-width: 520px) {
  .settings-header { padding-inline: 10px; }
  .settings-section { width: calc(100% - 24px); padding-block: 18px; }
  .settings-list > div { grid-template-columns: 100px minmax(0, 1fr); }
}
</style>
