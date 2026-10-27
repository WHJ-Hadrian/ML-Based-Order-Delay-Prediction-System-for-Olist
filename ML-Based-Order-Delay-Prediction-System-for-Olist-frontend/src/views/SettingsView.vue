<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Bell,
  Brush,
  Close,
  Collection,
  Grid,
  InfoFilled,
  RefreshLeft,
  Search,
  SetUp,
  SwitchButton,
  User,
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
  { key: 'account', title: '个人中心', description: '账户与登录状态', icon: User },
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
const searchQuery = ref('')
const tipDrawerVisible = ref(false)
const activePageKey = ref('')
const highlightedTipKey = ref('')
const accountBusy = ref(false)
const allowLeave = ref(false)
const returnPath = ref(resolveReturnPath())
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
      title: '个人中心',
      subtitle: '账户信息、登录与退出',
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

function resolveReturnPath() {
  const back = window.history.state?.back
  if (typeof back === 'string' && back) {
    try {
      const url = new URL(back, window.location.origin)
      if (!url.pathname.startsWith('/settings')) {
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
    router.push({ name: 'login' })
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
  <div class="settings-page">
    <el-breadcrumb separator="/" class="page-breadcrumb">
      <el-breadcrumb-item>工作台</el-breadcrumb-item>
      <el-breadcrumb-item>设置</el-breadcrumb-item>
    </el-breadcrumb>

    <header class="page-header settings-hero">
      <div>
        <h1>设置</h1>
        <p>管理账户、柔性提示与界面外观。修改会先保留在本页，保存后一次性生效。</p>
      </div>
      <div class="settings-hero-actions">
        <span class="settings-state-pill" :class="dirty ? 'is-dirty' : 'is-saved'">
          {{ dirty ? '有未保存的修改' : '设置已保存' }}
        </span>
        <el-button :icon="ArrowLeft" @click="handleBack">返回工作台</el-button>
      </div>
    </header>

    <div class="settings-layout">
      <aside class="settings-rail">
        <div class="settings-search">
          <el-input
            v-model="searchQuery"
            :prefix-icon="Search"
            placeholder="搜索设置或提示"
            clearable
            @keyup.enter="openFirstSearchResult"
          />
          <span class="settings-search-hint">试试搜索“水印”“剪贴板”或“一分钟”</span>
        </div>

        <nav class="settings-nav" aria-label="设置分类">
          <button
            v-for="item in sectionItems"
            :key="item.key"
            type="button"
            class="settings-nav-item"
            :class="{ 'is-active': activeSection === item.key && !searchQuery.trim() }"
            :aria-current="activeSection === item.key ? 'page' : undefined"
            @click="setActiveSection(item.key)"
          >
            <el-icon class="settings-nav-icon"><component :is="item.icon" /></el-icon>
            <span class="settings-nav-copy">
              <strong>{{ item.title }}</strong>
              <span>{{ item.description }}</span>
            </span>
          </button>
        </nav>
      </aside>

      <div class="settings-main">
        <template v-if="searchQuery.trim()">
          <section class="settings-section">
            <header class="settings-section-head">
              <span class="settings-section-index settings-section-index-icon">
                <el-icon><Search /></el-icon>
              </span>
              <div>
                <h2>搜索结果</h2>
                <p>找到 {{ searchResults.length }} 项与“{{ searchQuery.trim() }}”相关的设置。</p>
              </div>
            </header>

            <div v-if="searchResults.length" class="settings-card settings-search-list">
              <button
                v-for="result in searchResults"
                :key="result.key"
                type="button"
                class="settings-search-result"
                @click="openSearchResult(result)"
              >
                <span class="settings-result-icon">
                  <el-icon><component :is="result.icon" /></el-icon>
                </span>
                <span class="settings-result-copy">
                  <strong>{{ result.title }}</strong>
                  <span>{{ result.subtitle }}</span>
                </span>
                <el-icon class="settings-chevron"><ArrowRight /></el-icon>
              </button>
            </div>
            <div v-else class="settings-empty-card">
              <el-icon><Search /></el-icon>
              <strong>没有找到相关设置</strong>
              <span>换一个关键词，或清空搜索后按分类查看。</span>
              <el-button link type="primary" @click="searchQuery = ''">清空搜索</el-button>
            </div>
          </section>
        </template>

        <template v-else>
          <section v-if="activeSection === 'account'" class="settings-section">
            <header class="settings-section-head">
              <span class="settings-section-index">01</span>
              <div>
                <h2>个人中心</h2>
                <p>查看当前账户与登录状态。</p>
              </div>
            </header>

            <div class="settings-card">
              <div class="settings-profile">
                <span class="settings-avatar">{{ accountInitial }}</span>
                <div class="settings-profile-copy">
                  <strong>{{ accountName }}</strong>
                  <span>{{ accountEmail }}</span>
                </div>
                <span class="settings-account-chip" :class="{ 'is-online': authState.authenticated }">
                  {{ authState.authenticated ? '已登录' : '未登录' }}
                </span>
              </div>

              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>显示名称</strong>
                  <span>来自统一登录服务的账户资料。</span>
                </div>
                <span class="settings-value">{{ authState.authenticated ? accountName : '—' }}</span>
              </div>
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>邮箱</strong>
                  <span>用于识别你的工作台身份。</span>
                </div>
                <span class="settings-value">{{ authState.authenticated ? authState.user?.email || '—' : '—' }}</span>
              </div>
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>账户状态</strong>
                  <span>退出登录不会删除数据集、字段库或规则库。</span>
                </div>
                <el-button
                  v-if="!authState.authenticated"
                  type="primary"
                  :disabled="authState.status === 'checking'"
                  @click="handleLogin"
                >
                  {{ authState.status === 'checking' ? '验证中…' : '登录 / 注册' }}
                </el-button>
                <el-button
                  v-else
                  :icon="SwitchButton"
                  :loading="accountBusy"
                  @click="handleLogout"
                >
                  退出登录
                </el-button>
              </div>
            </div>

          </section>

          <section v-else-if="activeSection === 'tips'" class="settings-section">
            <header class="settings-section-head">
              <span class="settings-section-index">02</span>
              <div>
                <h2>使用提示</h2>
                <p>提示只在你停留并聚焦当前页面时计时，出现后继续操作会自动隐藏。</p>
              </div>
            </header>

            <div class="settings-card">
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>启用柔性提示</strong>
                  <span>关闭后，所有页面都不再出现这类提示。</span>
                </div>
                <el-switch v-model="tipDraft.enabled" />
              </div>
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>默认等待时间</strong>
                  <span>页面停留且没有操作这么久后出现；单条提示可以单独覆盖。</span>
                </div>
                <el-select
                  v-model="tipDraft.idleSeconds"
                  class="settings-select"
                  :disabled="!tipDraft.enabled"
                >
                  <el-option
                    v-for="option in SOFT_TIP_DELAY_OPTIONS"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                  />
                </el-select>
              </div>
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>计时方式</strong>
                  <span>切到其他标签页、窗口失焦或打开弹窗时不会计时。</span>
                </div>
                <span class="settings-value">仅页面可见且聚焦时</span>
              </div>
            </div>

            <div class="settings-subhead">
              <div>
                <h3>按页面管理</h3>
                <p>点击页面打开右侧详情，调整单条提示的开关和等待时间。</p>
              </div>
              <span class="settings-count">{{ enabledTipCount }} / {{ totalTipCount }} 条已启用</span>
            </div>

            <div class="settings-page-grid">
              <article
                v-for="page in SOFT_TIP_PAGES"
                :key="page.key"
                class="settings-page-card"
                :class="{ 'is-page-disabled': !tipDraft.enabled || !tipDraft.pages[page.key].enabled }"
                role="button"
                tabindex="0"
                @click="openTipDrawer(page.key)"
                @keydown.enter.prevent="openTipDrawer(page.key)"
                @keydown.space.prevent="openTipDrawer(page.key)"
              >
                <span class="settings-page-icon">
                  <el-icon><component :is="pageIcons[page.key]" /></el-icon>
                </span>
                <span class="settings-page-copy">
                  <strong>{{ page.title }}</strong>
                  <span>{{ page.description }}</span>
                  <span class="settings-page-meta">
                    {{ enabledTipsInPage(page) }}/{{ page.tips.length }} 条
                    · {{ tipDraft.pages[page.key].enabled ? '页面已启用' : '页面已关闭' }}
                  </span>
                </span>
                <span class="settings-page-controls" @click.stop @keydown.stop>
                  <el-switch
                    v-model="tipDraft.pages[page.key].enabled"
                    size="small"
                    :disabled="!tipDraft.enabled"
                  />
                </span>
                <el-icon class="settings-chevron"><ArrowRight /></el-icon>
              </article>
            </div>

            <div class="settings-inline-actions">
              <el-button link type="primary" :icon="RefreshLeft" @click="resetTipsDefaults">
                恢复默认提示设置
              </el-button>
            </div>
          </section>

          <section v-else-if="activeSection === 'appearance'" class="settings-section">
            <header class="settings-section-head">
              <span class="settings-section-index">03</span>
              <div>
                <h2>外观</h2>
                <p>这些选项会改变整个工作台的呈现方式，保存后立即应用。</p>
              </div>
            </header>

            <div class="settings-card">
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>背景动效</strong>
                  <span>在工作台背景播放流动光波视频；关闭后使用静态渐变。</span>
                </div>
                <el-switch v-model="appearanceDraft.backgroundMotion" />
              </div>
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>身份水印</strong>
                  <span>在背景和页面卡片上显示当前账户的身份水印。</span>
                </div>
                <el-switch v-model="appearanceDraft.watermark" />
              </div>
              <div class="settings-row">
                <div class="settings-row-copy">
                  <strong>侧边栏默认收起</strong>
                  <span>下次进入工作台时以收起状态显示左侧导航栏。</span>
                </div>
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
                      <span
                        v-if="!appearanceDraft.sidebarCollapsed"
                        class="settings-preview-brand-copy"
                      >
                        <i class="is-strong"></i>
                        <i></i>
                      </span>
                    </div>

                    <span
                      v-if="!appearanceDraft.sidebarCollapsed"
                      class="settings-preview-section"
                    >工作区</span>

                    <div class="settings-preview-menu">
                      <span
                        v-for="index in 3"
                        :key="index"
                        class="settings-preview-menu-item"
                        :class="{ 'is-active': index === 1 }"
                      >
                        <i class="settings-preview-menu-icon"></i>
                        <i
                          v-if="!appearanceDraft.sidebarCollapsed"
                          class="settings-preview-menu-label"
                        ></i>
                        <i
                          v-if="!appearanceDraft.sidebarCollapsed && index === 1"
                          class="settings-preview-menu-badge"
                        ></i>
                      </span>
                    </div>

                    <div class="settings-preview-sidebar-footer">
                      <i class="settings-preview-menu-icon"></i>
                      <i
                        v-if="!appearanceDraft.sidebarCollapsed"
                        class="settings-preview-menu-label"
                      ></i>
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
                      <span
                        v-for="index in 3"
                        :key="index"
                        class="settings-preview-row"
                      >
                        <i class="settings-preview-row-icon"></i>
                        <i class="settings-preview-row-text"></i>
                        <i class="settings-preview-row-meta"></i>
                      </span>
                    </div>

                    <span
                      v-if="appearanceDraft.watermark"
                      class="settings-preview-watermark"
                    >SeekDateSet</span>
                  </div>
                </div>
              </div>
              <div class="settings-preview-meta">
                <strong>外观预览</strong>
                <span>背景动效、水印和侧边栏状态会即时反映在这里，保存后应用到工作台。</span>
              </div>
            </div>

            <div class="settings-inline-actions">
              <el-button link type="primary" :icon="RefreshLeft" @click="resetAppearanceDefaults">
                恢复默认外观
              </el-button>
            </div>
          </section>
        </template>

        <footer class="settings-savebar" :class="{ 'is-dirty': dirty }">
          <div class="settings-savebar-state">
            <div>
              <strong>{{ dirty ? '有未保存的修改' : '所有设置已保存' }}</strong>
              <span>保存范围：使用提示与外观 · 全部页面</span>
            </div>
          </div>
          <div class="settings-savebar-actions">
            <el-button :disabled="!dirty" @click="saveAll()">保存</el-button>
            <el-button type="primary" title="保存并返回工作台" @click="confirmAndLeave">确定</el-button>
          </div>
        </footer>
      </div>
    </div>

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
            <el-switch
              v-model="tipDraft.pages[activePage.key].enabled"
              :disabled="!tipDraft.enabled"
            />
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
  </div>
</template>
<style scoped>
.settings-page {
  width: min(var(--page-max-width), 100%);
  margin: 0 auto;
  padding-bottom: 28px;
}

.settings-eyebrow {
  color: #176b57;
  font-size: 10px;
  font-weight: 650;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.settings-hero p {
  max-width: 640px;
}

.settings-hero-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.settings-state-pill {
  display: inline-flex;
  align-items: center;
  padding: 5px 10px;
  color: #176b57;
  font-size: 10.5px;
  font-weight: 600;
  background: rgba(228, 245, 237, 0.9);
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 999px;
}

.settings-state-pill.is-dirty {
  color: #a96818;
  background: #fdf3e4;
  border-color: rgba(211, 143, 44, 0.16);
}

.settings-layout {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 26px;
  align-items: start;
}

.settings-rail {
  position: sticky;
  top: 18px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.64);
  border: 1px solid rgba(255, 255, 255, 0.82);
  border-radius: 18px;
  box-shadow:
    0 14px 38px rgba(10, 45, 37, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(18px);
}

.settings-search :deep(.el-input__wrapper) {
  background: rgba(247, 251, 249, 0.86);
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 11px;
  box-shadow: none;
}

.settings-search :deep(.el-input__wrapper.is-focus) {
  border-color: rgba(23, 107, 87, 0.32);
  box-shadow: 0 0 0 3px rgba(23, 107, 87, 0.08);
}

.settings-search-hint {
  display: block;
  margin: 7px 2px 0;
  color: var(--ink-faint);
  font-size: 10px;
  line-height: 1.5;
}

.settings-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 14px;
}

.settings-nav-item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  color: var(--ink-soft);
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.settings-nav-item:hover {
  background: rgba(23, 107, 87, 0.06);
}

.settings-nav-item.is-active {
  color: #0f5c49;
  background: linear-gradient(135deg, #e4f5ed, #f1faf5);
  box-shadow: inset 0 0 0 1px rgba(23, 107, 87, 0.1);
}

.settings-nav-icon {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  place-items: center;
  color: var(--ink-faint);
  font-size: 15px;
  background: rgba(255, 255, 255, 0.62);
  border-radius: 9px;
}

.settings-nav-item.is-active .settings-nav-icon {
  color: #176b57;
  background: #fff;
}

.settings-nav-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.settings-nav-copy strong {
  font-size: 12px;
  font-weight: 600;
}

.settings-nav-copy span {
  overflow: hidden;
  color: var(--ink-faint);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 18px;
}

.settings-section {
  animation: settingsSectionIn 0.26s ease-out;
}

.settings-section-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}

.settings-section-index {
  display: grid;
  min-width: 34px;
  height: 26px;
  flex: 0 0 auto;
  place-items: center;
  padding: 0 7px;
  color: #176b57;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  background: #e5f5ed;
  border-radius: 8px;
}

.settings-section-index-icon {
  padding: 0;
  font-size: 13px;
}

.settings-section-head h2 {
  margin: 0 0 3px;
  color: var(--ink);
  font-size: 19px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.settings-section-head p {
  margin: 0;
  color: var(--ink-soft);
  font-size: 11.5px;
  line-height: 1.6;
}

.settings-card {
  overflow: hidden;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 16px;
  box-shadow: 0 12px 32px rgba(10, 45, 37, 0.06);
  backdrop-filter: blur(14px);
}

.settings-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 15px 18px;
  border-top: 1px solid rgba(23, 107, 87, 0.08);
}

.settings-row:first-child {
  border-top: 0;
}

.settings-row-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.settings-row-copy strong {
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 600;
}

.settings-row-copy span {
  color: var(--ink-faint);
  font-size: 11px;
  line-height: 1.55;
}

.settings-value {
  max-width: 55%;
  overflow: hidden;
  color: var(--ink-soft);
  font-size: 11.5px;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-chevron {
  flex: 0 0 auto;
  color: var(--ink-faint);
}

.settings-profile {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 18px;
  background: linear-gradient(135deg, rgba(226, 244, 236, 0.92), rgba(247, 252, 249, 0.92));
}

.settings-avatar {
  display: grid;
  width: 46px;
  height: 46px;
  flex: 0 0 46px;
  place-items: center;
  color: #0c5c48;
  font-size: 17px;
  font-weight: 700;
  background: #d5efe3;
  border: 1px solid rgba(23, 107, 87, 0.12);
  border-radius: 50%;
}

.settings-profile-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.settings-profile-copy strong,
.settings-profile-copy span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-profile-copy strong {
  font-size: 14px;
  font-weight: 650;
}

.settings-profile-copy span {
  color: var(--ink-soft);
  font-size: 11px;
}

.settings-account-chip {
  flex: 0 0 auto;
  padding: 4px 9px;
  color: var(--ink-faint);
  font-size: 10px;
  background: rgba(255, 255, 255, 0.72);
  border-radius: 999px;
}

.settings-account-chip.is-online {
  color: #176b57;
  background: #e3f4ec;
}

.settings-select {
  width: 126px;
  flex: 0 0 126px;
}

.settings-subhead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 14px;
  margin: 18px 0 10px;
}

.settings-subhead h3 {
  margin: 0 0 3px;
  color: var(--ink);
  font-size: 14px;
  font-weight: 650;
}

.settings-subhead p {
  margin: 0;
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.55;
}

.settings-count {
  flex: 0 0 auto;
  padding: 4px 9px;
  color: #176b57;
  font-size: 10.5px;
  font-weight: 600;
  background: #e8f6ef;
  border-radius: 999px;
}

.settings-page-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 12px;
}

.settings-page-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  gap: 12px;
  align-items: center;
  padding: 15px;
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 16px;
  box-shadow: 0 8px 24px rgba(10, 45, 37, 0.04);
  cursor: pointer;
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    border-color 0.18s ease;
}

.settings-page-card:hover,
.settings-page-card:focus-visible {
  border-color: rgba(23, 107, 87, 0.24);
  outline: none;
  box-shadow: 0 16px 34px rgba(10, 45, 37, 0.1);
  transform: translateY(-2px);
}

.settings-page-card.is-page-disabled {
  opacity: 0.64;
}

.settings-page-icon {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  color: #176b57;
  font-size: 17px;
  background: #e7f5ee;
  border-radius: 11px;
}

.settings-page-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.settings-page-copy strong {
  color: var(--ink);
  font-size: 13px;
  font-weight: 650;
}

.settings-page-copy > span {
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.45;
}

.settings-page-meta {
  margin-top: 2px;
  color: #176b57 !important;
  font-size: 10px !important;
}

.settings-page-controls {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.settings-inline-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.settings-search-list {
  padding: 0;
}

.settings-search-result {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 11px;
  padding: 14px 18px;
  text-align: left;
  background: transparent;
  border: 0;
  border-top: 1px solid rgba(23, 107, 87, 0.08);
  cursor: pointer;
  transition: background 0.16s ease;
}

.settings-search-result:first-child {
  border-top: 0;
}

.settings-search-result:hover {
  background: rgba(23, 107, 87, 0.05);
}

.settings-result-icon {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  color: #176b57;
  font-size: 14px;
  background: #e8f6ef;
  border-radius: 9px;
}

.settings-result-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.settings-result-copy strong {
  font-size: 12px;
  font-weight: 600;
}

.settings-result-copy span {
  color: var(--ink-faint);
  font-size: 10.5px;
}

.settings-empty-card {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 7px;
  padding: 36px 20px;
  color: var(--ink-faint);
  text-align: center;
  background: rgba(255, 255, 255, 0.72);
  border: 1px dashed rgba(23, 107, 87, 0.16);
  border-radius: 16px;
}

.settings-empty-card > .el-icon {
  color: #7aa998;
  font-size: 22px;
}

.settings-empty-card strong {
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 600;
}

.settings-empty-card span {
  font-size: 11px;
}

.settings-preview {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
  padding: 16px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 16px;
}

.settings-preview-stage {
  position: relative;
  height: 214px;
  padding: 10px;
  overflow: hidden;
  background:
    linear-gradient(130deg, rgba(230, 246, 239, 0.94), rgba(220, 239, 232, 0.94) 46%, rgba(248, 252, 250, 0.97));
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 13px;
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
  background: rgba(23, 107, 87, 0.12);
}

.settings-preview-sidebar.is-collapsed .settings-preview-menu-item {
  justify-content: center;
  padding: 0;
}

.settings-preview-menu-icon {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  background: rgba(23, 107, 87, 0.4);
  border-radius: 2px;
}

.settings-preview-menu-item.is-active .settings-preview-menu-icon {
  background: #176b57;
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
  background: rgba(23, 107, 87, 0.16);
  border-radius: 3px;
}

.settings-preview-sidebar-footer {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: auto;
  padding: 5px 5px 0;
  border-top: 1px solid rgba(23, 107, 87, 0.12);
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
  background: rgba(23, 107, 87, 0.34);
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
  background: rgba(23, 107, 87, 0.72);
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
  border-bottom: 1px solid rgba(23, 107, 87, 0.12);
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
  background: rgba(23, 107, 87, 0.3);
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
  color: rgba(23, 107, 87, 0.22);
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
  color: var(--ink);
  font-size: 12px;
  font-weight: 600;
}

.settings-preview-meta span {
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.5;
}

.settings-savebar {
  position: sticky;
  bottom: 16px;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 2px;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(23, 107, 87, 0.12);
  border-radius: 16px;
  box-shadow: 0 18px 46px rgba(10, 45, 37, 0.14);
  backdrop-filter: blur(18px);
}

.settings-savebar-state {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}

.settings-savebar-state > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.settings-savebar-state strong {
  color: var(--ink);
  font-size: 11.5px;
  font-weight: 600;
}

.settings-savebar-state span {
  color: var(--ink-faint);
  font-size: 10px;
}

.settings-savebar-actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.settings-tip-drawer {
  max-width: 100vw;
}

:global(.settings-tip-drawer) {
  max-width: 100vw;
  background: #f4f8f7;
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
  background: #f4f8f7;
}

.tip-drawer-header {
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 22px 22px 16px;
  border-bottom: 1px solid rgba(23, 107, 87, 0.1);
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
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.6;
}

.tip-drawer-close {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  color: var(--ink-faint);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 9px;
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
  font-size: 10.5px;
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
  border: 1px solid rgba(23, 107, 87, 0.1);
  border-radius: 13px;
}

.tip-drawer-master-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.tip-drawer-master-copy strong {
  font-size: 12px;
  font-weight: 600;
}

.tip-drawer-master-copy span {
  color: var(--ink-faint);
  font-size: 10.5px;
  line-height: 1.5;
}

.tip-drawer-bulk {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 14px 0 4px;
  color: var(--ink-faint);
  font-size: 10.5px;
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
  border-top: 1px dashed rgba(23, 107, 87, 0.1);
  border-radius: 10px;
  transition: background 0.2s ease;
}

.tip-detail-row:first-child {
  border-top: 0;
}

.tip-detail-row.is-highlighted {
  background: rgba(23, 107, 87, 0.08);
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
  color: var(--ink);
  font-size: 12px;
  font-weight: 600;
}

.tip-detail-copy span {
  color: var(--ink-soft);
  font-size: 11px;
  line-height: 1.6;
}

.tip-detail-row.is-disabled .tip-detail-copy {
  opacity: 0.55;
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
  border-top: 1px solid rgba(23, 107, 87, 0.1);
  box-shadow: 0 -10px 24px rgba(15, 52, 43, 0.04);
  backdrop-filter: blur(14px);
}

.tip-drawer-footer span {
  color: var(--ink-faint);
  font-size: 10px;
  line-height: 1.5;
}

@keyframes settingsSectionIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
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
    background: rgba(23, 107, 87, 0.16);
  }

  100% {
    background: rgba(23, 107, 87, 0.08);
  }
}

@media (max-width: 1050px) {
  .settings-layout {
    grid-template-columns: 220px minmax(0, 1fr);
    gap: 18px;
  }

  .settings-page-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 860px) {
  .settings-hero {
    align-items: flex-start;
    flex-direction: column;
  }

  .settings-hero-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .settings-layout {
    grid-template-columns: 1fr;
  }

  .settings-rail {
    position: static;
  }

  .settings-nav {
    flex-direction: row;
    padding-bottom: 2px;
    overflow-x: auto;
  }

  .settings-nav-item {
    min-width: 170px;
  }

}

@media (max-width: 640px) {
  .settings-hero h1 {
    font-size: 26px;
  }

  .settings-nav {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 6px;
    overflow: visible;
  }

  .settings-nav-item {
    position: relative;
    min-width: 0;
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
    padding: 9px 8px;
  }

  .settings-nav-icon {
    width: 24px;
    height: 24px;
    flex-basis: 24px;
    font-size: 13px;
  }

  .settings-nav-copy strong {
    font-size: 11px;
  }

  .settings-nav-copy span {
    display: none;
  }

  .settings-row {
    align-items: flex-start;
    padding: 13px 14px;
  }

  .settings-value {
    max-width: 45%;
  }

  .settings-profile {
    padding: 15px;
  }

  .settings-profile-copy strong,
  .settings-profile-copy span {
    white-space: normal;
  }

  .settings-preview-stage {
    height: 178px;
  }

  .settings-hero-actions {
    justify-content: flex-start;
  }

  .settings-savebar {
    position: sticky;
    bottom: calc(92px + env(safe-area-inset-bottom));
    align-items: stretch;
    flex-direction: column;
  }

  .settings-savebar-actions {
    justify-content: flex-end;
  }

  .tip-detail-row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .tip-detail-delay {
    grid-column: 2;
    justify-self: start;
  }

  .tip-drawer-header {
    padding: 18px 18px 14px;
  }

  .tip-drawer-scroll {
    padding: 14px 18px 18px;
  }

  .tip-drawer-footer {
    padding: 12px 18px 16px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .settings-section,
  .settings-preview-orb,
  .tip-detail-row.is-highlighted {
    animation: none;
  }

  .settings-preview-sidebar,
  .settings-preview-topbar,
  .settings-preview-card,
  .settings-page-card {
    transition: none;
  }
}
</style>
