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
const sidebarRef = ref(null)
const indicatorStyle = ref({})

const SPRING_STIFFNESS = 220
const SPRING_DAMPING = 16
const SPRING_MAX_STRETCH = 0.05

const reducedMotionQuery = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-reduced-motion: reduce)')
  : null

let indicatorFrame = 0
let itemResizeObserver = null
let previousActiveIndex = -1
let indicatorNode = null
let springFrame = 0
let springLastTime = 0
let indicatorX = 0
let indicatorY = 0
let indicatorVelocityX = 0
let indicatorVelocityY = 0
let indicatorTargetX = 0
let indicatorTargetY = 0
let glassEffect = null
let glassTarget = null
let glassInitTimer = null
let glassObserver = null
let glassInitToken = 0
let glassRetryCount = 0
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

const active = computed(() => {
  if (route.meta.hideNavigationActive) {
    return ''
  }
  return route.meta.navigationKey || findNavigationItemByPath(route.path).key
})
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

function currentIndicator() {
  return document.querySelector('.sidebar-active-indicator')
}

function destroyGlass() {
  const effect = glassEffect
  glassEffect = null
  glassTarget = null
  try {
    effect?.destroy?.()
  } catch {
    // Ignore teardown errors from the renderer.
  }
}

async function initLiquidGlass() {
  const indicator = currentIndicator()
  if (!indicator) return
  if (glassEffect && glassTarget === indicator) return

  const token = ++glassInitToken
  destroyGlass()

  await waitForBackdrop()
  if (token !== glassInitToken) return

  const ready = currentIndicator()
  if (!ready) return
  if (ready !== indicator) {
    scheduleGlassInit(80)
    return
  }

  try {
    glassEffect = liquidGL(glassOptions)
    glassTarget = ready
    glassRetryCount = 0
  } catch {
    glassEffect = null
    glassTarget = null
    if (glassRetryCount < 3) {
      glassRetryCount += 1
      scheduleGlassInit(1200)
    }
  }
}

function scheduleGlassInit(delay = 180) {
  if (glassInitTimer) window.clearTimeout(glassInitTimer)
  glassInitTimer = window.setTimeout(() => {
    glassInitTimer = null
    initLiquidGlass()
  }, delay)
}

// Element Plus rebuilds the menu (and the active indicator) when the sidebar
// collapses, which leaves the glass lens bound to a detached node.
function syncGlassBinding(delay = 120) {
  const indicator = currentIndicator()
  if (!indicator) return
  if (glassEffect && glassTarget === indicator) return
  scheduleGlassInit(delay)
}

function observeGlassTarget() {
  glassObserver?.disconnect()
  glassObserver = null
  const root = sidebarRef.value
  if (!root || typeof MutationObserver === 'undefined') return

  glassObserver = new MutationObserver((records) => {
    if (!records.some((record) => record.type === 'childList')) return
    const indicator = currentIndicator()
    if (!indicator) return
    if (glassEffect && glassTarget === indicator) return
    syncGlassBinding(140)
  })
  glassObserver.observe(root, { childList: true, subtree: true })
}

function scheduleSnapshotRefresh() {
  if (!glassEffect || snapshotTimer) return
  snapshotTimer = window.setTimeout(() => {
    snapshotTimer = null
    glassEffect?.renderer?.captureSnapshot?.()
  }, 320)
}

function indicatorTransform(x, y, velocityX, velocityY) {
  const speed = Math.abs(velocityX) + Math.abs(velocityY)
  const stretch = Math.min(SPRING_MAX_STRETCH, speed * 0.000075)
  const horizontalShare = Math.abs(velocityX) / (speed + 0.0001)
  const stretchX = stretch * horizontalShare
  const stretchY = stretch * (1 - horizontalShare)
  const scaleX = 1 + stretchX - stretchY * 0.4
  const scaleY = 1 + stretchY - stretchX * 0.4
  return `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0) scale(${scaleX.toFixed(4)}, ${scaleY.toFixed(4)})`
}

function writeIndicatorTransform(x, y, velocityX, velocityY) {
  indicatorStyle.value = {
    ...indicatorStyle.value,
    transform: indicatorTransform(x, y, velocityX, velocityY),
  }
}

function stopIndicatorSpring() {
  if (springFrame) {
    window.cancelAnimationFrame(springFrame)
    springFrame = 0
  }
  springLastTime = 0
}

function placeIndicator(x, y) {
  stopIndicatorSpring()
  indicatorTargetX = x
  indicatorTargetY = y
  indicatorX = x
  indicatorY = y
  indicatorVelocityX = 0
  indicatorVelocityY = 0
  writeIndicatorTransform(x, y, 0, 0)
}

function runIndicatorSpring(time) {
  springFrame = 0
  if (!springLastTime) springLastTime = time
  const elapsed = Math.min(0.064, Math.max(0.001, (time - springLastTime) / 1000))
  springLastTime = time

  const steps = Math.max(1, Math.ceil(elapsed / 0.016))
  const step = elapsed / steps
  for (let index = 0; index < steps; index += 1) {
    const accelerationX =
      -SPRING_STIFFNESS * (indicatorX - indicatorTargetX) - SPRING_DAMPING * indicatorVelocityX
    const accelerationY =
      -SPRING_STIFFNESS * (indicatorY - indicatorTargetY) - SPRING_DAMPING * indicatorVelocityY
    indicatorVelocityX += accelerationX * step
    indicatorVelocityY += accelerationY * step
    indicatorX += indicatorVelocityX * step
    indicatorY += indicatorVelocityY * step
  }

  const resting =
    Math.abs(indicatorX - indicatorTargetX) < 0.25 &&
    Math.abs(indicatorY - indicatorTargetY) < 0.25 &&
    Math.abs(indicatorVelocityX) < 4 &&
    Math.abs(indicatorVelocityY) < 4

  if (resting) {
    indicatorX = indicatorTargetX
    indicatorY = indicatorTargetY
    indicatorVelocityX = 0
    indicatorVelocityY = 0
    springLastTime = 0
    writeIndicatorTransform(indicatorX, indicatorY, 0, 0)
    return
  }

  writeIndicatorTransform(indicatorX, indicatorY, indicatorVelocityX, indicatorVelocityY)
  springFrame = window.requestAnimationFrame(runIndicatorSpring)
}

function springIndicatorTo(x, y) {
  indicatorTargetX = x
  indicatorTargetY = y
  if (reducedMotionQuery?.matches) {
    placeIndicator(x, y)
    return
  }
  if (!springFrame) {
    springLastTime = 0
    springFrame = window.requestAnimationFrame(runIndicatorSpring)
  }
}

function updateIndicator() {
  indicatorFrame = 0
  const menu = resolveMenuElement()
  if (!menu) return

  const item = [...menu.querySelectorAll('.el-menu-item')]
    .find((entry) => entry.dataset.navigationKey === active.value)
  if (!(item instanceof HTMLElement)) {
    previousActiveIndex = -1
    indicatorNode = null
    stopIndicatorSpring()
    indicatorStyle.value = {
      width: '0px',
      height: '0px',
      opacity: 0,
      transform: 'translate3d(0, 0, 0)',
    }
    return
  }

  const node = document.querySelector('.sidebar-active-indicator')
  const nodeChanged = node !== indicatorNode
  indicatorNode = node

  const nextIndex = Math.max(
    NAVIGATION_ITEMS.findIndex((entry) => entry.key === active.value),
    0,
  )
  const shouldPlaceImmediately = previousActiveIndex < 0 || nodeChanged
  previousActiveIndex = nextIndex

  indicatorStyle.value = {
    ...indicatorStyle.value,
    width: `${item.offsetWidth}px`,
    height: `${item.offsetHeight}px`,
    borderRadius: getComputedStyle(item).borderRadius,
    opacity: 1,
  }

  if (shouldPlaceImmediately) {
    placeIndicator(item.offsetLeft, item.offsetTop)
  } else {
    springIndicatorTo(item.offsetLeft, item.offsetTop)
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

function handleVisibilityChange() {
  if (document.visibilityState !== 'visible') return
  syncGlassBinding(0)
  scheduleSnapshotRefresh()
}

onMounted(() => {
  mobileQuery = window.matchMedia('(max-width: 760px)')
  isMobile.value = mobileQuery.matches
  mobileQuery.addEventListener('change', updateMobileState)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  nextTick(() => {
    observeMenu()
    observeGlassTarget()
    scheduleGlassInit()
  })
})

onBeforeUnmount(() => {
  mobileQuery?.removeEventListener('change', updateMobileState)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  if (glassInitTimer) window.clearTimeout(glassInitTimer)
  if (snapshotTimer) window.clearTimeout(snapshotTimer)
  if (indicatorFrame) {
    window.cancelAnimationFrame(indicatorFrame)
  }
  stopIndicatorSpring()
  itemResizeObserver?.disconnect()
  glassObserver?.disconnect()
  glassObserver = null
  glassInitToken += 1
  destroyGlass()
})

watch(active, () => nextTick(() => {
  scheduleIndicatorUpdate()
  syncGlassBinding(120)
}), { flush: 'post' })

watch(() => props.total, scheduleSnapshotRefresh)

watch(
  [() => props.collapsed, isMobile],
  () => {
    nextTick(() => {
      observeMenu()
      syncGlassBinding(160)
      scheduleSnapshotRefresh()
    })
  },
  { flush: 'post' },
)

function selectMenu(index) {
  const item = NAVIGATION_ITEMS.find((entry) => entry.key === index)
  if (!item || route.path === item.path) return

  // Start the spring on press so the highlight reacts immediately instead of
  // waiting for the route change to settle.
  const menu = resolveMenuElement()
  const target = menu?.querySelector(`.el-menu-item[data-navigation-key="${index}"]`)
  if (target instanceof HTMLElement) {
    springIndicatorTo(target.offsetLeft, target.offsetTop)
  }

  router.push(item.path)
}

function toggle() {
  emit('update:collapsed', !props.collapsed)
}
</script>

<template>
  <aside ref="sidebarRef" class="app-sidebar" :class="{ 'is-collapsed': collapsed }">
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
