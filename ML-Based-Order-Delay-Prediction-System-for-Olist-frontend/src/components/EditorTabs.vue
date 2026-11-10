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
    <div
      v-for="document in documents"
      :key="document.path"
      class="editor-tab-shell"
      role="presentation"
      :class="{ 'is-active': route.path === document.path }"
    >
      <button
        type="button"
        class="editor-tab"
        role="tab"
        :aria-current="route.path === document.path ? 'page' : undefined"
        :aria-selected="route.path === document.path"
        :title="document.title"
        @click="activate(document.path)"
      >
        <el-icon><component :is="document.icon" /></el-icon>
        <span>{{ document.title }}</span>
      </button>
      <button type="button" class="tab-close" :aria-label="`关闭 ${document.title}`" :title="`关闭 ${document.title}`" @click="close($event, document.path)"><el-icon><Close /></el-icon></button>
    </div>
  </nav>
</template>

<style scoped>
.editor-tabs { display: flex; min-width: 0; height: 32px; flex: 0 0 32px; align-items: stretch; overflow-x: auto; overflow-y: hidden; padding: 0; background: #f3f4f5; border-bottom: 1px solid #e3e5e8; scrollbar-width: none; }
.editor-tabs::-webkit-scrollbar { display: none; }
.editor-tab-shell { position: relative; display: flex; width: max-content; max-width: 240px; flex: 0 0 auto; align-items: center; border-right: 1px solid transparent; }
.editor-tab-shell::before { position: absolute; top: 0; right: 0; left: 0; height: 2px; background: transparent; content: ''; }
.editor-tab-shell:hover { background: #ebedef; }
.editor-tab-shell.is-active { background: #fff; border-right-color: #e3e5e8; }
.editor-tab-shell.is-active::before { background: #0f6cbd; }
.editor-tab { display: flex; min-width: 0; height: 31px; flex: 0 1 auto; align-items: center; gap: 7px; padding: 0 0 0 9px; overflow: hidden; color: #50545a; font-size: 12px; text-align: left; background: transparent; border: 0; cursor: pointer; }
.editor-tab-shell.is-active .editor-tab { color: #242424; }
.editor-tab > .el-icon:first-child { flex: 0 0 auto; color: #70777e; font-size: 14px; }
.editor-tab-shell.is-active .editor-tab > .el-icon:first-child { color: #0f6cbd; }
.editor-tab > span { min-width: 0; max-width: 180px; flex: 0 1 auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tab-close { display: grid; width: 22px; height: 22px; flex: 0 0 22px; place-items: center; margin: 0 4px 0 2px; color: #70777e; font-size: 12px; background: transparent; border: 0; border-radius: 4px; opacity: 0; cursor: pointer; }
.editor-tab-shell:hover .tab-close, .editor-tab-shell.is-active .tab-close, .tab-close:focus-visible { opacity: 1; }
.tab-close:hover { color: #344a40; background: #e8eeeb; }
@media (max-width: 760px) { .editor-tab-shell { max-width: 210px; } .editor-tab > span { max-width: 150px; } .tab-close { opacity: 1; } }
</style>
