<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '@/auth/keycloak'
import { getDatasetCatalog, getDatasetVersions, listDatasets } from '@/api/datasets'
import { compareModelTrials, getModelTrialComparison, listModelTrials, listModelVersions, runModelTrial } from '@/api/models'
import { Close, DataAnalysis, MagicStick, Plus, Refresh, Warning } from '@element-plus/icons-vue'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const models = ref([])
const datasets = ref([])
const versions = ref([])
const catalog = ref(null)
const trials = ref([])
const selectedModelIds = ref([])
const selectedDatasetId = ref('')
const selectedVersionId = ref('')
const selectedTableId = ref('')
const testSources = ref([])
const evaluate = ref(true)
const running = ref(false)
const loadingModels = ref(false)
const loadingDatasets = ref(false)
const loadingTrials = ref(false)
const loadingCatalog = ref(false)
const error = ref('')
const lastTrials = ref([])
const activeTrial = ref(null)
let datasetRequest = 0
let versionsRequest = 0
let catalogRequest = 0

const datasetGroups = computed(() => {
  const groups = new Map()
  for (const dataset of datasets.value) {
    const id = dataset.datasetId || dataset.id
    if (id && !groups.has(id)) groups.set(id, { ...dataset, datasetId: id })
  }
  return [...groups.values()]
})
const readyVersions = computed(() => versions.value.filter((item) => item.status === 'READY'))
const selectedModels = computed(() => selectedModelIds.value
  .map((id) => models.value.find((item) => item.id === id))
  .filter(Boolean))
const selectedModel = computed(() => selectedModels.value[0])
const compatibleSelection = computed(() => selectedModels.value.length >= 1
  && selectedModels.value.every((model) => model.task === selectedModel.value?.task
    && model.targetColumn === selectedModel.value?.targetColumn))
const comparisonMode = computed(() => selectedModelIds.value.length > 1)
const metricKeys = computed(() => [...new Set(lastTrials.value.flatMap((trial) => Object.keys(trial.metrics || {})))])
const trialGroups = computed(() => {
  const groups = new Map()
  for (const trial of trials.value) {
    const id = trial.comparisonId || trial.id
    if (!groups.has(id)) groups.set(id, { comparisonId: id, items: [], sources: new Map(), representative: trial })
    const group = groups.get(id)
    group.items.push(trial)
    const sourceId = `${trial.datasetVersionId}:${trial.tableId}`
    if (!group.sources.has(sourceId)) group.sources.set(sourceId, trial)
  }
  return [...groups.values()].map((group) => ({ ...group, sources: [...group.sources.values()] })).sort((left, right) =>
    new Date(right.representative.createdAt) - new Date(left.representative.createdAt))
})
const selectedTable = computed(() => catalog.value?.tables?.find((item) => item.tableId === selectedTableId.value))
const columns = computed(() => selectedTable.value?.columns || [])
const targetAvailable = computed(() => columns.value.some((column) => column.name === selectedModel.value?.targetColumn))
const trainingDatasetOverlap = computed(() => selectedModels.value.some((model) =>
  model.datasetId === catalog.value?.datasetId
  || model.datasetVersionId === catalog.value?.datasetVersionId))
const featureMissing = computed(() => [...new Set(selectedModels.value
  .flatMap((model) => model.featureColumns || [])
  .filter((name) => !columns.value.some((column) => column.name === name)))])
const currentSource = computed(() => {
  const table = selectedTable.value
  if (!catalog.value || !table) return null
  return {
    datasetId: catalog.value.datasetId,
    datasetVersionId: catalog.value.datasetVersionId,
    tableId: table.tableId,
    datasetName: datasetLabel(datasetGroups.value.find((item) => item.datasetId === catalog.value.datasetId)),
    versionNo: catalog.value.versionNo,
    tableName: table.tableName,
    rowCount: table.rowCount,
    columns: table.columns || [],
  }
})
const sourceCompatibility = computed(() => testSources.value.map((source) => {
  const available = new Set(source.columns.map((column) => column.name))
  const missing = [...new Set(selectedModels.value.flatMap((model) => model.featureColumns || []))]
    .filter((name) => !available.has(name))
  const overlap = selectedModels.value.some((model) => model.datasetId === source.datasetId
    || model.datasetVersionId === source.datasetVersionId)
  const targetMissing = evaluate.value && selectedModel.value && !available.has(selectedModel.value.targetColumn)
  return { key: sourceKey(source), source, missing, overlap, targetMissing }
}))
const availableTrialDatasets = computed(() => datasetGroups.value.filter((dataset) =>
  !selectedModels.value.some((model) => model.datasetId === dataset.datasetId)))
const currentSourceKey = computed(() => currentSource.value ? sourceKey(currentSource.value) : '')
const currentSourceAdded = computed(() => testSources.value.some((source) => sourceKey(source) === currentSourceKey.value))
const sourcesReady = computed(() => testSources.value.length > 0 && sourceCompatibility.value.every((item) =>
  !item.missing.length && !item.overlap && !item.targetMissing))
const datasetLabel = (item) => item?.name || item?.originalFilename || '未命名数据集'
const modelLabel = (item) => item ? `${item.name} · ${item.task === 'classification' ? '分类' : '回归'} · ${item.algorithm}` : ''
const trialDatasetLabel = (datasetId) => datasetLabel(datasetGroups.value.find((item) => item.datasetId === datasetId))
function sourceKey(source) { return `${source.datasetId}:${source.datasetVersionId}:${source.tableId}` }
function addCurrentSource() {
  if (!currentSource.value || currentSourceAdded.value || testSources.value.length >= 5) return
  testSources.value.push(currentSource.value)
}
function removeSource(key) {
  testSources.value = testSources.value.filter((source) => sourceKey(source) !== key)
}

function formatNumber(value, digits = 3) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits }).format(Number(value || 0))
}
function formatTime(value) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}
function resetTrialContext() {
  catalog.value = null
  selectedTableId.value = ''
  lastTrials.value = []
  activeTrial.value = null
  error.value = ''
}
async function loadModels() {
  loadingModels.value = true
  error.value = ''
  try {
    models.value = await listModelVersions() || []
    selectedModelIds.value = selectedModelIds.value.filter((id) => models.value.some((item) => item.id === id))
    const requestedModelId = String(route.query.modelId || '')
    if (requestedModelId && models.value.some((item) => item.id === requestedModelId)) {
      selectedModelIds.value = [requestedModelId]
    } else if (!selectedModelIds.value.length && models.value.length) selectedModelIds.value = [models.value[0].id]
    selectIndependentDataset()
  } catch (e) { error.value = e.message || '模型版本加载失败' } finally { loadingModels.value = false }
}

function selectIndependentDataset() {
  if (!selectedModel.value || !datasetGroups.value.length) return
  const currentIsTrainingData = selectedModels.value.some((model) => model.datasetId === selectedDatasetId.value)
  if (!selectedDatasetId.value || currentIsTrainingData) {
    selectedDatasetId.value = availableTrialDatasets.value[0]?.datasetId || ''
  }
  if (!availableTrialDatasets.value.length) {
    error.value = '没有可用于试炼的独立数据集。请先添加一份不同于训练数据的新数据。'
  }
}
async function loadDatasets() {
  const requestId = ++datasetRequest
  loadingDatasets.value = true
  error.value = ''
  try {
    const result = await listDatasets({ status: 'READY', page: 0, size: 100 })
    if (requestId !== datasetRequest) return
    datasets.value = result.items || []
    if (!datasetGroups.value.some((item) => item.datasetId === selectedDatasetId.value)) selectedDatasetId.value = ''
    selectIndependentDataset()
  } catch (e) { if (requestId === datasetRequest) error.value = e.message || '测试数据集加载失败' } finally { if (requestId === datasetRequest) loadingDatasets.value = false }
}
async function loadVersions(datasetId) {
  const requestId = ++versionsRequest
  versions.value = []
  selectedVersionId.value = ''
  resetTrialContext()
  if (!datasetId) return
  try {
    const result = await getDatasetVersions(datasetId)
    if (requestId !== versionsRequest) return
    versions.value = result || []
    selectedVersionId.value = readyVersions.value[0]?.datasetVersionId || ''
  } catch (e) { if (requestId === versionsRequest) error.value = e.message || '数据集版本加载失败' }
}
async function loadCatalog(versionId) {
  const requestId = ++catalogRequest
  resetTrialContext()
  if (!versionId) return
  loadingCatalog.value = true
  try {
    const result = await getDatasetCatalog(versionId)
    if (requestId !== catalogRequest) return
    catalog.value = result
    selectedTableId.value = result.tables?.[0]?.tableId || ''
    const firstTable = result.tables?.find((table) => table.tableId === selectedTableId.value)
    evaluate.value = Boolean(firstTable?.columns?.some((column) => column.name === selectedModel.value?.targetColumn))
  } catch (e) { if (requestId === catalogRequest) error.value = e.message || '测试数据表加载失败' } finally { if (requestId === catalogRequest) loadingCatalog.value = false }
}
async function loadTrials() {
  if (!selectedModelIds.value.length) { trials.value = []; return }
  loadingTrials.value = true
  try {
    const grouped = await Promise.all(selectedModelIds.value.map((id) => listModelTrials(id)))
    trials.value = grouped.flat().sort((left, right) => new Date(right.createdAt) - new Date(left.createdAt))
  } catch (e) { error.value = e.message || '试炼记录加载失败' } finally { loadingTrials.value = false }
}
async function submitTrial() {
  error.value = ''
  if (!compatibleSelection.value || !sourcesReady.value || selectedModelIds.value.length > 5) {
    error.value = '请选择 1 个模型进行试用，或选择 2 到 5 个兼容模型进行比较，并加入满足字段与训练数据隔离要求的测试数据源'
    return
  }
  running.value = true
  lastTrials.value = []
  activeTrial.value = null
  try {
    if (comparisonMode.value) {
      lastTrials.value = await compareModelTrials({
        testSources: testSources.value.map(({ datasetId, datasetVersionId, tableId }) => ({ datasetId, datasetVersionId, tableId })),
        modelIds: [...selectedModelIds.value],
        targetColumn: evaluate.value ? selectedModel.value.targetColumn : null,
      })
    } else {
      const results = await Promise.allSettled(testSources.value.map(({ datasetId, datasetVersionId, tableId }) => runModelTrial(selectedModelIds.value[0], {
        datasetId,
        datasetVersionId,
        tableId,
        targetColumn: evaluate.value ? selectedModel.value.targetColumn : null,
      })))
      lastTrials.value = results.filter((result) => result.status === 'fulfilled').map((result) => result.value)
      const failures = results.filter((result) => result.status === 'rejected')
      if (failures.length) error.value = `${lastTrials.value.length} 份数据试用成功，${failures.length} 份失败：${failures[0].reason?.message || '运行失败'}`
    }
    activeTrial.value = lastTrials.value[0] || null
    const resultIds = new Set(lastTrials.value.map((item) => item.id))
    trials.value = [...lastTrials.value, ...trials.value.filter((item) => !resultIds.has(item.id))]
  } catch (e) { error.value = e.message || '模型试炼失败' } finally { running.value = false }
}

watch(selectedDatasetId, loadVersions)
watch(selectedVersionId, loadCatalog)
watch(selectedTableId, (tableId) => {
  if (!tableId || !selectedModel.value) return
  evaluate.value = columns.value.some((column) => column.name === selectedModel.value.targetColumn)
})
watch(() => [...selectedModelIds.value], () => {
  lastTrials.value = []
  activeTrial.value = null
  loadTrials()
  selectIndependentDataset()
  evaluate.value = Boolean(targetAvailable.value)
})
watch(() => auth.state.status, (status) => {
  if (status === 'authenticated') { loadModels(); loadDatasets() }
  else if (status === 'anonymous') { models.value = []; datasets.value = []; trials.value = []; resetTrialContext() }
}, { immediate: true })
async function showTrial(row) {
  error.value = ''
  try {
    lastTrials.value = await getModelTrialComparison(row.comparisonId)
    activeTrial.value = lastTrials.value[0] || null
  } catch (e) { error.value = e.message || '试炼记录加载失败' }
}

onBeforeUnmount(() => { datasetRequest += 1; versionsRequest += 1; catalogRequest += 1 })
</script>

<template>
  <main class="page-content trial-page">
    <header class="page-header trial-header">
      <div><span class="trial-kicker">MODEL PLAYGROUND</span><h1>模型试炼场</h1><p>用独立数据版本验证、比较已登记模型</p></div>
      <el-button circle aria-label="刷新模型与测试数据" title="刷新" :icon="Refresh" :loading="loadingModels || loadingDatasets" @click="loadModels(); loadDatasets()" />
    </header>
    <div v-if="error" class="trial-message is-error" role="alert"><el-icon><Warning /></el-icon><span>{{ error }}</span><el-button text :icon="Refresh" @click="loadModels(); loadDatasets()">重试</el-button></div>

<section class="trial-panel" aria-label="试炼配置">
      <div class="section-heading"><div class="section-title"><span class="section-icon"><el-icon><MagicStick /></el-icon></span><div><h2>试炼配置</h2><span>训练数据不会被修改</span></div></div><el-tag v-if="selectedModel" type="success" effect="plain" size="small">{{ selectedModel.task === 'classification' ? '分类' : '回归' }}</el-tag></div>
      <div class="trial-form">
        <label class="form-field form-field-wide"><span>登记模型</span><el-select v-model="selectedModelIds" multiple :multiple-limit="5" collapse-tags :max-collapse-tags="2" filterable :loading="loadingModels" placeholder="选 1 个试用，或选 2–5 个比较"><el-option v-for="model in models" :key="model.id" :value="model.id" :label="modelLabel(model)" :disabled="selectedModels.length > 0 && (model.task !== selectedModel.task || model.targetColumn !== selectedModel.targetColumn)"><span class="option-label" :title="modelLabel(model)">{{ modelLabel(model) }}</span><small class="option-meta">{{ formatTime(model.createdAt) }}</small></el-option></el-select></label>
        <label class="form-field"><span>独立测试数据</span><el-select v-model="selectedDatasetId" filterable :loading="loadingDatasets" placeholder="选择与训练数据不同的数据集"><el-option v-for="dataset in availableTrialDatasets" :key="dataset.datasetId" :value="dataset.datasetId" :label="datasetLabel(dataset)"><span class="option-label" :title="datasetLabel(dataset)">{{ datasetLabel(dataset) }}</span></el-option></el-select></label>
        <label class="form-field"><span>数据集版本</span><el-select v-model="selectedVersionId" :disabled="!selectedDatasetId" placeholder="选择 READY 版本"><el-option v-for="version in readyVersions" :key="version.datasetVersionId" :value="version.datasetVersionId" :label="`v${version.versionNo} · ${version.rowCount} 行`"><span class="option-label">v{{ version.versionNo }} · {{ formatNumber(version.rowCount, 0) }} 行</span></el-option></el-select></label>
        <label class="form-field"><span>数据表</span><el-select v-model="selectedTableId" :disabled="!catalog?.tables?.length" :loading="loadingCatalog" placeholder="选择数据表"><el-option v-for="table in catalog?.tables || []" :key="table.tableId" :value="table.tableId" :label="table.tableName"><span class="option-label" :title="table.tableName">{{ table.tableName }}</span><small class="option-meta">{{ formatNumber(table.rowCount, 0) }} 行</small></el-option></el-select></label>
        <label class="trial-switch"><span>评估模式</span><el-switch v-model="evaluate" active-text="带标签评估" inactive-text="只生成预测" /></label>
      </div>
      <div v-if="selectedModel && !loadingDatasets && !availableTrialDatasets.length" class="trial-message is-warning">
        <el-icon><Warning /></el-icon>
        <span>试炼必须使用不同于训练来源的数据集，避免把训练数据当成新数据验证。</span>
        <el-button text @click="router.push('/datasets')">添加测试数据</el-button>
      </div>
      <div class="source-actions"><span>测试数据源（{{ testSources.length }}/5）</span><el-button size="small" :icon="Plus" :disabled="!currentSource || currentSourceAdded || testSources.length >= 5" @click="addCurrentSource">加入清单</el-button></div>
      <ul v-if="testSources.length" class="source-list" aria-label="已加入的测试数据源">
        <li v-for="item in sourceCompatibility" :key="sourceKey(item.source)" class="source-item">
          <div class="source-copy"><strong :title="`${item.source.datasetName} · v${item.source.versionNo} · ${item.source.tableName}`">{{ item.source.datasetName }}</strong><span :title="item.source.tableName">v{{ item.source.versionNo }} · {{ item.source.tableName }} · {{ formatNumber(item.source.rowCount, 0) }} 行</span></div>
          <span v-if="item.overlap" class="source-status is-error">与训练数据重叠</span><span v-else-if="item.missing.length" class="source-status is-error" :title="item.missing.join('、')">缺少特征</span><span v-else-if="item.targetMissing" class="source-status is-warning">缺少目标</span><span v-else class="source-status is-ready">可用</span>
          <el-button circle size="small" :icon="Close" :aria-label="`移除 ${item.source.datasetName} 测试数据源`" title="移除数据源" @click="removeSource(sourceKey(item.source))" />
        </li>
      </ul>
      <div v-if="selectedModel" class="trial-context"><span>已选模型 {{ selectedModels.length }} 个</span><span>共同任务：{{ selectedModel.task === 'classification' ? '分类' : '回归' }}</span><span v-if="evaluate">目标：{{ selectedModel.targetColumn }} · {{ targetAvailable ? '已找到' : '测试表未提供' }}</span><span v-else>不会读取目标字段</span></div>
      <div v-if="selectedModelIds.length > 0 && !compatibleSelection" class="trial-message is-warning"><el-icon><Warning /></el-icon><span>比较时请选择任务类型及目标字段相同的模型。</span></div>
      <div v-if="evaluate && selectedModel && currentSource && !targetAvailable" class="trial-message is-warning"><el-icon><Warning /></el-icon><span>当前表没有目标字段。只预测模式可以加入该数据源。</span></div>
      <div v-if="sourceCompatibility.some((item) => item.missing.length || item.overlap || item.targetMissing)" class="trial-message is-warning"><el-icon><Warning /></el-icon><span>清单中有不兼容数据源，请移除或修复后再运行。</span></div>
      <div class="form-actions"><span class="form-footnote">{{ comparisonMode ? '最多 5 个模型 × 5 个数据源' : '单模型预测或评估' }} · 每个数据源最多 100,000 行</span><el-button type="primary" :icon="MagicStick" :loading="running" :disabled="!compatibleSelection || !sourcesReady" @click="submitTrial">{{ running ? (comparisonMode ? '比较中' : '试用中') : (comparisonMode ? '开始比较' : '运行试用') }}</el-button></div>
    </section>

    <section v-if="lastTrials.length" class="result-panel" aria-label="模型试用结果">
      <div class="section-heading"><div class="section-title"><span class="section-icon is-success"><el-icon><DataAnalysis /></el-icon></span><div><h2>{{ comparisonMode ? '本次比较' : '本次试用' }}</h2><span>{{ new Set(lastTrials.map((trial) => trial.modelId)).size }} 个模型 · {{ formatNumber(lastTrials[0]?.predictedRows, 0) }} 条预测 / 模型</span></div></div><el-tag type="success" effect="plain">{{ lastTrials[0]?.evaluatedRows ? '已评估' : '仅预测' }}</el-tag></div>
      <el-table :data="lastTrials" row-key="id" size="small" table-layout="fixed" class="comparison-table" @row-click="activeTrial = $event">
        <el-table-column label="测试数据源" min-width="190"><template #default="{ row }"><div class="lineage-cell"><strong>{{ trialDatasetLabel(row.datasetId) }}</strong><span>版本 {{ row.datasetVersionNo }} · {{ testSources.find((source) => source.datasetVersionId === row.datasetVersionId && source.tableId === row.tableId)?.tableName || row.tableId }}</span></div></template></el-table-column>
        <el-table-column label="模型版本" min-width="210"><template #default="{ row }"><div class="lineage-cell"><strong :title="models.find((model) => model.id === row.modelId)?.name">{{ models.find((model) => model.id === row.modelId)?.name || row.modelId }}</strong><span>{{ models.find((model) => model.id === row.modelId)?.algorithm }}</span></div></template></el-table-column>
        <el-table-column v-for="metric in metricKeys" :key="metric" :label="metric" min-width="115"><template #default="{ row }">{{ row.metrics?.[metric] == null ? '—' : formatNumber(row.metrics[metric], 4) }}</template></el-table-column>
        <el-table-column label="评估 / 预测" width="150"><template #default="{ row }">{{ formatNumber(row.evaluatedRows, 0) }} / {{ formatNumber(row.predictedRows, 0) }}</template></el-table-column>
      </el-table>
      <div v-if="activeTrial?.evaluationSamples?.length" class="sample-detail"><div class="sample-heading"><strong>{{ models.find((model) => model.id === activeTrial.modelId)?.name || '所选模型' }} · 预测样例</strong><span>{{ formatTime(activeTrial.createdAt) }}</span></div><el-table :data="activeTrial.evaluationSamples" size="small" table-layout="fixed" max-height="260" class="sample-table"><el-table-column prop="row" label="原始行" width="86" /><el-table-column prop="actual" label="实际值" min-width="140" show-overflow-tooltip /><el-table-column prop="predicted" label="预测值" min-width="140" show-overflow-tooltip /></el-table></div>
    </section>

    <section class="history-section"><div class="registry-heading"><div><h2>试炼记录</h2><span>{{ trialGroups.length }} 组 · 当前所选模型</span></div><el-button circle aria-label="刷新试炼记录" title="刷新记录" :icon="Refresh" :loading="loadingTrials" @click="loadTrials" /></div><el-table v-if="trialGroups.length" :data="trialGroups" row-key="comparisonId" table-layout="fixed" class="history-table" @row-click="showTrial"><el-table-column label="模型比较" min-width="220"><template #default="{ row }"><div class="lineage-cell"><strong>{{ row.items.length }} 项结果</strong><span>{{ [...new Set(row.items.map((trial) => models.find((model) => model.id === trial.modelId)?.name || trial.modelId))].join('、') }}</span></div></template></el-table-column><el-table-column label="测试数据源" min-width="220"><template #default="{ row }"><div class="lineage-cell"><strong>{{ row.sources.length }} 个数据源</strong><span :title="row.sources.map((source) => trialDatasetLabel(source.datasetId)).join('、')">{{ row.sources.map((source) => `${trialDatasetLabel(source.datasetId)} v${source.datasetVersionNo}`).join('、') }}</span></div></template></el-table-column><el-table-column label="指标" min-width="190"><template #default="{ row }"><div class="registry-metrics"><span v-for="trial in row.items" :key="trial.id">{{ models.find((model) => model.id === trial.modelId)?.name || '模型' }}: {{ Object.entries(trial.metrics || {}).map(([key, value]) => `${key} ${formatNumber(value, 3)}`).join(' · ') || '仅预测' }}</span></div></template></el-table-column><el-table-column prop="representative.targetColumn" label="目标字段" min-width="130" show-overflow-tooltip /><el-table-column label="时间" width="165"><template #default="{ row }">{{ formatTime(row.representative.createdAt) }}</template></el-table-column></el-table><el-empty v-else description="选择模型后还没有试炼记录" :image-size="76" /></section>
  </main>
</template>

<style scoped>
.trial-page { padding-bottom: 32px; }
.trial-header { align-items: center; }
.trial-kicker { display: inline-block; margin-bottom: 5px; color: #438573; font-size: 10px; font-weight: 700; line-height: 1.2; }
.trial-header h1 { letter-spacing: 0; }
.trial-header p { color: #756f67; }
.trial-panel, .result-panel { margin-bottom: 12px; padding: 14px 16px; background: #fff; border: 1px solid var(--line-strong); border-radius: var(--radius-panel); box-shadow: none; }
.section-heading, .section-title, .registry-heading { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 14px; }
.section-title { justify-content: flex-start; }
.section-icon { display: grid; flex: 0 0 34px; width: 34px; height: 34px; place-items: center; color: var(--accent); background: var(--accent-subtle); border-radius: var(--radius-control); }
.section-icon.is-success { color: #46735e; background: #e4efe7; }
.section-title > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.section-title h2, .registry-heading h2 { max-width: 700px; margin: 0; overflow: hidden; color: #20372f; font-size: 15px; font-weight: 650; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.section-title > div > span, .registry-heading > div > span { color: #829089; font-size: 10px; }
.trial-form { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 15px 18px; margin-top: 20px; }
.form-field, .trial-switch { display: grid; min-width: 0; gap: 7px; color: #586b63; font-size: 11px; font-weight: 600; }
.form-field > span, .trial-switch > span { display: flex; justify-content: space-between; gap: 8px; }
.form-field :deep(.el-select), .form-field :deep(.el-input) { width: 100%; min-width: 0; }
.form-field :deep(.el-select__selected-item), .option-label { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.form-field-wide { grid-column: span 3; }
.option-label { display: inline-block; max-width: min(54vw, 420px); vertical-align: middle; }
.option-meta { float: right; margin-left: 14px; color: #829089; font-size: 10px; }
.trial-switch :deep(.el-switch) { min-height: 32px; }
.trial-context { display: flex; min-width: 0; flex-wrap: wrap; gap: 7px 15px; margin-top: 15px; color: #687971; font-size: 10px; }
.trial-context span { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trial-message { display: flex; min-height: 40px; align-items: center; gap: 9px; margin-top: 12px; padding: 7px 10px; border: 1px solid; border-radius: 6px; font-size: 11px; }
.trial-message > span { min-width: 0; flex: 1; overflow-wrap: anywhere; }
.trial-message.is-error { color: #8d3c35; background: #fff4f0; border-color: #f1d2c8; }
.trial-message.is-warning { color: #7a6127; background: #fff9e9; border-color: #efe2b9; }
.form-actions { display: flex; min-height: 52px; align-items: center; justify-content: space-between; gap: 12px; margin-top: 16px; padding-top: 13px; border-top: 1px solid #e7eeea; }
.source-actions { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 12px; margin-top: 17px; color: #52665c; font-size: 11px; font-weight: 650; }
.source-list { display: grid; min-width: 0; gap: 6px; margin: 9px 0 0; padding: 0; list-style: none; }
.source-item { display: grid; min-width: 0; grid-template-columns: minmax(0, 1fr) auto 32px; align-items: center; gap: 10px; padding: 8px 9px; border: 1px solid #e5ece8; border-radius: 6px; background: #fff; }
.source-copy { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.source-copy strong, .source-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-copy strong { color: #30463d; font-size: 11px; font-weight: 620; }
.source-copy span { color: #84928b; font-size: 10px; }
.source-status { max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }
.source-status.is-ready { color: #438573; }
.source-status.is-warning { color: #97712b; }
.source-status.is-error { color: #a14e42; }
.form-footnote { min-width: 0; color: #829089; font-size: 11px; }
.metric-strip { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); margin-top: 15px; overflow: hidden; border: 1px solid #e5ece8; border-radius: 6px; }
.metric-item { display: flex; min-width: 0; min-height: 72px; flex-direction: column; justify-content: center; gap: 5px; padding: 11px 15px; border-right: 1px solid #e5ece8; }
.metric-item:last-child { border-right: 0; }
.metric-item span, .metric-item strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.metric-item span { color: #74847c; font-size: 10px; }
.metric-item strong { color: #1a3e32; font-size: 17px; font-weight: 680; line-height: 1.2; }
.sample-table { margin-top: 16px; }
.comparison-table { width: 100%; margin-top: 14px; overflow: hidden; border: 1px solid #e5ece8; border-radius: 7px; }
.sample-detail { min-width: 0; margin-top: 17px; }
.sample-heading { display: flex; min-width: 0; align-items: baseline; justify-content: space-between; gap: 10px; color: #52665c; font-size: 11px; }
.sample-heading strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sample-heading span { flex: 0 0 auto; color: #829089; font-size: 10px; }
.history-section { min-width: 0; }
.registry-heading { min-height: 48px; margin-bottom: 7px; }
.registry-heading > div { display: flex; align-items: baseline; gap: 9px; }
.history-table { width: 100%; overflow: hidden; border: 1px solid #e5ece8; border-radius: 7px; }
.lineage-cell { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.lineage-cell strong, .lineage-cell span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lineage-cell strong { color: #30463d; font-size: 11px; font-weight: 620; }
.lineage-cell span { color: #84928b; font-size: 10px; }
.registry-metrics { display: flex; min-width: 0; flex-wrap: wrap; gap: 4px 10px; color: #586b63; font-size: 10px; }
.registry-metrics span { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 900px) { .trial-form { grid-template-columns: repeat(2, minmax(0, 1fr)); } .form-field-wide { grid-column: span 2; } .metric-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 760px) { .trial-panel, .result-panel { padding: 14px 12px 12px; } .trial-form { grid-template-columns: minmax(0, 1fr); gap: 13px; margin-top: 16px; } .form-field-wide { grid-column: auto; } .form-actions { align-items: flex-start; flex-direction: column; padding-top: 11px; } .form-actions .el-button { width: 100%; } .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); } .metric-item { min-height: 65px; } .metric-item:nth-child(2n) { border-right: 0; } .registry-heading > div { align-items: flex-start; flex-direction: column; gap: 3px; } .source-item { grid-template-columns: minmax(0, 1fr) 32px; } .source-status { grid-column: 1; grid-row: 2; } .source-item > .el-button { grid-column: 2; grid-row: 1 / span 2; } }
</style>
