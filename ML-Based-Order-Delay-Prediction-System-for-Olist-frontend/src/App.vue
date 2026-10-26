<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { ArrowDown, SwitchButton, User } from '@element-plus/icons-vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AuthRequiredDialog from '@/components/AuthRequiredDialog.vue'
import liquidGlassBackdrop from '@/assets/videos/liquid-wave.mp4'
import { provideWorkspaceState } from '@/state/workspace'
import {
  AUTH_REQUIRED_EVENT,
  initializeAuth,
  useAuth,
} from '@/auth/keycloak'

const sidebarCollapsed = ref(false)
const authDialogVisible = ref(false)
const locale = zhCn
const route = useRoute()
const router = useRouter()
const { datasetTotal } = provideWorkspaceState()
const auth = useAuth()
const accountInitial = computed(() => (auth.state.user?.name || 'U').charAt(0).toUpperCase())
const isFullscreenRoute = computed(() => route.meta.fullscreen === true)
const watermarkText = computed(() => {
  const user = auth.state.user
  if (!user) return 'SeekDateSet · 数据分析工作台'

  const label = [user.name, user.email]
    .map((value) => String(value || '').trim())
    .filter(Boolean)
    .join(' · ')

  const safeLabel = label.length > 64 ? `${label.slice(0, 61)}...` : label
  return safeLabel || 'SeekDateSet'
})

function createWatermarkImage(text, options) {
  const { width, height, fontSize, rotation, opacity } = options
  const centerX = width / 2
  const centerY = height / 2
  const safeText = text.replace(/[&<>"']/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&apos;',
  })[character])
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><text x="${centerX}" y="${centerY}" fill="#176b57" fill-opacity="${opacity}" font-family="Roboto,Microsoft YaHei,sans-serif" font-size="${fontSize}" font-weight="500" text-anchor="middle" dominant-baseline="central" transform="rotate(${rotation} ${centerX} ${centerY})">${safeText}</text></svg>`

  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`
}

const watermarkStyle = computed(() => ({
  '--identity-watermark': createWatermarkImage(watermarkText.value, {
    width: 220,
    height: 92,
    fontSize: 9,
    rotation: -24,
    opacity: 0.13,
  }),
  '--identity-watermark-hero': createWatermarkImage(watermarkText.value, {
    width: 380,
    height: 96,
    fontSize: 17,
    rotation: -8,
    opacity: 0.09,
  }),
}))

function startLogin() {
  router.push({ name: 'login' })
}

function handleAuthenticationRequired() {
  authDialogVisible.value = true
}

async function handleAccountCommand(command) {
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
    <div v-if="!isFullscreenRoute" class="app-frame" :style="watermarkStyle">
      <div class="app-backdrop" aria-hidden="true">
        <video
          class="app-backdrop-media"
          :src="liquidGlassBackdrop"
          autoplay
          muted
          loop
          playsinline
          draggable="false"
        ></video>
        <span class="app-backdrop-wash"></span>
      </div>

      <AppSidebar v-model:collapsed="sidebarCollapsed" :total="datasetTotal" />

      <main class="app-main">
        <div class="workspace-topbar">
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
                <el-dropdown-item command="logout">
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
            @click="startLogin"
          >
            <span class="account-avatar"><el-icon><User /></el-icon></span>
            <span class="account-name">{{ auth.state.status === 'checking' ? '验证中' : '登录 / 注册' }}</span>
          </button>
        </div>

        <RouterView v-slot="{ Component }">
          <Transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>

      <AuthRequiredDialog
        v-model="authDialogVisible"
        @continue="startLogin"
      />
    </div>
    <RouterView v-else />
  </el-config-provider>
</template>

<style scoped>
.workspace-topbar {
  display: flex;
  min-height: 38px;
  justify-content: flex-end;
  margin-bottom: 8px;
}

.account-trigger {
  display: inline-flex;
  min-width: 38px;
  height: 38px;
  align-items: center;
  gap: 9px;
  padding: 3px 11px 3px 4px;
  color: #29483e;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.58);
  border: 1px solid rgba(255, 255, 255, 0.78);
  border-radius: 999px;
  box-shadow: 0 4px 14px rgba(22, 48, 42, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.8);
  cursor: pointer;
}

.account-trigger:hover {
  background: rgba(255, 255, 255, 0.82);
}

.account-trigger:disabled {
  cursor: wait;
  opacity: 0.7;
}

.account-avatar {
  display: grid;
  width: 29px;
  height: 29px;
  flex: 0 0 29px;
  place-items: center;
  color: #0c5c48;
  font-size: 12px;
  font-weight: 650;
  background: #dff2ea;
  border-radius: 50%;
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
