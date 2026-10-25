<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import liquidGL from 'liquid-gl'
import {
  Collection,
  DataAnalysis,
  Expand,
  Fold,
  Grid,
  SetUp,
} from '@element-plus/icons-vue'

const props = defineProps({
  active: { type: String, default: 'datasets' },
  collapsed: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
})

const emit = defineEmits(['update:active', 'update:collapsed'])

let glassEffect = null
let glassInitTimer = null

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

const activeItemOffset = computed(() => ({
  top: `${5 + ['datasets', 'fields', 'rules'].indexOf(props.active) * 56}px`,
}))

async function waitForBackdrop() {
  const image = document.querySelector('.app-backdrop-media')
  if (image instanceof HTMLImageElement && !image.complete) {
    await image.decode().catch(() => {})
  }
  if (image instanceof HTMLVideoElement && image.readyState < 2) {
    await new Promise((resolve) => image.addEventListener('canplay', resolve, { once: true }))
  }
  await new Promise((resolve) => {
    window.requestAnimationFrame(() => window.requestAnimationFrame(resolve))
  })
}

async function initLiquidGlass() {
  if (glassEffect) {
    return
  }
  const target = document.querySelector('.sidebar-liquid-lens')
  if (!target) {
    return
  }
  await waitForBackdrop()
  if (glassEffect) {
    return
  }
  glassEffect = liquidGL(glassOptions)
}

onMounted(() => {
  nextTick(() => {
    const start = () => {
      glassInitTimer = window.setTimeout(initLiquidGlass, 180)
    }
    if (document.readyState === 'complete') {
      start()
    } else {
      window.addEventListener('load', start, { once: true })
    }
  })
})

onBeforeUnmount(() => {
  if (glassInitTimer) {
    window.clearTimeout(glassInitTimer)
  }
  glassEffect?.destroy?.()
  glassEffect = null
})

watch(
  () => props.total,
  () => {
    nextTick(() => {
      glassEffect?.renderer?.captureSnapshot?.()
    })
  },
)

watch(
  () => props.active,
  () => {
    nextTick(() => glassEffect?.renderer?.captureSnapshot?.())
  },
)

function selectMenu(index) {
  emit('update:active', index)
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
        index="datasets"
        :class="{ 'is-active': active === 'datasets' }"
      >
        <el-icon data-liquid-ignore><Collection /></el-icon>
        <template #title>
          <span class="menu-title" data-liquid-ignore>数据集库</span>
          <span v-if="!collapsed" class="menu-badge" data-liquid-ignore>{{ total }}</span>
        </template>
      </el-menu-item>

      <el-menu-item
        index="fields"
        :class="{ 'is-active': active === 'fields' }"
      >
        <el-icon data-liquid-ignore><Grid /></el-icon>
        <template #title>
          <span class="menu-title" data-liquid-ignore>字段库</span>
        </template>
      </el-menu-item>

      <el-menu-item
        index="rules"
        :class="{ 'is-active': active === 'rules' }"
      >
        <el-icon data-liquid-ignore><SetUp /></el-icon>
        <template #title>
          <span class="menu-title" data-liquid-ignore>规则库</span>
        </template>
      </el-menu-item>

      <span
        class="sidebar-liquid-lens"
        aria-hidden="true"
        data-liquid-ignore
        :style="activeItemOffset"
      ></span>
      <span class="sidebar-liquid-sheen" aria-hidden="true" data-liquid-ignore :style="activeItemOffset"></span>
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
