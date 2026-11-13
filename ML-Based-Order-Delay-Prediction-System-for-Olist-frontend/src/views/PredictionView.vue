<template>
  <main class="page-content prediction-page">
    <h1 class="visually-hidden">订单延误预测</h1>

    <header class="page-header prediction-header">
      <div class="prediction-title">
        <strong>订单延误预测</strong>
        <span>提交后立即返回，服务端排队计算；历史记录保存在数据库，换浏览器也一样</span>
      </div>
      <div class="prediction-header-actions">
        <el-tag v-if="pendingCount" type="warning" effect="plain" size="small">{{ pendingCount }} 个任务计算中</el-tag>
        <el-button circle aria-label="刷新" title="刷新" :icon="Refresh" :loading="loadingModels || loadingRecords" @click="refreshAll" />
      </div>
    </header>

    <div v-if="error && !manifest" class="prediction-message is-error" role="alert">
      <el-icon><Warning /></el-icon><span>{{ error }}</span>
      <el-button text :icon="Refresh" @click="refreshAll">重试</el-button>
    </div>

    <section v-if="!models.length && !loadingModels" class="prediction-empty">
      <el-icon><Aim /></el-icon>
      <div>
        <strong>还没有可用的分类模型</strong>
        <span>先到「建模实验」训练一个以 is_delayed 为目标的分类模型，再回来做单笔预测。</span>
      </div>
      <el-button type="primary" @click="router.push('/experiments')">去训练模型</el-button>
    </section>

    <template v-else>
      <section class="prediction-panel" aria-label="选择模型">
        <div class="panel-heading">
          <div class="panel-title"><h2>模型</h2><span>{{ models.length }} 个分类模型</span></div>
          <el-tag v-if="selectedModel" size="small" effect="plain" :type="selectedModel.status === 'APPROVED' ? 'success' : 'info'">
            {{ selectedModel.status || 'DRAFT' }}
          </el-tag>
        </div>
        <div class="model-form">
          <label class="form-field form-field-wide">
            <span>模型版本</span>
            <el-select v-model="selectedModelId" filterable :loading="loadingModels" placeholder="选择一个分类模型">
              <el-option v-for="model in models" :key="model.id" :value="model.id" :label="modelLabel(model)">
                <span class="option-label" :title="modelLabel(model)">{{ modelLabel(model) }}</span>
                <small class="option-meta">{{ model.status || 'DRAFT' }}</small>
              </el-option>
            </el-select>
          </label>
          <div v-if="selectedModel" class="model-facts">
            <span>算法 <strong>{{ selectedModel.algorithm }}</strong></span>
            <span>目标 <strong>{{ selectedModel.targetColumn }}</strong></span>
            <span>训练行数 <strong>{{ formatNumber(selectedModel.trainRows, 0) }}</strong></span>
            <span>阈值 <strong>{{ threshold == null ? '0.5' : formatNumber(threshold, 3) }}</strong></span>
          </div>
        </div>
      </section>

      <div v-if="manifestError" class="prediction-message is-error" role="alert">
        <el-icon><Warning /></el-icon><span>{{ manifestError }}</span>
        <el-button text @click="loadManifest(selectedModelId)">重试</el-button>
      </div>

      <div v-if="manifest" class="prediction-layout">
        <section class="prediction-panel form-panel" aria-label="订单特征">
          <div class="panel-heading">
            <div class="panel-title"><h2>订单特征</h2><span>{{ features.length }} 个输入 · 来自模型产物</span></div>
            <div class="panel-actions">
              <el-button size="small" :icon="RefreshLeft" @click="fillFromSample">填充训练样本</el-button>
            </div>
          </div>

          <div class="preset-row">
            <span>示例订单</span>
            <el-button v-for="preset in presets" :key="preset.label" size="small" plain @click="applyPreset(preset)">{{ preset.label }}</el-button>
          </div>

          <div v-for="group in groups" :key="group.title" class="feature-group">
            <h3>{{ group.title }}</h3>
            <div class="feature-grid">
              <label v-for="name in group.features" :key="name" class="feature-field" :class="{ 'is-wide': isWide(name) }">
                <span>{{ labelOf(name) }}<small>{{ kindOf(name) === 'numeric' ? unitOf(name) : '类别' }}</small></span>
                <el-input-number
                  v-if="kindOf(name) === 'numeric'"
                  v-model="form[name]"
                  :controls="false"
                  :precision="isInteger(name) ? 0 : 2"
                  :step="isInteger(name) ? 1 : 0.01"
                  size="small"
                />
                <el-input v-else v-model="form[name]" size="small" maxlength="120" placeholder="输入类别值" />
              </label>
            </div>
          </div>

          <div class="form-actions">
            <el-checkbox v-model="withCounterfactuals" size="small">同时生成反事实建议（较慢）</el-checkbox>
            <el-button type="primary" :icon="MagicStick" :loading="submitting" :disabled="!features.length" @click="submitPrediction">
              提交预测
            </el-button>
          </div>
          <p class="form-footnote">提交即返回，任务在服务端排队执行；关闭页面也不会丢，重新打开在下方历史里继续看结果。</p>
          <div v-if="error" class="prediction-message is-error" role="alert">
            <el-icon><Warning /></el-icon><span>{{ error }}</span>
          </div>
        </section>

        <section class="prediction-panel result-panel-sticky" aria-label="预测结果">
          <div class="panel-heading">
            <div class="panel-title">
              <h2>预测结果</h2>
              <span v-if="selectedRecord">{{ statusLabel(selectedRecord.status) }}</span>
            </div>
            <el-button v-if="result" size="small" text @click="clearResult">清除</el-button>
          </div>

          <div v-if="selectedRecord && !result" class="result-placeholder">
            <el-icon class="spin"><Loading /></el-icon>
            <strong>{{ statusLabel(selectedRecord.status) }}</strong>
            <span>任务已提交到服务端，完成后会自动显示；可以先关闭页面。</span>
          </div>

          <div v-else-if="!result" class="result-placeholder">
            <el-icon><DataAnalysis /></el-icon>
            <strong>还没有预测结果</strong>
            <span>左侧已按训练样本预填一单，点「提交预测」即可。</span>
          </div>

          <template v-else>
            <div class="verdict" :class="isDelayed ? 'is-delayed' : 'is-ontime'">
              <div class="verdict-icon"><el-icon><CircleCheck v-if="!isDelayed" /><Warning v-else /></el-icon></div>
              <div class="verdict-copy">
                <strong>{{ isDelayed ? '预计会延误' : '预计按时送达' }}</strong>
                <span>正类概率 {{ percent(result.probability) }} · 阈值 {{ result.threshold == null ? 0.5 : formatNumber(result.threshold, 3) }}</span>
              </div>
              <span class="risk-badge" :class="riskLevel.cls">{{ riskLevel.label }}</span>
            </div>

            <div class="probability-meter" :aria-label="`延误概率 ${percent(result.probability)}`">
              <div class="meter-track">
                <div class="meter-fill" :class="isDelayed ? 'is-delayed' : 'is-ontime'" :style="{ width: `${Math.min(100, probabilityPercent)}%` }" />
                <div v-if="result.threshold != null" class="meter-threshold" :style="{ left: `${Math.min(100, result.threshold * 100)}%` }" title="判定阈值" />
              </div>
              <div class="meter-labels"><span>0%</span><span>延误概率</span><span>100%</span></div>
            </div>

            <p v-if="result.naturalLanguage" class="narrative">{{ result.naturalLanguage }}</p>

            <div class="result-meta-grid">
              <div><span>预测类别</span><strong>{{ isDelayed ? '延误 (1)' : '未延误 (0)' }}</strong></div>
              <div><span>置信度</span><strong>{{ percent(result.confidence) }}</strong></div>
              <div><span>正类标签</span><strong>{{ result.positiveClass ?? '—' }}</strong></div>
              <div><span>解释方法</span><strong>{{ result.method }}</strong></div>
            </div>

            <div v-if="contributions.length" class="driver-block">
              <h3>主要影响因素</h3>
              <div v-for="item in contributions" :key="item.feature" class="driver-row">
                <span class="driver-label" :title="`${item.feature} = ${item.value}`">{{ item.label || item.feature }}</span>
                <div class="driver-track">
                  <div class="driver-bar" :class="item.contribution >= 0 ? 'is-up' : 'is-down'" :style="{ width: `${barWidth(item.contribution)}%` }" />
                </div>
                <span class="driver-value">{{ signed(item.contribution) }}</span>
              </div>
              <p class="driver-note">红色推高延误概率，蓝色拉低；数值是该特征相对训练基线样本的平均影响。</p>
            </div>

            <div v-if="result.counterfactuals?.length" class="counterfactual-block">
              <h3>怎样能变成准时？</h3>
              <div v-for="(item, index) in result.counterfactuals" :key="index" class="counterfactual-item">
                <span>{{ item.message }}</span>
                <el-button size="small" text type="primary" @click="applyCounterfactual(item)">应用并验证</el-button>
              </div>
            </div>
          </template>
        </section>
      </div>

      <section v-else-if="loadingManifest" class="prediction-panel">
        <div class="manifest-loading">正在读取模型产物…</div>
      </section>

      <section class="prediction-panel history-panel" aria-label="预测记录">
        <div class="panel-heading">
          <div class="panel-title">
            <h2>预测记录</h2>
            <span>{{ records.length }} 条 · 服务端保存</span>
          </div>
          <div class="panel-actions">
            <el-tag v-if="pendingCount" size="small" type="warning" effect="plain">队列 {{ pendingCount }}</el-tag>
            <el-popconfirm v-if="records.length" title="清空全部预测记录？" confirm-button-text="清空" cancel-button-text="取消" @confirm="clearHistory">
              <template #reference><el-button size="small" text type="danger">清空</el-button></template>
            </el-popconfirm>
          </div>
        </div>

        <el-table v-if="records.length" :data="records" size="small" row-key="id" class="history-table" max-height="360">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="模型" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.modelName || '—' }}</template>
          </el-table-column>
          <el-table-column label="订单摘要" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">{{ shortInput(row.input) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结论" width="96">
            <template #default="{ row }">
              <span v-if="row.status === 'SUCCEEDED'">{{ isDelayedRow(row) ? '延误' : '准时' }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="延误概率" width="100">
            <template #default="{ row }">{{ row.status === 'SUCCEEDED' ? percent(row.result?.probability) : '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRecord(row)">查看</el-button>
              <el-button link type="primary" @click="rerun(row)">重跑</el-button>
              <el-button link type="danger" @click="removeRecord(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="history-empty">还没有记录。提交一次预测后，任务和结果都会出现在这里。</div>
      </section>
    </template>

    <p class="prediction-footer">
      单笔预测使用模型产物的特征定义与判定阈值；需要一次评分成百上千单，请到
      <el-button link type="primary" @click="router.push('/evaluation')">评估与预测</el-button>
      上传数据表。
    </p>
  </main>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Aim,
  CircleCheck,
  DataAnalysis,
  Loading,
  MagicStick,
  Refresh,
  RefreshLeft,
  Warning,
} from '@element-plus/icons-vue'
import {
  clearModelPredictions,
  deleteModelPrediction,
  getModelManifest,
  listModelPredictions,
  listModelVersions,
  submitModelPrediction,
} from '@/api/models'
import { useAuth } from '@/auth/keycloak'

const router = useRouter()
const auth = useAuth()

const models = ref([])
const selectedModelId = ref('')
const loadingModels = ref(false)
const loadingManifest = ref(false)
const manifest = ref(null)
const manifestError = ref('')
const error = ref('')
const submitting = ref(false)
const result = ref(null)
const form = ref({})

const records = ref([])
const loadingRecords = ref(false)
const selectedRecordId = ref('')
const withCounterfactuals = ref(false)
let pollTimer = null

const INTEGER_FEATURES = new Set([
  'purchase_month', 'purchase_weekday', 'purchase_hour', 'purchase_day', 'purchase_weekofyear',
  'estimated_delivery_days', 'item_count', 'seller_count', 'product_category_count',
  'same_state', 'payment_count', 'payment_installments_max', 'payment_type_count', 'total_weight_g',
])

const UNITS = {
  distance_km: '公里',
  total_weight_g: '克',
  estimated_delivery_days: '天',
  freight_ratio: '占比',
  same_state: '0/1',
  purchase_month: '1-12',
  purchase_weekday: '0-6',
  purchase_hour: '0-23',
}

const FEATURE_GROUPS = [
  ['配送与距离', ['distance_km', 'same_state', 'estimated_delivery_days', 'customer_state', 'seller_state']],
  ['下单时间', ['purchase_month', 'purchase_weekday', 'purchase_hour', 'purchase_day', 'purchase_weekofyear']],
  ['金额与运费', ['total_price', 'total_freight', 'freight_ratio', 'avg_item_price', 'payment_value']],
  ['商品与卖家', ['item_count', 'seller_count', 'product_category', 'product_category_count', 'total_weight_g']],
  ['支付', ['payment_count', 'payment_installments_max', 'payment_type_count']],
]

const presets = [
  {
    label: '远距离跨州',
    values: {
      distance_km: 2050, same_state: 0, customer_state: 'SP', seller_state: 'AM',
      estimated_delivery_days: 30, freight_ratio: 0.42, total_freight: 68.5, total_price: 160,
      avg_item_price: 160, item_count: 1, payment_value: 228.5, payment_count: 1,
      payment_installments_max: 3, payment_type_count: 1, product_category: 'furniture_decor',
      total_weight_g: 12000, product_category_count: 1, purchase_month: 11, purchase_weekday: 4, purchase_hour: 21,
    },
  },
  {
    label: '同城小件',
    values: {
      distance_km: 45, same_state: 1, customer_state: 'SP', seller_state: 'SP',
      estimated_delivery_days: 8, freight_ratio: 0.12, total_freight: 14.2, total_price: 120,
      avg_item_price: 120, item_count: 1, payment_value: 134.2, payment_count: 1,
      payment_installments_max: 1, payment_type_count: 1, product_category: 'bed_bath_table',
      total_weight_g: 800, product_category_count: 1, purchase_month: 3, purchase_weekday: 1, purchase_hour: 10,
    },
  },
]

const DEFAULT_VALUES = {
  distance_km: 600, total_price: 120, total_freight: 22, freight_ratio: 0.18, avg_item_price: 120,
  item_count: 1, seller_count: 1, payment_value: 142, payment_count: 1, payment_installments_max: 1,
  payment_type_count: 1, product_category_count: 1, total_weight_g: 1500, estimated_delivery_days: 20,
  purchase_month: 6, purchase_weekday: 2, purchase_hour: 14, purchase_day: 15, purchase_weekofyear: 24,
  same_state: 1, customer_state: 'SP', seller_state: 'SP', product_category: 'bed_bath_table',
}

const selectedModel = computed(() => models.value.find((model) => model.id === selectedModelId.value) || null)
const features = computed(() => [...(manifest.value?.featureColumns || [])])
const featureTypes = computed(() => manifest.value?.featureTypes || {})
const threshold = computed(() => {
  if (result.value?.threshold != null) return Number(result.value.threshold)
  if (manifest.value?.decisionThreshold != null) return Number(manifest.value.decisionThreshold)
  return null
})
const groups = computed(() => {
  const known = new Set()
  const grouped = []
  for (const [title, names] of FEATURE_GROUPS) {
    const present = names.filter((name) => features.value.includes(name))
    present.forEach((name) => known.add(name))
    if (present.length) grouped.push({ title, features: present })
  }
  const rest = features.value.filter((name) => !known.has(name))
  if (rest.length) grouped.push({ title: '其他特征', features: rest })
  return grouped
})
const contributions = computed(() => (result.value?.contributions || []).slice(0, 6))
const probabilityPercent = computed(() => Number(result.value?.probability || 0) * 100)
const isDelayed = computed(() => (result.value ? isDelayedFrom(result.value) : false))
const riskLevel = computed(() => {
  if (!result.value) return { label: '', cls: '' }
  const probability = Number(result.value.probability || 0)
  const cutoff = Number(result.value.threshold ?? 0.5)
  if (probability >= cutoff) return { label: '高风险', cls: 'is-high' }
  if (probability >= cutoff * 0.7) return { label: '需关注', cls: 'is-mid' }
  return { label: '低风险', cls: 'is-low' }
})
const maxContribution = computed(() => Math.max(
  0.000001,
  ...contributions.value.map((item) => Math.abs(Number(item.contribution || 0))),
))
const selectedRecord = computed(() => records.value.find((item) => item.id === selectedRecordId.value) || null)
const pendingCount = computed(() => records.value.filter((item) => item.status === 'QUEUED' || item.status === 'RUNNING').length)
const pendingRestore = ref(null)

function isDelayedFrom(explanation) {
  if (!explanation) return false
  const positive = explanation.positiveClass
  if (positive != null) return String(explanation.prediction) === String(positive)
  return String(explanation.prediction) === '1'
}

function isDelayedRow(row) {
  return row?.status === 'SUCCEEDED' ? isDelayedFrom(row.result) : false
}

function statusLabel(status) {
  return { QUEUED: '排队中', RUNNING: '计算中', SUCCEEDED: '已完成', FAILED: '失败' }[status] || status
}

function statusType(status) {
  return { QUEUED: 'info', RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' }[status] || 'info'
}

function modelLabel(model) {
  if (!model) return ''
  return `${model.name || '未命名模型'} · ${model.algorithm}`
}

function kindOf(name) {
  return featureTypes.value[name] === 'numeric' ? 'numeric' : 'categorical'
}

function labelOf(name) {
  return manifest.value?.explainability?.featureLabels?.[name] || name
}

function unitOf(name) {
  return UNITS[name] || '数值'
}

function isInteger(name) {
  return INTEGER_FEATURES.has(name)
}

function isWide(name) {
  return kindOf(name) === 'categorical' || name === 'distance_km' || name === 'total_weight_g'
}

function formatNumber(value, digits = 0) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits }).format(Number(value))
}

function percent(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return `${(Number(value) * 100).toFixed(1)}%`
}

function signed(value) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return '—'
  return `${numeric >= 0 ? '+' : ''}${numeric.toFixed(3)}`
}

function barWidth(value) {
  return Math.min(100, (Math.abs(Number(value || 0)) / maxContribution.value) * 100)
}

function formatTime(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}

function shortInput(row) {
  if (!row) return '—'
  const parts = []
  if (row.distance_km != null) parts.push(`${formatNumber(row.distance_km, 0)}km`)
  if (row.customer_state) parts.push(`${row.customer_state}`)
  if (row.item_count != null) parts.push(`${row.item_count}件`)
  if (row.estimated_delivery_days != null) parts.push(`预计${formatNumber(row.estimated_delivery_days, 0)}天`)
  return parts.join(' · ') || '—'
}

function sampleRow() {
  const sample = manifest.value?.explainability?.backgroundSample?.[0] || {}
  const values = {}
  for (const name of features.value) {
    const hasValue = Object.prototype.hasOwnProperty.call(sample, name)
    const raw = hasValue ? sample[name] : DEFAULT_VALUES[name]
    if (kindOf(name) === 'numeric') {
      values[name] = raw == null || raw === '' || Number.isNaN(Number(raw)) ? Number(DEFAULT_VALUES[name] ?? 0) : Number(raw)
    } else {
      values[name] = raw == null ? String(DEFAULT_VALUES[name] ?? '') : String(raw)
    }
  }
  return values
}

function fillFromSample() {
  form.value = sampleRow()
}

function applyPreset(preset) {
  const next = { ...form.value }
  for (const [key, value] of Object.entries(preset.values)) {
    if (features.value.includes(key)) next[key] = value
  }
  form.value = next
}

function normalizeRow(row = form.value) {
  const normalized = {}
  for (const name of features.value) {
    const value = row[name]
    if (kindOf(name) === 'numeric') {
      const numeric = Number(value)
      normalized[name] = Number.isFinite(numeric) ? numeric : 0
    } else {
      normalized[name] = value == null ? '' : String(value)
    }
  }
  return normalized
}

async function submitPrediction() {
  if (!selectedModelId.value || !features.value.length || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const created = await submitModelPrediction({
      modelId: selectedModelId.value,
      features: normalizeRow(),
      includeCounterfactuals: withCounterfactuals.value,
    })
    records.value = [created, ...records.value.filter((item) => item.id !== created.id)]
    selectedRecordId.value = created.id
    result.value = null
    startPolling()
  } catch (failure) {
    error.value = failure.message || '提交预测失败'
  } finally {
    submitting.value = false
  }
}

async function loadRecords() {
  if (!auth.state.authenticated) return
  loadingRecords.value = true
  try {
    records.value = await listModelPredictions() || []
    syncResultFromSelection()
    if (!pendingCount.value) stopPolling()
  } catch (failure) {
    error.value = failure.message || '预测记录载入失败'
  } finally {
    loadingRecords.value = false
  }
}

function syncResultFromSelection() {
  const record = selectedRecord.value
  result.value = record && record.status === 'SUCCEEDED' ? record.result : null
}

function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(async () => {
    await loadRecords()
    if (!pendingCount.value) stopPolling()
  }, 2500)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function openRecord(record) {
  selectedRecordId.value = record.id
  if (record.modelId && record.modelId !== selectedModelId.value && models.value.some((model) => model.id === record.modelId)) {
    pendingRestore.value = record
    selectedModelId.value = record.modelId
    return
  }
  applyRecord(record)
}

function applyRecord(record) {
  const next = { ...form.value }
  for (const [key, value] of Object.entries(record.input || {})) {
    if (features.value.includes(key)) next[key] = value
  }
  form.value = next
  result.value = record.status === 'SUCCEEDED' ? record.result : null
  error.value = ''
}

async function rerun(record) {
  if (!record) return
  if (record.modelId !== selectedModelId.value && models.value.some((model) => model.id === record.modelId)) {
    selectedModelId.value = record.modelId
  }
  try {
    const created = await submitModelPrediction({
      modelId: record.modelId,
      features: record.input,
      includeCounterfactuals: false,
    })
    records.value = [created, ...records.value]
    selectedRecordId.value = created.id
    result.value = null
    startPolling()
  } catch (failure) {
    error.value = failure.message || '重跑失败'
  }
}

async function removeRecord(record) {
  try {
    await deleteModelPrediction(record.id)
    records.value = records.value.filter((item) => item.id !== record.id)
    if (selectedRecordId.value === record.id) {
      selectedRecordId.value = ''
      result.value = null
    }
  } catch (failure) {
    error.value = failure.message || '删除失败'
  }
}

async function clearHistory() {
  try {
    await clearModelPredictions()
    records.value = []
    selectedRecordId.value = ''
    result.value = null
  } catch (failure) {
    error.value = failure.message || '清空失败'
  }
}

function clearResult() {
  selectedRecordId.value = ''
  result.value = null
}

function applyCounterfactual(item) {
  if (item?.feature) {
    form.value = {
      ...form.value,
      [item.feature]: kindOf(item.feature) === 'numeric' ? Number(item.to) : String(item.to),
    }
  }
  submitPrediction()
}

async function loadManifest(modelId) {
  if (!modelId) return
  loadingManifest.value = true
  manifestError.value = ''
  manifest.value = null
  try {
    const payload = await getModelManifest(modelId)
    manifest.value = payload
    if (!payload?.featureColumns?.length) {
      manifestError.value = '该模型产物缺少特征定义，无法做单笔预测。'
      return
    }
    form.value = sampleRow()
    if (pendingRestore.value) {
      const record = pendingRestore.value
      pendingRestore.value = null
      applyRecord(record)
    }
  } catch (failure) {
    manifestError.value = failure.message || '模型产物读取失败'
  } finally {
    loadingManifest.value = false
  }
}

async function loadModels() {
  if (!auth.state.authenticated) return
  loadingModels.value = true
  error.value = ''
  try {
    const all = await listModelVersions() || []
    models.value = all.filter((model) => model.task === 'classification')
    if (!models.value.length) {
      selectedModelId.value = ''
      return
    }
    const requested = String(router.currentRoute.value.query.modelId || '')
    if (requested && models.value.some((model) => model.id === requested)) {
      selectedModelId.value = requested
    } else if (!models.value.some((model) => model.id === selectedModelId.value)) {
      const approved = models.value.find((model) => model.status === 'APPROVED')
      selectedModelId.value = (approved || models.value[0]).id
    }
  } catch (failure) {
    error.value = failure.message || '模型版本载入失败'
  } finally {
    loadingModels.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadModels(), loadRecords()])
}

onMounted(async () => {
  await loadModels()
  await loadRecords()
  if (pendingCount.value) startPolling()
})

onUnmounted(stopPolling)

watch(() => auth.state.status, (status) => {
  if (status === 'authenticated') refreshAll()
  if (status === 'anonymous') {
    models.value = []
    records.value = []
    manifest.value = null
    result.value = null
    form.value = {}
    selectedModelId.value = ''
    selectedRecordId.value = ''
    stopPolling()
  }
})

watch(selectedModelId, (modelId) => loadManifest(modelId))
</script>

<style scoped>
.prediction-page { display: grid; gap: 12px; padding-bottom: 26px; }
.prediction-header { align-items: center; }
.prediction-title { display: grid; gap: 3px; }
.prediction-title strong { color: #26352e; font-size: 18px; font-weight: 680; }
.prediction-title span { color: #6b7a72; font-size: 11px; }
.prediction-header-actions { display: flex; align-items: center; gap: 8px; }
.prediction-panel { min-width: 0; padding: 12px 14px; background: #fff; border: 1px solid var(--line); border-radius: 8px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.panel-title { display: flex; align-items: baseline; gap: 8px; }
.panel-title h2 { margin: 0; color: #26352e; font-size: 13px; font-weight: 660; }
.panel-title span { color: #859289; font-size: 10px; }
.panel-actions { display: flex; align-items: center; gap: 6px; }
.model-form { display: grid; gap: 8px; }
.form-field { display: grid; gap: 5px; color: #5c6c64; font-size: 11px; }
.model-facts { display: flex; flex-wrap: wrap; gap: 6px 18px; color: #6b7a72; font-size: 10px; }
.model-facts strong { color: #34493f; font-weight: 620; }
.prediction-layout { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(340px, 1fr); gap: 12px; align-items: start; }
.result-panel-sticky { position: sticky; top: 12px; }
.preset-row { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 8px 0 10px; border-bottom: 1px dashed var(--line); }
.preset-row > span { color: #6b7a72; font-size: 10px; }
.feature-group { margin-top: 12px; }
.feature-group h3 { margin: 0 0 7px; color: #41544a; font-size: 11px; font-weight: 620; }
.feature-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
.feature-field { display: grid; gap: 4px; min-width: 0; color: #5c6c64; font-size: 10px; }
.feature-field.is-wide { grid-column: span 2; }
.feature-field > span { display: flex; justify-content: space-between; gap: 6px; }
.feature-field small { color: #93a29a; }
.feature-field :deep(.el-input-number) { width: 100%; }
.form-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 14px; padding-top: 11px; border-top: 1px solid var(--line-subtle); }
.form-footnote { margin: 6px 0 0; color: #93a29a; font-size: 10px; line-height: 1.6; }
.result-placeholder { display: grid; justify-items: center; gap: 6px; padding: 38px 12px; color: #859289; text-align: center; }
.result-placeholder .el-icon { font-size: 22px; }
.result-placeholder strong { color: #41544a; font-size: 12px; }
.result-placeholder span { font-size: 10px; }
.spin { animation: spin-rotate 1s linear infinite; }
@keyframes spin-rotate { to { transform: rotate(360deg); } }
.verdict { display: flex; align-items: center; gap: 10px; padding: 10px; border: 1px solid; border-radius: 7px; }
.verdict.is-delayed { background: #fff3ef; border-color: #f0c9bd; }
.verdict.is-ontime { background: #f0f7f2; border-color: #c8dfd1; }
.verdict-icon { display: grid; width: 32px; height: 32px; flex: 0 0 auto; place-content: center; color: #2f6a4f; background: #fff; border-radius: 50%; }
.verdict.is-delayed .verdict-icon { color: #b4523c; }
.verdict-copy { display: grid; flex: 1; gap: 2px; min-width: 0; }
.verdict-copy strong { color: #26352e; font-size: 15px; }
.verdict-copy span { color: #6b7a72; font-size: 10px; }
.risk-badge { flex: 0 0 auto; padding: 3px 7px; color: #4c5f55; background: #fff; border-radius: 999px; font-size: 10px; }
.risk-badge.is-high { color: #a34733; background: #ffe9e1; }
.risk-badge.is-mid { color: #8a6a1f; background: #fff6df; }
.risk-badge.is-low { color: #2f6a4f; background: #e8f3ec; }
.probability-meter { margin-top: 12px; }
.meter-track { position: relative; height: 10px; overflow: visible; background: #edf2ef; border-radius: 5px; }
.meter-fill { height: 100%; border-radius: 5px; transition: width 0.3s ease; }
.meter-fill.is-delayed { background: linear-gradient(90deg, #e2a08d, #c85c40); }
.meter-fill.is-ontime { background: linear-gradient(90deg, #9dc4ac, #3f7c5c); }
.meter-threshold { position: absolute; top: -3px; width: 2px; height: 16px; background: #4c5f55; opacity: 0.65; }
.meter-labels { display: flex; justify-content: space-between; margin-top: 4px; color: #93a29a; font-size: 9px; }
.narrative { margin: 10px 0 0; padding: 9px 10px; color: #3c5248; background: #f4f8f5; border-radius: 6px; font-size: 11px; line-height: 1.65; }
.result-meta-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; margin-top: 10px; }
.result-meta-grid > div { display: flex; align-items: baseline; justify-content: space-between; gap: 6px; padding: 6px 8px; background: #fbfcfb; border: 1px solid var(--line-subtle); border-radius: 5px; }
.result-meta-grid span { color: #7b8a82; font-size: 10px; }
.result-meta-grid strong { color: #263d33; font-size: 11px; }
.driver-block, .counterfactual-block { margin-top: 14px; }
.driver-block h3, .counterfactual-block h3 { margin: 0 0 7px; color: #41544a; font-size: 11px; font-weight: 620; }
.driver-row { display: grid; grid-template-columns: minmax(74px, 118px) minmax(0, 1fr) 58px; align-items: center; gap: 6px; margin-bottom: 4px; }
.driver-label { overflow: hidden; color: #5c6c64; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.driver-track { height: 9px; overflow: hidden; background: #edf2ef; border-radius: 5px; }
.driver-bar { height: 100%; border-radius: 5px; }
.driver-bar.is-up { background: #d9694f; }
.driver-bar.is-down { background: #4b7fb5; }
.driver-value { color: #4c5f55; font-size: 10px; text-align: right; }
.driver-note { margin: 3px 0 0; color: #93a29a; font-size: 9px; line-height: 1.6; }
.counterfactual-item { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 0; border-top: 1px dashed var(--line); color: #4c5f55; font-size: 10px; }
.prediction-message { display: flex; align-items: center; gap: 8px; padding: 8px 10px; color: #7a6127; background: #fff9e9; border: 1px solid #efe2b9; border-radius: 6px; font-size: 11px; }
.prediction-message span { flex: 1; }
.prediction-message.is-error { color: #8d3c35; background: #fff4f0; border-color: #f1d2c8; }
.prediction-empty { display: flex; align-items: center; gap: 12px; padding: 22px 16px; color: #6b7a72; background: #fff; border: 1px dashed var(--line-strong); border-radius: 8px; }
.prediction-empty .el-icon { font-size: 20px; color: #859289; }
.prediction-empty div { display: grid; flex: 1; gap: 3px; }
.prediction-empty strong { color: #2b4037; font-size: 12px; }
.prediction-empty span { font-size: 10px; }
.manifest-loading { padding: 24px 0; color: #7b8a82; font-size: 11px; text-align: center; }
.prediction-footer { margin: 0; color: #93a29a; font-size: 10px; }
.history-panel { margin-top: 2px; }
.history-table { font-size: 10px; }
.history-empty { padding: 18px 0; color: #93a29a; font-size: 10px; text-align: center; }
@media (max-width: 1080px) {
  .prediction-layout { grid-template-columns: minmax(0, 1fr); }
  .result-panel-sticky { position: static; }
}
@media (max-width: 760px) {
  .feature-grid { grid-template-columns: minmax(0, 1fr); }
  .feature-field.is-wide { grid-column: auto; }
  .form-actions { align-items: flex-start; flex-direction: row; flex-wrap: wrap; }
}
</style>
