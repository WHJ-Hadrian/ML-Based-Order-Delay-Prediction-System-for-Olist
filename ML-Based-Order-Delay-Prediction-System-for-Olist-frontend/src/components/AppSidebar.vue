<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import liquidGL from 'liquid-gl'
import { DataAnalysis, Expand, Fold } from '@element-plus/icons-vue'
import { NAVIGATION_ITEMS, findNavigationItemByPath } from '@/config/navigation'

const props = defineProps({
  collapsed: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
})

const emit = defineEmits(['update:collapsed'])
const route = useRoute()
const router = useRouter()

let glassEffect = null
let glassInitTimer = null
let removeLoadListener = null

const glassOptions = {
  target: '.sidebar-liquid-lens',
  snapshot: 'body',
  resolution: 2,
  zIndex: 2,
  refraction: 0.045,
  aberration: 0.1,
  bevelDepth: 0.12,
  bevelWidth: 0.13,
  frost: 0,
  shadow: true,
  specular: true,
  reveal: 'none',
  tilt: false,
  magnify: 1.18,
  tint: 'rgba(255, 255, 255, 0.01)',
}

const active = computed(() => route.meta.navigationKey || findNavigationItemByPath(route.path).key)

const activeItemOffset = computed(() => {
  const index = Math.max(NAVIGATION_ITEMS.findIndex((item) => item.key === active.value), 0)
  return { top: `${5 + index * 56}px` }
})

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
  if (glassEffect || !document.querySelector('.sidebar-liquid-lens')) {
    return
  }
  await waitForBackdrop()
  if (!glassEffect && document.querySelector('.sidebar-liquid-lens')) {
    try {
      glassEffect = liquidGL(glassOptions)
    } catch {
      glassEffect = null
    }
  }
}

onMounted(() => {
  nextTick(() => {
    const start = () => {
      glassInitTimer = window.setTimeout(initLiquidGlass, 180)
    }
    if (document.readyState === 'complete') {
      start()
      return
    }
    window.addEventListener('load', start, { once: true })
    removeLoadListener = () => window.removeEventListener('load', start)
  })
})

onBeforeUnmount(() => {
  if (glassInitTimer) {
    window.clearTimeout(glassInitTimer)
  }
  removeLoadListener?.()
  glassEffect?.destroy?.()
  glassEffect = null
})

watch(
  () => [props.total, active.value],
  () => nextTick(() => glassEffect?.renderer?.captureSnapshot?.()),
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
        <el-icon><DataAnalysis /></el-icon>
      </div>
      <div v-if="!collapsed" class="brand-copy">
        <strong>Olist Data</strong>
        <span>数据资产工作台</span>
      </div>
    </div>

    <div v-if="!collapsed" class="sidebar-section">工作区</div>

    <el-menu
      class="sidebar-menu"
      :default-active="active"
      :collapse="collapsed"
      :collapse-transition="false"
      @select="selectMenu"
    >
      <el-menu-item
        v-for="item in NAVIGATION_ITEMS"
        :key="item.key"
        :index="item.key"
        :class="{ 'is-active': active === item.key }"
      >
        <el-icon data-liquid-ignore><component :is="item.icon" /></el-icon>
        <template #title>
          <span class="menu-title" data-liquid-ignore>{{ item.title }}</span>
          <span
            v-if="!collapsed && item.showTotal"
            class="menu-badge"
            data-liquid-ignore
          >{{ total }}</span>
        </template>
      </el-menu-item>

      <span
        class="sidebar-liquid-lens"
        aria-hidden="true"
        data-liquid-ignore
        :style="activeItemOffset"
      ></span>
      <span
        class="sidebar-liquid-sheen"
        aria-hidden="true"
        data-liquid-ignore
        :style="activeItemOffset"
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
