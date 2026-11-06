<script setup>
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Close } from '@element-plus/icons-vue'
import { useWorkbench } from '@/state/workbench'

const route = useRoute()
const router = useRouter()
const { documents, openDocument, closeDocument } = useWorkbench()

watch(() => route.path, (path) => openDocument(path), { immediate: true })

function activate(path) {
  if (path !== route.path) router.push(path)
}

function close(event, path) {
  event.stopPropagation()
  const nextPath = closeDocument(path, route.path)
  if (nextPath !== route.path) router.push(nextPath)
}
</script>

<template>
  <nav class="editor-tabs" role="tablist" aria-label="打开的工作区文档">
    <button
      v-for="document in documents"
      :key="document.path"
      type="button"
      class="editor-tab"
      :class="{ 'is-active': route.path === document.path }"
      role="tab"
      :aria-current="route.path === document.path ? 'page' : undefined"
      :aria-selected="route.path === document.path"
      :title="document.title"
      @click="activate(document.path)"
    >
      <el-icon><component :is="document.icon" /></el-icon>
      <span>{{ document.title }}</span>
      <span class="tab-close" :aria-label="`关闭 ${document.title}`" role="button" tabindex="0" @click="close($event, document.path)" @keydown.enter.prevent="close($event, document.path)"><el-icon><Close /></el-icon></span>
    </button>
  </nav>
</template>

<style scoped>
.editor-tabs { display: flex; min-width: 0; height: 38px; flex: 0 0 38px; align-items: stretch; overflow-x: auto; overflow-y: hidden; background: #f1f4f2; border-bottom: 1px solid #dce3df; scrollbar-width: none; }
.editor-tabs::-webkit-scrollbar { display: none; }
.editor-tab { position: relative; display: flex; min-width: 132px; max-width: 210px; align-items: center; gap: 7px; padding: 0 9px 0 12px; overflow: hidden; color: #637169; font-size: 11px; background: #edf1ef; border: 0; border-right: 1px solid #dce3df; cursor: pointer; }
.editor-tab::after { position: absolute; right: 0; bottom: 0; left: 0; height: 2px; background: transparent; content: ''; }
.editor-tab:hover { color: #29483d; background: #f7f9f8; }
.editor-tab.is-active { color: #193d31; background: #fff; }
.editor-tab.is-active::after { background: #28775f; }
.editor-tab > .el-icon:first-child { flex: 0 0 auto; font-size: 13px; }
.editor-tab > span { min-width: 0; flex: 1; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.tab-close { flex: 0 0 auto; padding: 2px; color: #85918b; font-size: 11px; border-radius: 3px; }
.tab-close:hover { color: #32483f; background: #e2e8e4; }
</style>
