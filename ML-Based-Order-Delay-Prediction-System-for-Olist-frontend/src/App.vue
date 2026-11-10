<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { ArrowDown, Setting, SwitchButton, User } from '@element-plus/icons-vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AuthRequiredDialog from '@/components/AuthRequiredDialog.vue'
import WorkbenchTopbar from '@/components/WorkbenchTopbar.vue'
import { provideWorkspaceState } from '@/state/workspace'
import {
  AUTH_REQUIRED_EVENT,
  initializeAuth,
  useAuth,
} from '@/auth/keycloak'

const authDialogVisible = ref(false)
const locale = zhCn
const route = useRoute()
const router = useRouter()
provideWorkspaceState()
const auth = useAuth()
const accountInitial = computed(() => (auth.state.user?.name || 'U').charAt(0).toUpperCase())
const isFullscreenRoute = computed(() => route.meta.fullscreen === true)
const hasSubNavigation = computed(() => route.meta.navigationKey !== 'workspace')
function startLogin() {
  router.push({ name: 'login' })
}

function handleAuthenticationRequired() {
  authDialogVisible.value = true
}

async function handleAccountCommand(command) {
  if (command === 'settings') {
    router.push({ name: 'settings' })
    return
  }
  if (command === 'logout') {
    try {
      await auth.logout()
    } catch {
      ElMessage.error('退出登录失败，请重试')
    }
  }
}

watch(
  () => auth.state.authenticated,
  (authenticated) => {
    if (authenticated) {
      authDialogVisible.value = false
    }
  },
)

onMounted(() => {
  window.addEventListener(AUTH_REQUIRED_EVENT, handleAuthenticationRequired)
  initializeAuth()
})

onBeforeUnmount(() => {
  window.removeEventListener(AUTH_REQUIRED_EVENT, handleAuthenticationRequired)
})
</script>

<template>
  <el-config-provider :locale="locale">
    <div v-if="!isFullscreenRoute" class="app-frame">
      <WorkbenchTopbar>
        <el-dropdown
          v-if="auth.state.authenticated"
          trigger="click"
          @command="handleAccountCommand"
        >
          <button type="button" class="account-trigger" aria-label="账户菜单">
            <span class="account-avatar">{{ accountInitial }}</span>
            <span class="account-name">{{ auth.state.user?.name }}</span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <div class="account-summary">
                <strong>{{ auth.state.user?.name }}</strong>
                <span>{{ auth.state.user?.email }}</span>
              </div>
              <el-dropdown-item command="settings">
                <el-icon><Setting /></el-icon>
                设置
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <button
          v-else
          type="button"
          class="account-trigger"
          aria-label="登录 / 注册"
          @click="startLogin"
        >
          <span class="account-avatar"><el-icon><User /></el-icon></span>
          <span class="account-name">登录 / 注册</span>
        </button>
      </WorkbenchTopbar>

      <div class="app-body">
        <AppSidebar v-if="hasSubNavigation" />
        <main class="app-main">
          <div class="workbench-content">
            <RouterView />
          </div>
        </main>
      </div>

      <AuthRequiredDialog
        v-model="authDialogVisible"
        @continue="startLogin"
      />
    </div>
    <RouterView v-else />
  </el-config-provider>
</template>

<style scoped>
.app-main {
  border-left: 0;
}

@media (max-width: 760px) {
  .workbench-content {
    padding: 12px 12px calc(116px + env(safe-area-inset-bottom));
  }
}

.account-trigger {
  display: inline-flex;
  min-width: 38px;
  height: 34px;
  align-items: center;
  gap: 9px;
  padding: 2px 8px 2px 3px;
  color: #43574d;
  font-size: 11px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
}

.account-trigger:hover {
  background: #eff3f0;
  border-color: #e5ebe7;
}

.account-trigger:disabled {
  cursor: wait;
  opacity: 0.7;
}

.account-avatar {
  display: grid;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  place-items: center;
  color: #0c5c48;
  font-size: 11px;
  font-weight: 650;
  background: #dff2ea;
  border-radius: 4px;
}

.account-name {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-summary {
  display: flex;
  max-width: 240px;
  flex-direction: column;
  gap: 3px;
  padding: 8px 16px 10px;
  overflow: hidden;
}

.account-summary strong,
.account-summary span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-summary strong {
  color: #192521;
  font-size: 12px;
}

.account-summary span {
  color: #68766f;
  font-size: 11px;
}
</style>
