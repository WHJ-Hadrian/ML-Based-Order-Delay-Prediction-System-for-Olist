<script setup>
import { ref } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AppSidebar from '@/components/AppSidebar.vue'
import liquidGlassBackdrop from '@/assets/videos/liquid-wave.mp4'
import { provideWorkspaceState } from '@/state/workspace'

const sidebarCollapsed = ref(false)
const locale = zhCn
const { datasetTotal } = provideWorkspaceState()
</script>

<template>
  <el-config-provider :locale="locale">
    <div class="app-frame">
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
        <RouterView v-slot="{ Component }">
          <Transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </el-config-provider>
</template>
