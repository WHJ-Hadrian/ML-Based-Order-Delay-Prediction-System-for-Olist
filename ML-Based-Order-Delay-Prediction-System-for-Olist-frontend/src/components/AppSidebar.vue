<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import liquidGL from 'liquid-gl'
import { Expand, Fold } from '@element-plus/icons-vue'
import brandArtwork from '@/assets/img/chahua.png'
import { NAVIGATION_ITEMS, findNavigationItemByPath } from '@/config/navigation'

const props = defineProps({
  collapsed: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
})

const emit = defineEmits(['update:collapsed'])
const route = useRoute()
const router = useRouter()
const isMobile = ref(false)
let mobileQuery = null
const menuRef = ref(null)
const indicatorStyle = ref({})

let indicatorFrame = 0
let itemResizeObserver = null
let previousActiveIndex = -1
let glassEffect = null
let glassInitTimer = null
let snapshotTimer = null

const glassOptions = {
  target: '.sidebar-active-indicator',
  snapshot: 'body',
  resolution: 2,
  zIndex: 2,
  refraction: 0.04,
  aberration: 0,
  bevelDepth: 0,
  bevelWidth: 0.22,
  frost: 0,
  shadow: true,
  specular: false,
  reveal: 'none',
  tilt: false,
  magnify: 1,
  tint: 'rgba(24, 90, 74, 0.1)',
}

const active = computed(() => route.meta.navigationKey || findNavigationItemByPath(route.path).key)
const menuCollapsed = computed(() => props.collapsed && !isMobile.value)

function updateMobileState(event) {
  isMobile.value = event.matches
}

function resolveMenuElement() {
  const value = menuRef.value
  if (value instanceof HTMLElement) return value
  return value?.$el instanceof HTMLElement ? value.$el : null
}

async function waitForBackdrop() {
  const media = document.querySelector('.app-backdrop-media')
  if (media instanceof HTMLImageElement && !media.complete) {
    await media.decode().catch(() => {})
  }
  if (media instanceof HTMLVideoElement && media.readyState < 2) {
    await new Promise((resolve) => {
      const finish = () => {
        window.clearTimeout(timeout)
        resolve()
      }
      const timeout = window.setTimeout(finish, 1500)
      media.addEventListener('canplay', finish, { once: true })
      media.addEventListener('error', finish, { once: true })
    })
  }
  await new Promise((resolve) => {
    window.requestAnimationFrame(() => window.requestAnimationFrame(resolve))
  })
}

async function initLiquidGlass() {
  if (glassEffect || !document.querySelector('.sidebar-active-indicator')) return

  await waitForBackdrop()
  if (glassEffect || !document.querySelector('.sidebar-active-indicator')) return

  try {
    glassEffect = liquidGL(glassOptions)
  } catch {
    glassEffect = null
  }
}

function scheduleGlassInit() {
  if (glassInitTimer) window.clearTimeout(glassInitTimer)
  glassInitTimer = window.setTimeout(initLiquidGlass, 180)
}

function scheduleSnapshotRefresh() {
  if (!glassEffect || snapshotTimer) return
  snapshotTimer = window.setTimeout(() => {
    snapshotTimer = null
    glassEffect?.renderer?.captureSnapshot?.()
  }, 320)
}

function updateIndicator() {
  indicatorFrame = 0
  const menu = resolveMenuElement()
  if (!menu) return

  const item = [...menu.querySelectorAll('.el-menu-item')]
    .find((entry) => entry.dataset.navigationKey === active.value)
  if (!(item instanceof HTMLElement)) return

  const nextIndex = Math.max(
    NAVIGATION_ITEMS.findIndex((entry) => entry.key === active.value),
    0,
  )
  const distance = previousActiveIndex < 0
    ? 0
    : Math.abs(nextIndex - previousActiveIndex)
  const duration = distance === 0
    ? 0
    : 330 + Math.max(0, distance - 1) * 105
  previousActiveIndex = nextIndex

  indicatorStyle.value = {
    width: `${item.offsetWidth}px`,
    height: `${item.offsetHeight}px`,
    borderRadius: getComputedStyle(item).borderRadius,
    transform: `translate3d(${item.offsetLeft}px, ${item.offsetTop}px, 0)`,
    '--indicator-duration': `${duration}ms`,
  }
}

function scheduleIndicatorUpdate() {
  if (indicatorFrame) {
    window.cancelAnimationFrame(indicatorFrame)
  }
  indicatorFrame = window.requestAnimationFrame(updateIndicator)
}

function observeMenu() {
  itemResizeObserver?.disconnect()
  itemResizeObserver = null

  const menu = resolveMenuElement()
  if (!menu || typeof ResizeObserver === 'undefined') {
    scheduleIndicatorUpdate()
    return
  }

  itemResizeObserver = new ResizeObserver(scheduleIndicatorUpdate)
  itemResizeObserver.observe(menu)
  menu.querySelectorAll('.el-menu-item').forEach((item) => itemResizeObserver.observe(item))
  scheduleIndicatorUpdate()
}

onMounted(() => {
  mobileQuery = window.matchMedia('(max-width: 760px)')
  isMobile.value = mobileQuery.matches
  mobileQuery.addEventListener('change', updateMobileState)
  nextTick(observeMenu)
  nextTick(scheduleGlassInit)
})

onBeforeUnmount(() => {
  mobileQuery?.removeEventListener('change', updateMobileState)
  if (glassInitTimer) window.clearTimeout(glassInitTimer)
  if (snapshotTimer) window.clearTimeout(snapshotTimer)
  if (indicatorFrame) {
    window.cancelAnimationFrame(indicatorFrame)
  }
  itemResizeObserver?.disconnect()
  glassEffect?.destroy?.()
  glassEffect = null
})

watch(active, () => nextTick(scheduleIndicatorUpdate), { flush: 'post' })

watch(() => props.total, scheduleSnapshotRefresh)

watch(
  [() => props.collapsed, isMobile],
  () => {
    nextTick(observeMenu)
    scheduleSnapshotRefresh()
  },
  { flush: 'post' },
)

function selectMenu(index) {
  const item = NAVIGATION_ITEMS.find((entry) => entry.key === index)
  if (item && route.path !== item.path) {
    router.push(item.path)
  }
}

function toggle() {
  emit('update:collapsed', !props.collapsed)
}
</script>

<template>
  <aside class="app-sidebar" :class="{ 'is-collapsed': collapsed }">
    <div class="sidebar-brand" @click="toggle">
      <div class="brand-mark">
        <img :src="brandArtwork" alt="SeekDateSet">
      </div>
      <div v-if="!collapsed" class="brand-copy">
        <strong>SeekDateSet</strong>
        <span>数据分析工作台</span>
      </div>
    </div>

    <div v-if="!collapsed" class="sidebar-section">工作区</div>

    <el-menu
      ref="menuRef"
      class="sidebar-menu"
      :default-active="active"
      :collapse="menuCollapsed"
      :collapse-transition="false"
      @select="selectMenu"
    >
      <el-menu-item
        v-for="item in NAVIGATION_ITEMS"
        :key="item.key"
        :index="item.key"
        :data-navigation-key="item.key"
        data-liquid-ignore
        :class="{ 'is-active': active === item.key }"
      >
        <el-icon><component :is="item.icon" /></el-icon>
        <template #title>
          <span class="menu-title">{{ item.title }}</span>
          <span
            v-if="!collapsed && item.showTotal"
            class="menu-badge"
          >{{ total }}</span>
        </template>
      </el-menu-item>

      <span
        class="sidebar-active-indicator"
        aria-hidden="true"
        :style="indicatorStyle"
      ></span>
    </el-menu>

    <div class="sidebar-footer">
      <button
        type="button"
        class="collapse-toggle"
        :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
        :title="collapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="toggle"
      >
        <el-icon>
          <Expand v-if="collapsed" />
          <Fold v-else />
        </el-icon>
        <span v-if="!collapsed">收起侧边栏</span>
      </button>
    </div>
  </aside>
</template>
