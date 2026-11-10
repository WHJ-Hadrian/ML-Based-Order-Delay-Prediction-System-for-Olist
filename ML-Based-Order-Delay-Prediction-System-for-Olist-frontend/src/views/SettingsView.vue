<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Bell,
  Brush,
  Camera,
  Close,
  Collection,
  Fold,
  Grid,
  InfoFilled,
  Lock,
  Message,
  Monitor,
  Postcard,
  QuestionFilled,
  RefreshLeft,
  Search,
  SetUp,
  Timer,
  User,
  View,
} from '@element-plus/icons-vue'
import { useAuth } from '@/auth/keycloak'
import {
  SOFT_TIP_DELAY_OPTIONS,
  SOFT_TIP_PAGES,
  cloneSoftTipSettings,
  createDefaultSoftTipSettings,
  normalizeSoftTipSettings,
} from '@/config/softTips'
import {
  cloneAppearance,
  createDefaultAppearance,
  normalizeAppearance,
  useAppearance,
} from '@/state/appearance'
import { useSoftTipSettings } from '@/state/softTips'

const sectionItems = [
  { key: 'account', title: '个人信息', description: '账户资料与登录状态', icon: User },
  { key: 'tips', title: '使用提示', description: '柔性提示的出现方式', icon: Bell },
  { key: 'appearance', title: '外观', description: '背景、水印与侧边栏', icon: Brush },
]
const pageIcons = { datasets: Collection, fields: Grid, rules: SetUp }
const validSections = new Set(sectionItems.map((item) => item.key))

const route = useRoute()
const router = useRouter()
const { state: authState, logout } = useAuth()
const { settings, saveSoftTipSettings } = useSoftTipSettings()
const { appearance, saveAppearance } = useAppearance()

const requestedSection = String(route.query.section || '')
const activeSection = ref(validSections.has(requestedSection) ? requestedSection : 'account')
const tipDraft = ref(cloneSoftTipSettings(settings.value))
const appearanceDraft = ref(cloneAppearance(appearance.value))
const searchInputRef = ref(null)
const searchQuery = ref('')
const tipDrawerVisible = ref(false)
const activePageKey = ref('')
const highlightedTipKey = ref('')
const accountBusy = ref(false)
const allowLeave = ref(false)
const returnPath = ref(resolveReturnPath())
const activeSectionMeta = computed(
  () => sectionItems.find((item) => item.key === activeSection.value) || sectionItems[0],
)
const returnLabel = computed(() => {
  const pathname = returnPath.value.split('?')[0]
  if (pathname.startsWith('/datasets')) return '返回数据集库'
  if (pathname.startsWith('/fields')) return '返回字段库'
  if (pathname.startsWith('/rules')) return '返回规则库'
  return '返回工作台'
})
let highlightTimer = null

const accountInitial = computed(() =>
  (authState.user?.name || 'U').charAt(0).toUpperCase(),
)
const accountName = computed(() =>
  authState.authenticated ? authState.user?.name || '用户' : '未登录',
)
const accountEmail = computed(() =>
  authState.authenticated
    ? authState.user?.email || ''
    : '登录后可访问你的数据集和规则库',
)
const activePage = computed(
  () => SOFT_TIP_PAGES.find((page) => page.key === activePageKey.value) || null,
)
const totalTipCount = computed(() =>
  SOFT_TIP_PAGES.reduce((sum, page) => sum + page.tips.length, 0),
)
const enabledTipCount = computed(() =>
  SOFT_TIP_PAGES.reduce((sum, page) => sum + enabledTipsInPage(page), 0),
)
const tipsDirty = computed(
  () =>
    JSON.stringify(normalizeSoftTipSettings(tipDraft.value)) !==
    JSON.stringify(settings.value),
)
const appearanceDirty = computed(
  () =>
    JSON.stringify(normalizeAppearance(appearanceDraft.value)) !==
    JSON.stringify(appearance.value),
)
const dirty = computed(() => tipsDirty.value || appearanceDirty.value)
const searchResults = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) {
    return []
  }
  return searchEntries.value
    .filter((entry) =>
      `${entry.title} ${entry.subtitle} ${entry.keywords || ''}`
        .toLowerCase()
        .includes(keyword),
    )
    .slice(0, 18)
})
const searchEntries = computed(() => {
  const entries = [
    {
      key: 'account',
      section: 'account',
      title: '个人信息',
      subtitle: '账户资料、登录与退出',
      icon: User,
    },
    {
      key: 'tips-master',
      section: 'tips',
      title: '启用柔性提示',
      subtitle: '使用提示 · 总开关',
      keywords: '提示 关闭 开启',
      icon: Bell,
    },
    {
      key: 'tips-delay',
      section: 'tips',
      title: '默认等待时间',
      subtitle: '使用提示 · 默认等待时间',
      keywords: '一分钟 60 秒 空闲',
      icon: Bell,
    },
    {
      key: 'appearance-motion',
      section: 'appearance',
      title: '背景动效',
      subtitle: '外观 · 背景',
      keywords: '视频 动画 动态背景',
      icon: Brush,
    },
    {
      key: 'appearance-watermark',
      section: 'appearance',
      title: '身份水印',
      subtitle: '外观 · 水印',
      keywords: '姓名 邮箱 背景水印',
      icon: Brush,
    },
    {
      key: 'appearance-sidebar',
      section: 'appearance',
      title: '侧边栏默认收起',
      subtitle: '外观 · 侧边栏',
      keywords: '导航 左侧 收起',
      icon: Brush,
    },
  ]
  for (const page of SOFT_TIP_PAGES) {
    entries.push({
      key: `page-${page.key}`,
      section: 'tips',
      pageKey: page.key,
      title: page.title,
      subtitle: '使用提示 · 页面',
      keywords: page.description,
      icon: pageIcons[page.key],
    })
    for (const tip of page.tips) {
      entries.push({
        key: `tip-${page.key}-${tip.key}`,
        section: 'tips',
        pageKey: page.key,
        tipKey: tip.key,
        title: tip.title,
        subtitle: `使用提示 · ${page.title}`,
        keywords: tip.text,
        icon: InfoFilled,
      })
    }
  }
  return entries
})

const INTERNAL_RETURN_PATHS = ['/datasets', '/fields', '/rules']

function resolveReturnPath() {
  const back = window.history.state?.back
  if (typeof back === 'string' && back) {
    try {
      const url = new URL(back, window.location.origin)
      if (INTERNAL_RETURN_PATHS.includes(url.pathname)) {
        return `${url.pathname}${url.search}${url.hash}`
      }
    } catch {
      // Fall through to the workspace default.
    }
  }
  return '/datasets'
}

function enabledTipsInPage(page) {
  return page.tips.filter(
    (tip) => tipDraft.value.pages[page.key]?.tips?.[tip.key]?.enabled !== false,
  ).length
}

function setTipDelay(pageKey, tipKey, value) {
  tipDraft.value.pages[pageKey].tips[tipKey].delaySeconds =
    value === 'inherit' ? null : Number(value)
}

function setAllTipsInPage(pageKey, enabled) {
  const page = tipDraft.value.pages[pageKey]
  if (!page) return
  for (const tip of Object.values(page.tips)) {
    tip.enabled = enabled
  }
}

function saveAll({ notify = true } = {}) {
  saveSoftTipSettings(tipDraft.value)
  saveAppearance(appearanceDraft.value)
  tipDraft.value = cloneSoftTipSettings(settings.value)
  appearanceDraft.value = cloneAppearance(appearance.value)
  if (notify) {
    ElMessage.success('设置已保存，立即生效')
  }
}

function discardAll() {
  tipDraft.value = cloneSoftTipSettings(settings.value)
  appearanceDraft.value = cloneAppearance(appearance.value)
}

async function promptUnsaved() {
  try {
    await ElMessageBox.confirm(
      '当前设置还没有保存。保存会一次性应用到使用提示和外观，也可以放弃本次修改。',
      '未保存的修改',
      {
        confirmButtonText: '保存并离开',
        cancelButtonText: '放弃修改',
        distinguishCancelAndClose: true,
        type: 'warning',
      },
    )
    return 'save'
  } catch (action) {
    return action === 'cancel' ? 'discard' : 'stay'
  }
}

async function ensureLeavable({ notifyOnSave = false } = {}) {
  if (!dirty.value) {
    return true
  }
  const action = await promptUnsaved()
  if (action === 'save') {
    saveAll({ notify: false })
    if (notifyOnSave) {
      ElMessage.success('设置已保存')
    }
    return true
  }
  if (action === 'discard') {
    discardAll()
    return true
  }
  return false
}

function goToReturnPath() {
  allowLeave.value = true
  const back = window.history.state?.back
  if (typeof back === 'string' && back) {
    try {
      const url = new URL(back, window.location.origin)
      if (`${url.pathname}${url.search}${url.hash}` === returnPath.value) {
        router.back()
        return
      }
    } catch {
      // Fall through to a direct navigation.
    }
  }
  router.push(returnPath.value)
}

async function handleBack() {
  if (await ensureLeavable({ notifyOnSave: true })) {
    goToReturnPath()
  }
}

function confirmAndLeave() {
  const changed = dirty.value
  if (changed) {
    saveAll({ notify: false })
  }
  goToReturnPath()
  if (changed) {
    ElMessage.success('设置已保存')
  }
}

onBeforeRouteLeave(async () => {
  if (allowLeave.value) {
    return true
  }
  return ensureLeavable()
})

function handleBeforeUnload(event) {
  if (!dirty.value || allowLeave.value) {
    return
  }
  event.preventDefault()
  event.returnValue = ''
}

function setActiveSection(key) {
  searchQuery.value = ''
  activeSection.value = key
  nextTick(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' })
  })
}

function openTipDrawer(pageKey, tipKey = '') {
  activePageKey.value = pageKey
  highlightedTipKey.value = tipKey
  tipDrawerVisible.value = true
  if (tipKey) {
    window.setTimeout(() => {
      const target = document.querySelector(`[data-tip-key="${tipKey}"]`)
      target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    }, 380)
    if (highlightTimer) {
      window.clearTimeout(highlightTimer)
    }
    highlightTimer = window.setTimeout(() => {
      highlightedTipKey.value = ''
      highlightTimer = null
    }, 4600)
  }
}

function openSearchResult(result) {
  searchQuery.value = ''
  if (result.pageKey) {
    activeSection.value = 'tips'
    openTipDrawer(result.pageKey, result.tipKey || '')
    return
  }
  setActiveSection(result.section)
}

function openFirstSearchResult() {
  if (searchResults.value.length) {
    openSearchResult(searchResults.value[0])
  }
}

async function resetTipsDefaults() {
  try {
    await ElMessageBox.confirm(
      '恢复默认提示设置？修改会在点击「保存」或「确定」后生效。',
      '恢复默认提示',
      {
        confirmButtonText: '恢复默认',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  tipDraft.value = createDefaultSoftTipSettings()
}

async function resetAppearanceDefaults() {
  try {
    await ElMessageBox.confirm(
      '恢复默认外观？修改会在点击「保存」或「确定」后生效。',
      '恢复默认外观',
      {
        confirmButtonText: '恢复默认',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  appearanceDraft.value = createDefaultAppearance()
}

async function handleLogin() {
  if (await ensureLeavable({ notifyOnSave: true })) {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
  }
}

async function handleLogout() {
  if (!(await ensureLeavable({ notifyOnSave: true }))) {
    return
  }
  accountBusy.value = true
  try {
    await logout()
    ElMessage.success('已退出登录')
  } catch {
    ElMessage.error('退出登录失败，请重试')
  } finally {
    accountBusy.value = false
  }
}

watch(tipDrawerVisible, (open) => {
  if (!open) {
    highlightedTipKey.value = ''
    if (highlightTimer) {
      window.clearTimeout(highlightTimer)
      highlightTimer = null
    }
  }
})

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  if (highlightTimer) {
    window.clearTimeout(highlightTimer)
  }
})
</script>
<template>
  <main class="settings-shell">
    <header class="settings-topbar">
      <div class="settings-topbar-leading">
        <button type="button" class="settings-back-button" :title="returnLabel" :aria-label="returnLabel" @click="handleBack">
          <el-icon><ArrowLeft /></el-icon>
        </button>
        <span class="settings-brand">SeekDateSet</span>
        <span class="settings-brand-suffix">设置</span>
      </div>

      <div class="settings-searchbox">
        <el-input
          ref="searchInputRef"
          v-model="searchQuery"
          :prefix-icon="Search"
          placeholder="搜索 SeekDateSet 设置"
          aria-label="搜索 SeekDateSet 设置"
          clearable
          @keyup.enter="openFirstSearchResult"
        />
      </div>

      <div class="settings-topbar-actions">
        <button type="button" class="settings-round-button" title="使用提示设置" aria-label="使用提示设置" @click="setActiveSection('tips')">
          <el-icon><QuestionFilled /></el-icon>
        </button>
        <button type="button" class="settings-round-button" :title="returnLabel" :aria-label="returnLabel" @click="handleBack">
          <el-icon><Grid /></el-icon>
        </button>
        <button type="button" class="settings-avatar-button" :title="accountName" @click="setActiveSection('account')">
          {{ accountInitial }}
        </button>
      </div>
    </header>

    <div class="settings-body">
      <aside class="settings-nav" aria-label="设置分类">
        <p class="settings-nav-caption">设置</p>
        <button
          v-for="item in sectionItems"
          :key="item.key"
          type="button"
          class="settings-nav-item"
          :class="{ 'is-active': activeSection === item.key && !searchQuery.trim() }"
          :aria-current="activeSection === item.key && !searchQuery.trim() ? 'page' : undefined"
          @click="setActiveSection(item.key)"
        >
          <span class="settings-nav-orb" :class="`is-${item.key}`">
            <el-icon><component :is="item.icon" /></el-icon>
          </span>
          <span class="settings-nav-copy">
            <strong>{{ item.title }}</strong>
            <span>{{ item.description }}</span>
          </span>
        </button>
        <span class="settings-nav-divider" aria-hidden="true"></span>
        <button type="button" class="settings-nav-item is-return" @click="handleBack">
          <span class="settings-nav-orb is-return"><el-icon><ArrowLeft /></el-icon></span>
          <span class="settings-nav-copy">
            <strong>{{ returnLabel }}</strong>
            <span>回到刚才的页面</span>
          </span>
        </button>
      </aside>

      <section class="settings-main">
        <template v-if="searchQuery.trim()">
          <header class="settings-page-head">
            <h1>搜索结果</h1>
            <p>找到 {{ searchResults.length }} 项与“{{ searchQuery.trim() }}”相关的设置。</p>
          </header>

          <div v-if="searchResults.length" class="settings-panel">
            <button
              v-for="result in searchResults"
              :key="result.key"
              type="button"
              class="settings-row settings-row-button"
              @click="openSearchResult(result)"
            >
              <span class="settings-row-icon">
                <el-icon><component :is="result.icon" /></el-icon>
              </span>
              <span class="settings-row-body">
                <strong>{{ result.title }}</strong>
                <span>{{ result.subtitle }}</span>
              </span>
              <el-icon class="settings-chevron"><ArrowRight /></el-icon>
            </button>
          </div>

          <div v-else class="settings-empty">
            <el-icon class="settings-empty-icon"><Search /></el-icon>
            <strong>没有找到相关设置</strong>
            <span>换一个关键词，或清空搜索后按分类查看。</span>
            <button type="button" class="settings-link-button" @click="searchQuery = ''">清空搜索</button>
          </div>
        </template>

        <template v-else>
          <header class="settings-page-head">
            <h1>{{ activeSectionMeta.title }}</h1>
            <p v-if="activeSection === 'account'">查看账户资料和登录状态。账户信息由 SeekDateSet 统一管理。</p>
            <p v-else-if="activeSection === 'tips'">柔性提示只在你停留并聚焦当前页面时计时，出现后继续操作会自动隐藏。</p>
            <p v-else>这些选项会改变整个工作台的呈现方式，保存后立即应用。</p>
          </header>

          <template v-if="activeSection === 'account'">
            <div class="settings-panel">
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Camera /></el-icon></span>
                <span class="settings-row-body">
                  <strong>个人资料照片</strong>
                  <span>用于在工作台中识别你</span>
                </span>
                <span class="settings-profile-avatar">{{ accountInitial }}</span>
              </div>
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><User /></el-icon></span>
                <span class="settings-row-body">
                  <strong>姓名</strong>
                  <span>{{ authState.authenticated ? accountName : '登录后显示' }}</span>
                </span>
              </div>
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Message /></el-icon></span>
                <span class="settings-row-body">
                  <strong>邮箱</strong>
                  <span>{{ authState.authenticated ? (authState.user?.email || '未设置邮箱') : '登录后显示' }}</span>
                </span>
              </div>
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Lock /></el-icon></span>
                <span class="settings-row-body">
                  <strong>账户状态</strong>
                  <span>退出登录不会删除数据集、字段库或规则库。</span>
                </span>
                <span class="settings-row-trailing">
                  <span class="settings-status-chip" :class="{ 'is-online': authState.authenticated }">
                    {{ authState.authenticated ? '已登录' : '未登录' }}
                  </span>
                  <button
                    v-if="!authState.authenticated"
                    type="button"
                    class="settings-primary-button"
                    :disabled="authState.status === 'checking'"
                    @click="handleLogin"
                  >
                    {{ authState.status === 'checking' ? '验证中…' : '登录 / 注册' }}
                  </button>
                  <button
                    v-else
                    type="button"
                    class="settings-outline-button"
                    :disabled="accountBusy"
                    @click="handleLogout"
                  >
                    {{ accountBusy ? '退出中…' : '退出登录' }}
                  </button>
                </span>
              </div>
            </div>
          </template>

          <template v-else-if="activeSection === 'tips'">
            <div class="settings-panel">
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Bell /></el-icon></span>
                <span class="settings-row-body">
                  <strong>启用柔性提示</strong>
                  <span>关闭后，所有页面都不再出现这类提示。</span>
                </span>
                <el-switch v-model="tipDraft.enabled" />
              </div>
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Timer /></el-icon></span>
                <span class="settings-row-body">
                  <strong>默认等待时间</strong>
                  <span>页面停留且没有操作这么久后出现；单条提示可以单独覆盖。</span>
                </span>
                <el-select v-model="tipDraft.idleSeconds" class="settings-select" :disabled="!tipDraft.enabled">
                  <el-option
                    v-for="option in SOFT_TIP_DELAY_OPTIONS"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                  />
                </el-select>
              </div>
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><View /></el-icon></span>
                <span class="settings-row-body">
                  <strong>计时方式</strong>
                  <span>切到其他标签页、窗口失焦或打开弹窗时不会计时。</span>
                </span>
                <span class="settings-row-value">仅页面可见且聚焦时</span>
              </div>
            </div>

            <div class="settings-group">
              <h2>按页面管理</h2>
              <p>点击页面打开右侧详情，调整单条提示的开关和等待时间。{{ enabledTipCount }} / {{ totalTipCount }} 条已启用。</p>
            </div>

            <div class="settings-panel">
              <article
                v-for="page in SOFT_TIP_PAGES"
                :key="page.key"
                class="settings-row settings-row-button"
                :class="{ 'is-muted': !tipDraft.enabled || !tipDraft.pages[page.key].enabled }"
                role="button"
                tabindex="0"
                @click="openTipDrawer(page.key)"
                @keydown.enter.prevent="openTipDrawer(page.key)"
                @keydown.space.prevent="openTipDrawer(page.key)"
              >
                <span class="settings-row-icon"><el-icon><component :is="pageIcons[page.key]" /></el-icon></span>
                <span class="settings-row-body">
                  <strong>{{ page.title }}</strong>
                  <span>{{ page.description }}</span>
                  <span class="settings-row-meta">
                    {{ enabledTipsInPage(page) }}/{{ page.tips.length }} 条 · {{ tipDraft.pages[page.key].enabled ? '页面已启用' : '页面已关闭' }}
                  </span>
                </span>
                <span class="settings-row-trailing" @click.stop @keydown.stop>
                  <el-switch v-model="tipDraft.pages[page.key].enabled" size="small" :disabled="!tipDraft.enabled" />
                  <el-icon class="settings-chevron"><ArrowRight /></el-icon>
                </span>
              </article>
            </div>

            <div class="settings-inline-action">
              <button type="button" class="settings-link-button" @click="resetTipsDefaults">
                <el-icon><RefreshLeft /></el-icon>
                恢复默认提示设置
              </button>
            </div>
          </template>

          <template v-else>
            <div class="settings-panel">
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Monitor /></el-icon></span>
                <span class="settings-row-body">
                  <strong>工作区背景</strong>
                  <span>使用稳定的浅色画布，保证表格、图表和流程节点始终清晰可读。</span>
                </span>
                <span class="settings-row-meta">Fluent 浅色</span>
              </div>
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Postcard /></el-icon></span>
                <span class="settings-row-body">
                  <strong>身份水印</strong>
                  <span>在背景和页面卡片上显示当前账户的身份水印。</span>
                </span>
                <el-switch v-model="appearanceDraft.watermark" />
              </div>
              <div class="settings-row">
                <span class="settings-row-icon"><el-icon><Fold /></el-icon></span>
                <span class="settings-row-body">
                  <strong>侧边栏默认收起</strong>
                  <span>下次进入工作台时以收起状态显示左侧导航栏。</span>
                </span>
                <el-switch v-model="appearanceDraft.sidebarCollapsed" />
              </div>
            </div>

            <div
              class="settings-preview"
              :class="{
                'is-static': !appearanceDraft.backgroundMotion,
                'is-watermark-hidden': !appearanceDraft.watermark,
              }"
            >
              <div
                class="settings-preview-stage"
                :class="{ 'is-sidebar-collapsed': appearanceDraft.sidebarCollapsed }"
              >
                <span class="settings-preview-orb settings-preview-orb-a"></span>
                <span class="settings-preview-orb settings-preview-orb-b"></span>

                <div class="settings-preview-app">
                  <aside
                    class="settings-preview-sidebar"
                    :class="{ 'is-collapsed': appearanceDraft.sidebarCollapsed }"
                  >
                    <div class="settings-preview-brand">
                      <span class="settings-preview-brand-mark"></span>
                      <span v-if="!appearanceDraft.sidebarCollapsed" class="settings-preview-brand-copy">
                        <i class="is-strong"></i>
                        <i></i>
                      </span>
                    </div>

                    <span v-if="!appearanceDraft.sidebarCollapsed" class="settings-preview-section">工作区</span>

                    <div class="settings-preview-menu">
                      <span
                        v-for="index in 3"
                        :key="index"
                        class="settings-preview-menu-item"
                        :class="{ 'is-active': index === 1 }"
                      >
                        <i class="settings-preview-menu-icon"></i>
                        <i v-if="!appearanceDraft.sidebarCollapsed" class="settings-preview-menu-label"></i>
                        <i v-if="!appearanceDraft.sidebarCollapsed && index === 1" class="settings-preview-menu-badge"></i>
                      </span>
                    </div>

                    <div class="settings-preview-sidebar-footer">
                      <i class="settings-preview-menu-icon"></i>
                      <i v-if="!appearanceDraft.sidebarCollapsed" class="settings-preview-menu-label"></i>
                    </div>
                  </aside>

                  <div class="settings-preview-main">
                    <div class="settings-preview-topbar">
                      <span class="settings-preview-account">
                        <i class="settings-preview-avatar"></i>
                        <i class="settings-preview-account-name"></i>
                        <i class="settings-preview-caret"></i>
                      </span>
                    </div>

                    <div class="settings-preview-breadcrumb">
                      <i></i>
                      <i></i>
                      <i></i>
                    </div>

                    <div class="settings-preview-hero">
                      <span class="settings-preview-hero-copy">
                        <i class="settings-preview-hero-title"></i>
                        <i class="settings-preview-hero-desc"></i>
                      </span>
                      <i class="settings-preview-hero-action"></i>
                    </div>

                    <div class="settings-preview-card">
                      <div class="settings-preview-card-head">
                        <i></i>
                        <i class="is-meta"></i>
                      </div>
                      <span v-for="index in 3" :key="index" class="settings-preview-row">
                        <i class="settings-preview-row-icon"></i>
                        <i class="settings-preview-row-text"></i>
                        <i class="settings-preview-row-meta"></i>
                      </span>
                    </div>

                    <span v-if="appearanceDraft.watermark" class="settings-preview-watermark">SeekDateSet</span>
                  </div>
                </div>
              </div>
              <div class="settings-preview-meta">
                <strong>外观预览</strong>
                <span>背景动效、水印和侧边栏状态会即时反映在这里，保存后应用到工作台。</span>
              </div>
            </div>

            <div class="settings-inline-action">
              <button type="button" class="settings-link-button" @click="resetAppearanceDefaults">
                <el-icon><RefreshLeft /></el-icon>
                恢复默认外观
              </button>
            </div>
          </template>
        </template>

        <div class="settings-actions" :class="{ 'is-dirty': dirty }">
          <div class="settings-actions-copy">
            <strong>{{ dirty ? '有未保存的修改' : '所有设置已保存' }}</strong>
            <span>保存范围：使用提示与外观 · 所有页面</span>
          </div>
          <div class="settings-actions-buttons">
            <button type="button" class="settings-outline-button" :disabled="!dirty" @click="saveAll()">保存</button>
            <button type="button" class="settings-primary-button" @click="confirmAndLeave">确定</button>
          </div>
        </div>

        <p class="settings-note">「保存」只保存设置并留在此页；「确定」会保存并{{ returnLabel }}。</p>
      </section>
    </div>

    <footer class="settings-footer">
      <nav aria-label="设置快捷入口">
        <button type="button" @click="setActiveSection('account')">个人信息</button>
        <button type="button" @click="setActiveSection('tips')">使用提示</button>
        <button type="button" @click="setActiveSection('appearance')">外观</button>
        <button type="button" @click="handleBack">{{ returnLabel }}</button>
      </nav>
      <span>SeekDateSet · 设置</span>
    </footer>

    <el-drawer
      v-model="tipDrawerVisible"
      class="settings-tip-drawer"
      size="520px"
      :with-header="false"
      append-to-body
    >
      <div v-if="activePage" class="tip-drawer-shell">
        <header class="tip-drawer-header">
          <div class="tip-drawer-heading">
            <span class="settings-eyebrow">使用提示</span>
            <h3>{{ activePage.title }}</h3>
            <p>{{ activePage.description }} · 修改会保留在本页，点击底部「保存」后生效。</p>
          </div>
          <button type="button" class="tip-drawer-close" aria-label="关闭页面提示设置" @click="tipDrawerVisible = false">
            <el-icon><Close /></el-icon>
          </button>
        </header>

        <div class="tip-drawer-scroll">
          <div v-if="!tipDraft.enabled" class="tip-drawer-alert">
            <el-icon><InfoFilled /></el-icon>
            使用提示总开关已关闭，本页提示暂时不会出现。
          </div>

          <div class="tip-drawer-master">
            <div class="tip-drawer-master-copy">
              <strong>启用「{{ activePage.title }}」页面的提示</strong>
              <span>关闭后只影响这个页面，其他页面不受影响。</span>
            </div>
            <el-switch v-model="tipDraft.pages[activePage.key].enabled" :disabled="!tipDraft.enabled" />
          </div>

          <div class="tip-drawer-bulk">
            <span>已启用 {{ enabledTipsInPage(activePage) }} / {{ activePage.tips.length }} 条</span>
            <div>
              <el-button link type="primary" @click="setAllTipsInPage(activePage.key, true)">全部开启</el-button>
              <el-button link @click="setAllTipsInPage(activePage.key, false)">全部关闭</el-button>
            </div>
          </div>

          <div class="tip-drawer-list">
            <div
              v-for="tip in activePage.tips"
              :key="tip.key"
              class="tip-detail-row"
              :class="{
                'is-highlighted': highlightedTipKey === tip.key,
                'is-disabled':
                  !tipDraft.enabled ||
                  !tipDraft.pages[activePage.key].enabled ||
                  !tipDraft.pages[activePage.key].tips[tip.key].enabled,
              }"
              :data-tip-key="tip.key"
            >
              <el-switch
                v-model="tipDraft.pages[activePage.key].tips[tip.key].enabled"
                size="small"
                :disabled="!tipDraft.enabled || !tipDraft.pages[activePage.key].enabled"
              />
              <div class="tip-detail-copy">
                <strong>{{ tip.title }}</strong>
                <span>{{ tip.text }}</span>
              </div>
              <el-select
                :model-value="tipDraft.pages[activePage.key].tips[tip.key].delaySeconds ?? 'inherit'"
                class="tip-detail-delay"
                size="small"
                :disabled="
                  !tipDraft.enabled ||
                  !tipDraft.pages[activePage.key].enabled ||
                  !tipDraft.pages[activePage.key].tips[tip.key].enabled
                "
                @update:model-value="(value) => setTipDelay(activePage.key, tip.key, value)"
              >
                <el-option value="inherit" label="跟随默认" />
                <el-option
                  v-for="option in SOFT_TIP_DELAY_OPTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
          </div>
        </div>

        <footer class="tip-drawer-footer">
          <span>修改会保留在设置页，点击底部「保存」后生效。</span>
          <el-button type="primary" @click="tipDrawerVisible = false">完成</el-button>
        </footer>
      </div>
    </el-drawer>
  </main>
</template><style scoped>
.settings-shell {
  min-height: 100vh;
  min-height: 100dvh;
  color: #1f1f1f;
  font-family: var(--font-sans);
  background: #f0f4f9;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

.settings-shell *,
.settings-shell *::before,
.settings-shell *::after {
  box-sizing: border-box;
}

.settings-shell button,
.settings-shell input,
.settings-shell select {
  font: inherit;
}

.settings-shell button {
  -webkit-tap-highlight-color: transparent;
}

.settings-topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: clamp(12px, 2vw, 24px);
  height: 72px;
  padding: 0 clamp(14px, 2.6vw, 32px);
  background: rgba(240, 244, 249, 0.94);
  backdrop-filter: blur(16px);
}

.settings-topbar-leading {
  display: flex;
  min-width: 0;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.settings-back-button,
.settings-round-button {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  color: #444746;
  font-size: 20px;
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.settings-back-button:hover,
.settings-round-button:hover {
  color: #1f1f1f;
  background: #e3e9f2;
}

.settings-brand {
  overflow: hidden;
  color: #3c4043;
  font-size: 21px;
  font-weight: 500;
  letter-spacing: -0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-brand-suffix {
  color: #5f6368;
  font-size: 21px;
  font-weight: 400;
}

.settings-searchbox {
  width: min(720px, 52vw);
  max-width: 720px;
  flex: 0 1 720px;
  margin: 0;
}

.settings-searchbox :deep(.el-input__wrapper) {
  height: 48px;
  padding: 0 18px;
  background: #e4ebf5;
  border: 0;
  border-radius: 999px;
  box-shadow: none;
  transition: background 0.16s ease, box-shadow 0.16s ease;
}

.settings-searchbox :deep(.el-input__wrapper:hover) {
  background: #dde6f2;
  box-shadow: none;
}

.settings-searchbox :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow:
    0 1px 2px rgba(60, 64, 67, 0.16),
    0 1px 3px 1px rgba(60, 64, 67, 0.1);
}

.settings-searchbox :deep(.el-input__inner) {
  color: #1f1f1f;
  font-size: 15px;
}

.settings-searchbox :deep(.el-input__inner::placeholder) {
  color: #5f6368;
}

.settings-searchbox :deep(.el-input__prefix) {
  margin-right: 6px;
  color: #444746;
  font-size: 19px;
}

.settings-topbar-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}

.settings-avatar-button {
  display: grid;
  width: 36px;
  height: 36px;
  margin-left: 6px;
  flex: 0 0 36px;
  place-items: center;
  color: #0c5c48;
  font-size: 14px;
  font-weight: 600;
  background: #d9efe5;
  border: 2px solid #fff;
  border-radius: 50%;
  box-shadow: 0 1px 3px rgba(60, 64, 67, 0.18);
  cursor: pointer;
}

.settings-avatar-button:hover {
  background: #c8e8da;
}

.settings-body {
  display: grid;
  width: min(1440px, 100%);
  grid-template-columns: 280px minmax(0, 1fr);
  gap: clamp(24px, 4vw, 72px);
  align-items: start;
  margin: 0 auto;
  padding: 12px clamp(16px, 3vw, 48px) 40px;
}

.settings-nav {
  position: sticky;
  top: 88px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 0;
}

.settings-nav-caption {
  margin: 0 0 8px 18px;
  color: #5f6368;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.settings-nav-item {
  display: flex;
  width: 100%;
  min-height: 52px;
  align-items: center;
  gap: 16px;
  padding: 6px 16px 6px 8px;
  color: #1f1f1f;
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.16s ease;
}

.settings-nav-item:hover {
  background: #e3e9f2;
}

.settings-nav-item.is-active {
  background: #d9ece4;
}

.settings-nav-item.is-active .settings-nav-copy strong {
  color: #0f5c49;
}

.settings-nav-orb {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  color: #444746;
  font-size: 19px;
  background: #e8eaed;
  border-radius: 50%;
  transition: background 0.16s ease, color 0.16s ease;
}

.settings-nav-orb.is-account {
  color: var(--accent);
  background: #dff2ea;
}

.settings-nav-orb.is-tips {
  color: #a25a00;
  background: #fdead0;
}

.settings-nav-orb.is-appearance {
  color: #8e24aa;
  background: #f4e4f8;
}

.settings-nav-item.is-active .settings-nav-orb {
  color: #fff;
  background: var(--accent);
}

.settings-nav-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.settings-nav-copy strong {
  overflow: hidden;
  font-size: 14.5px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-nav-copy span {
  overflow: hidden;
  color: #5f6368;
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-nav-divider {
  display: block;
  height: 1px;
  margin: 12px 18px;
  background: #dbe1e8;
}

.settings-nav-item.is-return {
  color: #444746;
}

.settings-nav-item.is-return .settings-nav-copy strong {
  font-weight: 450;
}

.settings-main {
  display: flex;
  width: 100%;
  max-width: 720px;
  flex-direction: column;
  margin: 0 auto;
  padding-top: 8px;
}

.settings-page-head {
  margin: 0 0 20px;
}

.settings-page-head h1 {
  margin: 0 0 6px;
  color: #1f1f1f;
  font-size: 28px;
  font-weight: 500;
  line-height: 1.3;
  letter-spacing: -0.01em;
}

.settings-page-head p {
  max-width: 640px;
  margin: 0;
  color: #444746;
  font-size: 14px;
  line-height: 1.6;
}

.settings-panel {
  overflow: hidden;
  background: #fff;
  border-radius: 24px;
  box-shadow:
    0 1px 2px rgba(60, 64, 67, 0.08),
    0 1px 3px 1px rgba(60, 64, 67, 0.04);
}

.settings-row {
  display: grid;
  min-height: 74px;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  gap: 20px;
  align-items: center;
  padding: 16px 24px;
  border-top: 1px solid #e8eaed;
}

.settings-row:first-child {
  border-top: 0;
}

.settings-row-icon {
  display: grid;
  place-items: center;
  color: #444746;
  font-size: 22px;
}

.settings-row-body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.settings-row-body strong {
  color: #1f1f1f;
  font-size: 15px;
  font-weight: 500;
  line-height: 1.45;
}

.settings-row-body span {
  color: #444746;
  font-size: 13.5px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.settings-row-body .settings-row-meta {
  margin-top: 1px;
  color: var(--accent);
  font-size: 12.5px;
  font-weight: 500;
}

.settings-row-value {
  color: #444746;
  font-size: 14px;
  line-height: 1.5;
  text-align: right;
}

.settings-row-trailing {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;
}

.settings-row-button {
  width: 100%;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  border-top: 1px solid #e8eaed;
  cursor: pointer;
  transition: background 0.16s ease;
}

.settings-row-button:first-child {
  border-top: 0;
}

.settings-row-button:hover {
  background: #f8fafd;
}

.settings-row-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.settings-row-button.is-muted .settings-row-body {
  opacity: 0.78;
}

.settings-row-button.is-muted .settings-row-trailing {
  opacity: 1;
}

.settings-chevron {
  color: #5f6368;
  font-size: 18px;
}

.settings-profile-avatar {
  display: grid;
  width: 56px;
  height: 56px;
  place-items: center;
  color: #0c5c48;
  font-size: 22px;
  font-weight: 600;
  background: #dff2ea;
  border-radius: 50%;
}

.settings-status-chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 11px;
  color: #5f6368;
  font-size: 12.5px;
  font-weight: 500;
  background: #f1f3f4;
  border-radius: 999px;
}

.settings-status-chip.is-online {
  color: #0f5c49;
  background: #e2f3eb;
}

.settings-select {
  width: 190px;
}

.settings-select :deep(.el-select__wrapper) {
  min-height: 38px;
  font-size: 14px;
}

.settings-group {
  margin: 28px 0 10px;
}

.settings-group h2 {
  margin: 0 0 4px;
  color: #1f1f1f;
  font-size: 17px;
  font-weight: 500;
}

.settings-group p {
  margin: 0;
  color: #5f6368;
  font-size: 13px;
  line-height: 1.6;
}

.settings-inline-action {
  display: flex;
  margin: 14px 2px 0;
}

.settings-link-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 2px;
  color: var(--accent);
  font-size: 13.5px;
  font-weight: 500;
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.settings-link-button:hover {
  color: #0d4d3e;
  text-decoration: underline;
}

.settings-link-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.settings-empty {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 8px;
  padding: 52px 24px;
  text-align: center;
  background: #fff;
  border-radius: 24px;
  box-shadow:
    0 1px 2px rgba(60, 64, 67, 0.08),
    0 1px 3px 1px rgba(60, 64, 67, 0.04);
}

.settings-empty-icon {
  margin-bottom: 4px;
  color: #5f6368;
  font-size: 28px;
}

.settings-empty strong {
  color: #1f1f1f;
  font-size: 16px;
  font-weight: 500;
}

.settings-empty span {
  color: #5f6368;
  font-size: 13.5px;
}

.settings-actions {
  position: sticky;
  bottom: 18px;
  z-index: 15;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 28px;
  padding: 14px 20px;
  background: #fff;
  border-radius: 20px;
  box-shadow:
    0 1px 2px rgba(60, 64, 67, 0.12),
    0 10px 28px rgba(60, 64, 67, 0.14);
}

.settings-actions-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.settings-actions-copy strong {
  color: #1f1f1f;
  font-size: 14px;
  font-weight: 500;
}

.settings-actions-copy span {
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.5;
}

.settings-actions.is-dirty .settings-actions-copy strong {
  color: #8a4b00;
}

.settings-actions-buttons {
  display: flex;
  flex: 0 0 auto;
  gap: 10px;
}

.settings-outline-button,
.settings-primary-button {
  display: inline-flex;
  height: 38px;
  align-items: center;
  justify-content: center;
  padding: 0 18px;
  font-size: 13.5px;
  font-weight: 500;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease, box-shadow 0.16s ease;
}

.settings-outline-button {
  color: var(--accent);
  background: transparent;
  border: 1px solid #747775;
}

.settings-outline-button:hover:not(:disabled) {
  background: #eef7f3;
}

.settings-primary-button {
  color: #fff;
  background: var(--accent);
  border: 1px solid var(--accent);
}

.settings-primary-button:hover:not(:disabled) {
  background: #0f5c49;
  border-color: #0f5c49;
}

.settings-outline-button:disabled,
.settings-primary-button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.settings-outline-button:focus-visible,
.settings-primary-button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.settings-note {
  margin: 12px 4px 0;
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.6;
}

.settings-footer {
  display: flex;
  width: min(1440px, 100%);
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 auto;
  padding: 8px clamp(16px, 3vw, 48px) 30px;
}

.settings-footer nav {
  display: flex;
  flex-wrap: wrap;
  gap: 22px;
}

.settings-footer button {
  padding: 0;
  color: #444746;
  font-size: 12px;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.settings-footer button:hover {
  color: var(--accent);
  text-decoration: underline;
}

.settings-footer span {
  color: #5f6368;
  font-size: 12px;
}

.settings-eyebrow {
  color: var(--accent);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
.settings-preview {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 24px;
  padding: 20px;
  background: #fff;
  border: 0;
  border-radius: 24px;
  box-shadow:
    0 1px 2px rgba(60, 64, 67, 0.08),
    0 1px 3px 1px rgba(60, 64, 67, 0.04);
}

.settings-preview-stage {
  position: relative;
  height: 236px;
  padding: 10px;
  overflow: hidden;
  background:
    linear-gradient(130deg, rgba(230, 246, 239, 0.94), rgba(220, 239, 232, 0.94) 46%, rgba(248, 252, 250, 0.97));
  border: 1px solid rgba(15, 108, 189, 0.1);
  border-radius: 16px;
}

.settings-preview-app {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
  gap: 8px;
}

.settings-preview-orb {
  position: absolute;
  width: 180px;
  height: 180px;
  background: radial-gradient(circle, rgba(69, 168, 136, 0.34), transparent 68%);
  border-radius: 50%;
  filter: blur(3px);
  animation: previewDrift 11s ease-in-out infinite alternate;
}

.settings-preview-orb-a {
  top: -70px;
  left: 30%;
}

.settings-preview-orb-b {
  right: -40px;
  bottom: -80px;
  width: 220px;
  height: 220px;
  background: radial-gradient(circle, rgba(132, 196, 170, 0.3), transparent 68%);
  animation-delay: -4s;
}

.settings-preview.is-static .settings-preview-orb {
  animation: none;
}

.settings-preview-sidebar {
  display: flex;
  width: 20%;
  min-width: 92px;
  max-width: 132px;
  flex: 0 0 auto;
  flex-direction: column;
  padding: 8px 7px 7px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 9px;
  box-shadow: 0 8px 18px rgba(15, 52, 43, 0.08);
  transition: width 0.25s ease, min-width 0.25s ease, max-width 0.25s ease, padding 0.25s ease;
}

.settings-preview-sidebar.is-collapsed {
  width: 34px;
  min-width: 34px;
  max-width: 34px;
  padding: 8px 4px 7px;
}

.settings-preview-brand {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 8px;
}

.settings-preview-sidebar.is-collapsed .settings-preview-brand {
  justify-content: center;
}

.settings-preview-brand-mark {
  width: 16px;
  height: 14px;
  flex: 0 0 16px;
  background: radial-gradient(circle at 35% 40%, #eba766, #c2632d 68%);
  border-radius: 4px;
}

.settings-preview-brand-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.settings-preview-brand-copy i {
  display: block;
  width: 32px;
  height: 3px;
  background: rgba(21, 60, 50, 0.3);
  border-radius: 2px;
}

.settings-preview-brand-copy i.is-strong {
  width: 40px;
  background: rgba(21, 60, 50, 0.52);
}

.settings-preview-section {
  margin-bottom: 5px;
  color: rgba(24, 52, 44, 0.42);
  font-size: 6px;
  letter-spacing: 0.14em;
}

.settings-preview-menu {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.settings-preview-menu-item {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 17px;
  padding: 0 5px;
  border-radius: 5px;
}

.settings-preview-menu-item.is-active {
  background: rgba(15, 108, 189, 0.12);
}

.settings-preview-sidebar.is-collapsed .settings-preview-menu-item {
  justify-content: center;
  padding: 0;
}

.settings-preview-menu-icon {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  background: rgba(15, 108, 189, 0.4);
  border-radius: 2px;
}

.settings-preview-menu-item.is-active .settings-preview-menu-icon {
  background: var(--accent);
}

.settings-preview-menu-label {
  height: 3px;
  flex: 0 1 38px;
  background: rgba(21, 60, 50, 0.22);
  border-radius: 2px;
}

.settings-preview-menu-badge {
  width: 12px;
  height: 6px;
  margin-left: auto;
  background: rgba(15, 108, 189, 0.16);
  border-radius: 3px;
}

.settings-preview-sidebar-footer {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: auto;
  padding: 5px 5px 0;
  border-top: 1px solid rgba(15, 108, 189, 0.12);
}

.settings-preview-sidebar.is-collapsed .settings-preview-sidebar-footer {
  justify-content: center;
  padding: 5px 0 0;
}

.settings-preview-main {
  position: relative;
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 5px;
}

.settings-preview-topbar {
  display: flex;
  height: 16px;
  align-items: center;
  justify-content: flex-end;
}

.settings-preview-account {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 14px;
  padding: 0 5px;
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid rgba(255, 255, 255, 0.88);
  border-radius: 999px;
}

.settings-preview-avatar {
  width: 7px;
  height: 7px;
  background: rgba(15, 108, 189, 0.34);
  border-radius: 50%;
}

.settings-preview-account-name {
  width: 26px;
  height: 3px;
  background: rgba(21, 60, 50, 0.24);
  border-radius: 2px;
}

.settings-preview-caret {
  width: 4px;
  height: 4px;
  margin-top: -2px;
  border-right: 1.5px solid rgba(21, 60, 50, 0.32);
  border-bottom: 1.5px solid rgba(21, 60, 50, 0.32);
  transform: rotate(45deg);
}

.settings-preview-breadcrumb {
  display: flex;
  align-items: center;
  gap: 4px;
}

.settings-preview-breadcrumb i {
  width: 18px;
  height: 3px;
  background: rgba(21, 60, 50, 0.18);
  border-radius: 2px;
}

.settings-preview-breadcrumb i:last-child {
  width: 12px;
  background: rgba(21, 60, 50, 0.32);
}

.settings-preview-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}

.settings-preview-hero-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.settings-preview-hero-title {
  width: 66px;
  height: 6px;
  background: rgba(21, 60, 50, 0.5);
  border-radius: 2px;
}

.settings-preview-hero-desc {
  width: 132px;
  height: 3px;
  background: rgba(21, 60, 50, 0.2);
  border-radius: 2px;
}

.settings-preview-hero-action {
  width: 34px;
  height: 10px;
  background: rgba(15, 108, 189, 0.72);
  border-radius: 3px;
}

.settings-preview-card {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  padding: 7px;
  background: rgba(255, 255, 255, 0.68);
  border: 1px solid rgba(255, 255, 255, 0.88);
  border-radius: 8px;
  box-shadow: 0 8px 18px rgba(15, 52, 43, 0.05);
}

.settings-preview-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 5px;
  border-bottom: 1px solid rgba(15, 108, 189, 0.12);
}

.settings-preview-card-head i {
  width: 40px;
  height: 4px;
  background: rgba(21, 60, 50, 0.34);
  border-radius: 2px;
}

.settings-preview-card-head i.is-meta {
  width: 26px;
  height: 3px;
  background: rgba(21, 60, 50, 0.16);
}

.settings-preview-row {
  display: flex;
  min-height: 13px;
  flex: 1;
  align-items: center;
  gap: 5px;
  padding: 0 5px;
  background: rgba(255, 255, 255, 0.62);
  border-radius: 4px;
}

.settings-preview-row-icon {
  width: 6px;
  height: 6px;
  flex: 0 0 6px;
  background: rgba(15, 108, 189, 0.3);
  border-radius: 2px;
}

.settings-preview-row-text {
  width: 42px;
  height: 3px;
  background: rgba(21, 60, 50, 0.22);
  border-radius: 2px;
}

.settings-preview-row-meta {
  width: 20px;
  height: 3px;
  margin-left: auto;
  background: rgba(21, 60, 50, 0.14);
  border-radius: 2px;
}

.settings-preview-watermark {
  position: absolute;
  right: 6px;
  bottom: 4px;
  color: rgba(15, 108, 189, 0.22);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  transform: rotate(-8deg);
}

.settings-preview-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.settings-preview-meta strong {
  color: #1f1f1f;
  font-size: 14.5px;
  font-weight: 500;
}

.settings-preview-meta span {
  color: #5f6368;
  font-size: 13px;
  line-height: 1.55;
}



.settings-tip-drawer {
  max-width: 100vw;
}

:global(.settings-tip-drawer) {
  max-width: 100vw;
  background: #f7f9fc;
  box-shadow: -22px 0 54px rgba(8, 30, 24, 0.16);
}

:global(.settings-tip-drawer .el-drawer__body) {
  display: flex;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}

.tip-drawer-shell {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  color: var(--ink);
  background: #f7f9fc;
}

.tip-drawer-header {
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 22px 22px 16px;
  border-bottom: 1px solid rgba(15, 108, 189, 0.1);
}

.tip-drawer-heading {
  min-width: 0;
}

.tip-drawer-heading h3 {
  margin: 3px 0;
  color: var(--ink);
  font-size: 19px;
  font-weight: 650;
}

.tip-drawer-heading p {
  margin: 0;
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.6;
}

.tip-drawer-close {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  color: #5f6368;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(15, 108, 189, 0.1);
  border-radius: 50%;
  cursor: pointer;
}

.tip-drawer-close:hover {
  color: var(--ink);
  background: #fff;
}

.tip-drawer-scroll {
  min-height: 0;
  flex: 1;
  padding: 16px 22px 20px;
  overflow-y: auto;
}

.tip-drawer-alert {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 12px;
  color: #96611b;
  font-size: 12.5px;
  line-height: 1.55;
  background: #fdf3e4;
  border: 1px solid rgba(211, 143, 44, 0.16);
  border-radius: 10px;
}

.tip-drawer-alert .el-icon {
  flex: 0 0 auto;
  margin-top: 1px;
}

.tip-drawer-master {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(15, 108, 189, 0.1);
  border-radius: 13px;
}

.tip-drawer-master-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.tip-drawer-master-copy strong {
  font-size: 14px;
  font-weight: 500;
}

.tip-drawer-master-copy span {
  color: #5f6368;
  font-size: 12.5px;
  line-height: 1.5;
}

.tip-drawer-bulk {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 14px 0 4px;
  color: #5f6368;
  font-size: 12.5px;
}

.tip-drawer-list {
  margin-top: 4px;
}

.tip-detail-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 116px;
  gap: 10px;
  align-items: start;
  margin: 0 -10px;
  padding: 12px 10px;
  border-top: 1px dashed rgba(15, 108, 189, 0.1);
  border-radius: 10px;
  transition: background 0.2s ease;
}

.tip-detail-row:first-child {
  border-top: 0;
}

.tip-detail-row.is-highlighted {
  background: rgba(15, 108, 189, 0.08);
  animation: tipHighlight 1.8s ease;
}

.tip-detail-row > .el-switch {
  margin-top: 2px;
}

.tip-detail-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.tip-detail-copy strong {
  color: #1f1f1f;
  font-size: 13.5px;
  font-weight: 500;
}

.tip-detail-copy span {
  color: #444746;
  font-size: 12.5px;
  line-height: 1.6;
}

.tip-detail-row.is-disabled .tip-detail-copy {
  opacity: 0.7;
}

.tip-detail-delay {
  width: 116px;
}

.tip-drawer-footer {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 22px 18px;
  background: rgba(255, 255, 255, 0.9);
  border-top: 1px solid rgba(15, 108, 189, 0.1);
  box-shadow: 0 -10px 24px rgba(15, 52, 43, 0.04);
  backdrop-filter: blur(14px);
}

.tip-drawer-footer span {
  color: #5f6368;
  font-size: 12px;
  line-height: 1.5;
}



@keyframes previewDrift {
  from {
    transform: translate3d(0, 0, 0) scale(1);
  }

  to {
    transform: translate3d(18px, -12px, 0) scale(1.08);
  }
}

@keyframes tipHighlight {
  0% {
    background: rgba(15, 108, 189, 0.16);
  }

  100% {
    background: rgba(15, 108, 189, 0.08);
  }
}

@media (max-width: 1100px) {
  .settings-body {
    grid-template-columns: 1fr;
    gap: 14px;
    padding-top: 6px;
  }

  .settings-nav {
    position: static;
    flex-direction: row;
    gap: 6px;
    padding: 4px 0 8px;
    overflow-x: auto;
    scrollbar-width: none;
  }

  .settings-nav::-webkit-scrollbar {
    display: none;
  }

  .settings-nav-caption,
  .settings-nav-divider {
    display: none;
  }

  .settings-nav-item {
    width: auto;
    min-height: 44px;
    flex: 0 0 auto;
    padding: 5px 15px 5px 6px;
  }

  .settings-nav-orb {
    width: 32px;
    height: 32px;
    flex-basis: 32px;
    font-size: 16px;
  }

  .settings-nav-copy span {
    display: none;
  }

  .settings-nav-copy strong {
    font-size: 13.5px;
  }

  .settings-nav-item.is-return {
    display: none;
  }

  .settings-main {
    max-width: 760px;
    padding-top: 4px;
  }
}

@media (max-width: 760px) {
  .settings-topbar {
    height: auto;
    flex-wrap: wrap;
    gap: 10px;
    padding: 10px 14px 12px;
  }

  .settings-topbar-leading {
    order: 1;
  }

  .settings-topbar-actions {
    order: 2;
    margin-left: auto;
  }

  .settings-searchbox {
    width: 100%;
    max-width: none;
    order: 3;
    flex-basis: 100%;
  }

  .settings-brand,
  .settings-brand-suffix {
    font-size: 18px;
  }

  .settings-back-button,
  .settings-round-button {
    width: 36px;
    height: 36px;
    flex-basis: 36px;
    font-size: 18px;
  }

  .settings-avatar-button {
    width: 34px;
    height: 34px;
    flex-basis: 34px;
  }

  .settings-body {
    padding: 8px 14px 32px;
  }

  .settings-page-head h1 {
    font-size: 24px;
  }

  .settings-page-head p {
    font-size: 13.5px;
  }

  .settings-row {
    min-height: 66px;
    grid-template-columns: 22px minmax(0, 1fr) auto;
    gap: 14px;
    padding: 14px 16px;
  }

  .settings-row-icon {
    font-size: 20px;
  }

  .settings-row-body strong {
    font-size: 14.5px;
  }

  .settings-row-body span {
    font-size: 13px;
  }

  .settings-profile-avatar {
    width: 48px;
    height: 48px;
    font-size: 19px;
  }

  .settings-select {
    width: 150px;
  }

  .settings-actions {
    position: static;
    align-items: stretch;
    flex-direction: column;
    gap: 12px;
    padding: 14px 16px;
  }

  .settings-actions-buttons {
    justify-content: flex-end;
  }

  .settings-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
    padding: 6px 14px 26px;
  }

  .settings-preview-stage {
    height: 190px;
  }

  :global(.settings-tip-drawer) {
    width: 100vw !important;
    max-width: 100vw;
  }
}

@media (max-width: 520px) {
  .settings-row {
    grid-template-columns: 22px minmax(0, 1fr);
  }

  .settings-row-trailing,
  .settings-profile-avatar,
  .settings-chevron {
    grid-column: 2;
    justify-self: start;
    margin-top: 4px;
  }

  .settings-row-trailing {
    justify-content: flex-start;
    flex-wrap: wrap;
  }

  .settings-row-value {
    grid-column: 2;
    text-align: left;
  }

  .settings-row-body .settings-row-meta {
    font-size: 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .settings-preview-orb,
  .tip-detail-row.is-highlighted {
    animation: none;
  }

  .settings-preview-sidebar,
  .settings-preview-app,
  .settings-preview-card,
  .settings-preview-menu-item,
  .settings-nav-item,
  .settings-nav-orb {
    transition: none;
  }
}

.settings-shell { color: var(--ink); background: var(--canvas); }
.settings-topbar { height: 56px; padding-inline: 20px; background: var(--paper); border-bottom: 1px solid var(--line); backdrop-filter: none; }
.settings-back-button, .settings-round-button { width: 32px; height: 32px; flex-basis: 32px; border-radius: var(--radius-control); }
.settings-brand, .settings-brand-suffix { font-size: 16px; letter-spacing: 0; }
.settings-searchbox :deep(.el-input__wrapper) { height: 34px; padding-inline: 10px; background: #f3f4f5; border: 1px solid var(--line); border-radius: var(--radius-control); }
.settings-searchbox :deep(.el-input__wrapper:hover), .settings-searchbox :deep(.el-input__wrapper.is-focus) { background: #fff; box-shadow: 0 0 0 1px var(--accent) inset; }
.settings-searchbox :deep(.el-input__inner) { font-size: 12px; }
.settings-body { width: min(1320px, 100%); grid-template-columns: 220px minmax(0, 1fr); gap: 24px; padding: 12px 20px 32px; }
.settings-nav { top: 68px; }
.settings-nav-item { min-height: 40px; gap: 10px; padding: 4px 8px; border-radius: var(--radius-control); }
.settings-nav-orb { width: 28px; height: 28px; flex-basis: 28px; border-radius: var(--radius-control); font-size: 15px; }
.settings-nav-caption { margin-left: 8px; }
.settings-nav-divider { margin: 8px; }
.settings-main { max-width: 900px; padding-top: 4px; }
.settings-page-head { margin-bottom: 14px; }
.settings-page-head h1 { font-size: 21px; letter-spacing: 0; }
.settings-page-head p, .settings-row-body span { color: var(--ink-soft); }
.settings-panel, .settings-empty, .settings-preview, .settings-actions { border: 1px solid var(--line); border-radius: var(--radius-panel); box-shadow: none; }
.settings-row { min-height: 58px; grid-template-columns: 22px minmax(0, 1fr) auto; gap: 12px; padding: 11px 16px; border-color: var(--line-subtle); }
.settings-row-body strong { font-size: 13px; }
.settings-row-body span { font-size: 12px; }
.settings-group { margin: 20px 0 8px; }
.settings-group h2 { font-size: 15px; }
.settings-preview { gap: 9px; margin-top: 18px; padding: 12px; }
.settings-preview-stage { height: 210px; background: var(--canvas); border-color: var(--line); border-radius: var(--radius-panel); }
.settings-actions { bottom: 12px; gap: 10px; margin-top: 20px; padding: 10px 12px; }
.settings-outline-button, .settings-primary-button { min-height: 32px; border-radius: var(--radius-control); }
.settings-primary-button { background: var(--accent); border-color: var(--accent); }
.settings-outline-button { color: var(--accent); }
.settings-nav-item.is-active { background: var(--accent-subtle); }
.settings-nav-item.is-active .settings-nav-copy strong { color: var(--accent); }
.settings-link-button:hover { color: var(--accent-hover); }
.settings-status-chip, .settings-status-chip.is-online { border-radius: var(--radius-control); }
.settings-status-chip.is-online { color: var(--success); background: #edf7ed; }
.settings-row-button:focus-visible, .settings-link-button:focus-visible { outline-color: var(--accent); }

@media (max-width: 1100px) {
  .settings-body { grid-template-columns: 190px minmax(0, 1fr); gap: 18px; }
}

@media (max-width: 760px) {
  .settings-topbar { padding: 10px 14px 12px; }
  .settings-body { gap: 10px; padding: 8px 14px 28px; }
  .settings-nav { position: static; }
  .settings-panel, .settings-empty, .settings-preview, .settings-actions { border-radius: var(--radius-panel); }
  .settings-actions { position: static; }
}
</style>
