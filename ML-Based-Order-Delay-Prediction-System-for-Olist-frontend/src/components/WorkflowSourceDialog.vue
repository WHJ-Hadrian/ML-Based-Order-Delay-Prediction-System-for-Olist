<script setup>
import { computed, ref, watch } from 'vue'
import { Plus, Share, Refresh } from '@element-plus/icons-vue'
import { getDataset, getDatasetVersionCatalog } from '@/api/datasets'
import { createWorkflow, getWorkflow, listWorkflows, updateWorkflow } from '@/api/workflows'
import { appendWorkflowSources, sourceFromCatalog, uniqueTableReferences } from '@/utils/workflowSources'

const props = defineProps({
  modelValue: Boolean,
  references: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'opened'])
const rows = ref([])
const selectedKeys = ref([])
const workflows = ref([])
const mode = ref('new')
const name = ref('')
const workflowId = ref('')
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const listError = ref('')
let generation = 0
const selectedSources = computed(() => rows.value.filter((row) => selectedKeys.value.includes(row.key) && row.source).map((row) => row.source))
const canSubmit = computed(() => !loading.value && !submitting.value && selectedSources.value.length
  && (mode.value === 'new' ? name.value.trim() : workflowId.value && !listError.value))

async function loadWorkflows(current = generation) {
  listError.value = ''
  try {
    const result = await listWorkflows()
    if (current === generation) workflows.value = result || []
  } catch (cause) {
    if (current === generation) listError.value = cause.message || '工作流列表载入失败'
  }
}

async function prepare() {
  const current = ++generation
  loading.value = true
  error.value = ''
  rows.value = []
  selectedKeys.value = []
  workflows.value = []
  workflowId.value = ''
  mode.value = 'new'
  try {
    const references = uniqueTableReferences(props.references)
    const catalogs = new Map()
    const datasets = new Map()
    const prepared = []
    // Resolve once per version, with at most one metadata request in flight.
    for (const reference of references) {
      try {
        if (!catalogs.has(reference.datasetVersionId)) {
          catalogs.set(reference.datasetVersionId, await getDatasetVersionCatalog(reference.datasetVersionId))
        }
        const catalog = catalogs.get(reference.datasetVersionId)
        if (!datasets.has(catalog.datasetId)) datasets.set(catalog.datasetId, await getDataset(catalog.datasetId))
        const dataset = datasets.get(catalog.datasetId)
        const source = sourceFromCatalog(reference, catalog, dataset.name || dataset.originalFilename)
        prepared.push({ ...reference, source })
      } catch (cause) {
        prepared.push({ ...reference, error: cause.message || '数据资产不可用' })
      }
      if (current !== generation) return
    }
    rows.value = prepared
    selectedKeys.value = prepared.filter((row) => row.source).map((row) => row.key)
    const first = prepared.find((row) => row.source)?.source
    name.value = first ? `${first.datasetName} · ${first.tableName}${prepared.length > 1 ? ` 等 ${prepared.length} 张表` : ''} 流程`.slice(0, 255) : '新建工作流'
    await loadWorkflows(current)
  } catch (cause) {
    if (current === generation) error.value = cause.message || '数据资产载入失败'
  } finally {
    if (current === generation) loading.value = false
  }
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  error.value = ''
  try {
    let workflow
    if (mode.value === 'new') {
      const { document } = appendWorkflowSources(null, selectedSources.value)
      workflow = await createWorkflow({ name: name.value.trim(), document })
    } else {
      workflow = await getWorkflow(workflowId.value)
      const result = appendWorkflowSources(workflow.document, selectedSources.value)
      if (result.addedCount) workflow = await updateWorkflow(workflow.id, {
        name: workflow.name, document: result.document, expectedRevision: workflow.revision,
      })
    }
    emit('update:modelValue', false)
    emit('opened', workflow.id)
  } catch (cause) {
    error.value = cause.message || '无法保存工作流，请重试'
  } finally {
    submitting.value = false
  }
}

function close() {
  if (!submitting.value) emit('update:modelValue', false)
}

watch(() => props.modelValue, (visible) => {
  if (visible) prepare()
  else generation += 1
})
</script>

<template>
  <el-dialog :model-value="modelValue" title="加入工作流" width="min(560px, calc(100vw - 24px))"
    :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" @close="close">
    <p v-if="loading" class="source-loading">正在读取工作流</p>
    <template v-else>
      <el-checkbox-group v-model="selectedKeys" class="source-list" aria-label="来源数据表">
        <div v-for="row in rows" :key="row.key" class="source-row">
          <el-checkbox :value="row.key" :disabled="!row.source || submitting" :aria-label="row.source?.tableName || row.tableName || row.tableId" />
          <div class="source-copy">
            <strong :title="row.source?.tableName || row.tableName || row.tableId">{{ row.source?.tableName || row.tableName || row.tableId }}</strong>
            <span :title="row.source?.datasetName || row.datasetName">{{ row.source?.datasetName || row.datasetName }}<template v-if="row.source"> · v{{ row.source.versionNo }} · {{ row.source.rowCount.toLocaleString('zh-CN') }} 行</template></span>
            <small v-if="row.error" role="alert">{{ row.error }}</small>
          </div>
        </div>
      </el-checkbox-group>
      <div class="target-controls">
        <el-segmented v-model="mode" :disabled="submitting" :options="[{ label: '新建画布', value: 'new' }, { label: '已有画布', value: 'existing' }]" aria-label="目标画布类型" />
        <label v-if="mode === 'new'" class="target-field">
          <span>画布名称</span>
          <el-input v-model="name" maxlength="255" :disabled="submitting" aria-label="画布名称" />
        </label>
        <label v-else class="target-field">
          <span>目标画布</span>
          <el-select v-model="workflowId" filterable :disabled="submitting || Boolean(listError)" placeholder="选择画布" no-data-text="还没有画布，可切换到新建画布" aria-label="目标画布">
            <el-option v-for="workflow in workflows" :key="workflow.id" :value="workflow.id" :label="workflow.name">
              <span class="workflow-option" :title="workflow.name">{{ workflow.name }}</span>
            </el-option>
          </el-select>
        </label>
        <div v-if="listError && mode === 'existing'" class="dialog-error" role="alert">{{ listError }}<el-button text :icon="Refresh" @click="loadWorkflows()">重试</el-button></div>
      </div>
    </template>
    <div v-if="error" class="dialog-error" role="alert">{{ error }}</div>
    <template #footer>
      <div class="dialog-footer"><span>{{ selectedSources.length }} 张表</span><el-button :disabled="submitting" @click="close">取消</el-button><el-button type="primary" :icon="mode === 'new' ? Plus : Share" :loading="submitting" :disabled="!canSubmit" @click="submit">{{ mode === 'new' ? '创建并打开' : '加入并打开' }}</el-button></div>
    </template>
  </el-dialog>
</template>

<style scoped>
.source-list { max-height: 260px; overflow: auto; overscroll-behavior: contain; }
.source-row { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid #e6ebe8; }
.source-row .el-checkbox { flex: 0 0 22px; margin: 0; }
.source-copy { display: grid; min-width: 0; gap: 5px; }
.source-copy strong, .source-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-copy span { color: #748179; font-size: 12px; }
.source-copy small, .dialog-error { color: #ab443c; overflow-wrap: anywhere; }
.target-controls { display: grid; gap: 18px; margin-top: 20px; }
.target-controls .el-segmented { justify-self: start; }
.target-field { display: grid; min-width: 0; gap: 7px; }
.target-field > span { color: #66746c; font-size: 12px; }
.workflow-option { display: block; max-width: min(430px, calc(100vw - 100px)); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dialog-error { margin-top: 12px; font-size: 12px; }
.dialog-footer { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dialog-footer > span { margin-right: auto; color: #748179; font-size: 12px; }
.dialog-footer .el-button + .el-button { margin-left: 0; }
</style>
