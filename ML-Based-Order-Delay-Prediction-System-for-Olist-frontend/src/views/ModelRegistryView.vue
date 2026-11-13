<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listModelVersions } from '@/api/models'
import { useAuth } from '@/auth/keycloak'

const router = useRouter()
const auth = useAuth()
const models = ref([])
const loading = ref(false)
const error = ref('')

function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

async function loadModels() {
  if (!auth.state.authenticated) return
  loading.value = true
  error.value = ''
  try { models.value = await listModelVersions() || [] }
  catch (cause) { error.value = cause.message || '模型版本载入失败' }
  finally { loading.value = false }
}

onMounted(loadModels)
watch(() => auth.state.status, (status) => {
  if (status === 'authenticated') loadModels()
  if (status === 'anonymous') models.value = []
})
</script>

<template>
  <main class="registry-page">
    <h1 class="visually-hidden">模型版本</h1>
    <header class="registry-heading page-actions">
      <el-button :loading="loading" @click="loadModels">刷新</el-button>
    </header>
    <div v-if="error" class="registry-error" role="alert"><span>{{ error }}</span><el-button text @click="loadModels">重试</el-button></div>
    <div v-else-if="loading" class="registry-loading">正在读取模型版本…</div>
    <el-table v-else :data="models" row-key="id" table-layout="fixed">
      <el-table-column label="模型版本" min-width="210">
        <template #default="{ row }"><div class="registry-model"><strong>{{ row.name || '未命名模型' }}</strong><span>{{ row.task === 'classification' ? '分类' : '回归' }} · {{ row.algorithm }}</span></div></template>
      </el-table-column>
      <el-table-column label="数据来源" min-width="190">
        <template #default="{ row }"><div class="registry-model"><span>{{ row.datasetId }}</span><small>版本 {{ row.datasetVersionNo }} · 表 {{ row.tableId }}</small></div></template>
      </el-table-column>
      <el-table-column label="指标" min-width="190">
        <template #default="{ row }"><div class="registry-metrics"><span v-for="(value, key) in row.metrics" :key="key">{{ key }} {{ Number(value).toFixed(3) }}</span></div></template>
      </el-table-column>
      <el-table-column prop="targetColumn" label="目标字段" min-width="120" show-overflow-tooltip />
      <el-table-column label="创建时间" width="180"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="176" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push({ path: '/evaluation', query: { modelId: row.id } })">评估/预测</el-button>
          <el-button v-if="row.task === 'classification'" link type="primary" @click="router.push({ path: '/prediction', query: { modelId: row.id } })">单笔预测</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!loading && !error && !models.length" class="registry-empty">暂无模型版本 <el-button link type="primary" @click="router.push('/experiments')">新建实验</el-button></div>
  </main>
</template>

<style scoped>
.registry-page { width: min(100%, 1440px); min-width: 0; margin: 0 auto; }
.registry-heading { display: flex; min-height: 42px; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; border-bottom: 1px solid var(--line); }
.registry-heading h1 { margin: 0; font-size: 18px; font-weight: 650; }
.registry-error { display: flex; align-items: center; gap: 8px; padding: 9px 11px; color: #9a4139; background: #fff4f0; border: 1px solid #f1d2c8; font-size: 11px; }
.registry-error span { flex: 1; }
.registry-loading { min-height: 60px; padding: 16px 0; color: #626a70; font-size: 11px; border: 0; border-top: 1px solid var(--line); }
.registry-model { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.registry-model strong, .registry-model span, .registry-model small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.registry-model strong { color: #303840; font-size: 11px; font-weight: 620; }
.registry-model span, .registry-model small { color: #747c82; font-size: 10px; }
.registry-metrics { display: flex; min-width: 0; flex-wrap: wrap; gap: 4px 10px; color: #50545a; font-size: 10px; }
.registry-empty { display: flex; min-height: 80px; align-items: center; gap: 8px; padding: 0; color: #626a70; font-size: 11px; border: 0; border-top: 1px solid var(--line); }
</style>
