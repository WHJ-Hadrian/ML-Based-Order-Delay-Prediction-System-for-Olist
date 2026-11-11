<template>
  <el-dialog
    v-model="open"
    :title="`单样本解释 · ${modelName || '模型'}`"
    width="min(920px, 94vw)"
    top="5vh"
    destroy-on-close
    @close="$emit('close')"
  >
    <div class="explain-body">
      <div class="explain-toolbar">
        <span class="explain-hint">修改下方特征取值后重新计算，可做 What-If 假设分析。</span>
        <div class="explain-actions">
          <el-button size="small" :loading="loading" type="primary" @click="compute(false)">计算解释</el-button>
          <el-button size="small" :disabled="loading" @click="resetForm">重置输入</el-button>
        </div>
      </div>

      <div v-if="error" class="explain-message is-error">{{ error }}</div>
      <div v-else-if="!features.length" class="explain-message">模型产物中没有可用的特征定义。</div>

      <div v-if="features.length" class="explain-grid">
        <section class="explain-card">
          <h4>输入特征</h4>
          <div class="feature-form">
            <label v-for="name in features" :key="name" class="feature-field">
              <span>{{ labelOf(name) }}<small>{{ typeOf(name) === 'numeric' ? '数值' : '类别' }}</small></span>
              <el-input-number
                v-if="typeOf(name) === 'numeric'"
                v-model="form[name]"
                :controls="false"
                size="small"
                :step="0.1"
                :precision="4"
              />
              <el-input v-else v-model="form[name]" size="small" maxlength="200" />
            </label>
          </div>
        </section>

        <section class="explain-card">
          <h4>预测结果</h4>
          <div v-if="result" class="prediction-summary">
            <div class="probability-ring" :class="{ 'is-positive': positive }">
              <strong>{{ percent(result.probability) }}</strong>
              <span>正类概率</span>
            </div>
            <div class="prediction-meta">
              <div><span>预测类别</span><strong>{{ result.prediction }}</strong></div>
              <div><span>判定阈值</span><strong>{{ result.threshold == null ? '默认' : number(result.threshold, 2) }}</strong></div>
              <div><span>置信度</span><strong>{{ percent(result.confidence) }}</strong></div>
              <div><span>解释方法</span><strong>{{ result.method }}</strong></div>
            </div>
          </div>
          <p v-else class="explain-empty">点击“计算解释”生成该样本的预测与归因。</p>

          <div v-if="result?.naturalLanguage" class="narrative">{{ result.naturalLanguage }}</div>

          <div v-if="contributions.length" class="contribution-list">
            <div v-for="item in contributions" :key="item.feature" class="contribution-row">
              <span class="contribution-label" :title="item.feature">{{ item.label || item.feature }}</span>
              <div class="contribution-track">
                <div
                  class="contribution-bar"
                  :class="item.contribution >= 0 ? 'is-positive' : 'is-negative'"
                  :style="{ width: `${barWidth(item.contribution)}%` }"
                />
              </div>
              <span class="contribution-value">{{ signed(item.contribution) }}</span>
            </div>
            <p class="contribution-note">红色表示推高预测，蓝色表示拉低预测；数值为该特征相对基线样本的平均影响。</p>
          </div>

          <div v-if="result?.counterfactuals?.length" class="counterfactual-list">
            <h4>反事实建议</h4>
            <div v-for="(item, index) in result.counterfactuals" :key="index" class="counterfactual-item">
              <span>{{ item.message }}</span>
              <el-button size="small" text type="primary" @click="applyCounterfactual(item)">应用并验证</el-button>
            </div>
          </div>
        </section>
      </div>

      <section v-if="result" class="explain-card whatif-card">
        <div class="whatif-heading">
          <h4>What-If 对比</h4>
          <div>
            <el-button size="small" :loading="loading" @click="compute(true)">按当前输入重新计算</el-button>
            <el-button v-if="whatIfResult" size="small" text @click="whatIfResult = null">清除对比</el-button>
          </div>
        </div>
        <div v-if="whatIfResult" class="whatif-grid">
          <div class="whatif-cell">
            <span>原始概率</span><strong>{{ percent(result.probability) }}</strong>
          </div>
          <div class="whatif-cell">
            <span>修改后概率</span><strong :class="deltaClass">{{ percent(whatIfResult.probability) }}</strong>
          </div>
          <div class="whatif-cell">
            <span>概率变化</span><strong :class="deltaClass">{{ signed(probabilityDelta) }}</strong>
          </div>
          <div class="whatif-cell">
            <span>结论</span><strong>{{ whatIfResult.prediction === result.prediction ? '未翻转' : '已翻转' }}</strong>
          </div>
        </div>
        <p v-else class="explain-empty">修改任一特征后点击“按当前输入重新计算”，即可对比原始概率与修改后概率。</p>
      </section>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { explainModelSample } from '@/api/models'

const props = defineProps({
  modelId: { type: String, required: true },
  modelName: { type: String, default: '' },
  manifest: { type: Object, default: null },
})
defineEmits(['close'])

const open = ref(true)
const loading = ref(false)
const error = ref('')
const result = ref(null)
const whatIfResult = ref(null)

const featureTypes = computed(() => (
  props.manifest?.featureTypes
  || props.manifest?.explainability?.featureTypes
  || {}
))
const features = computed(() => {
  const columns = props.manifest?.featureColumns || Object.keys(featureTypes.value)
  return [...columns]
})
const contributions = computed(() => result.value?.contributions || [])
const positive = computed(() => Number(result.value?.probability || 0) >= Number(result.value?.threshold ?? 0.5))
const probabilityDelta = computed(() => (
  whatIfResult.value && result.value
    ? Number(whatIfResult.value.probability || 0) - Number(result.value.probability || 0)
    : 0
))
const deltaClass = computed(() => probabilityDelta.value >= 0 ? 'is-up' : 'is-down')
const maxContribution = computed(() => Math.max(
  0.000001,
  ...contributions.value.map((item) => Math.abs(Number(item.contribution || 0))),
))

const sample = props.manifest?.explainability?.backgroundSample?.[0] || {}
const original = {}
for (const name of features.value) {
  const value = sample[name]
  original[name] = typeOf(name) === 'numeric'
    ? (value == null || value === '' ? 0 : Number(value))
    : (value == null ? '' : String(value))
}
const form = ref({ ...original })

function typeOf(name) {
  return featureTypes.value[name] === 'numeric' ? 'numeric' : 'categorical'
}

function labelOf(name) {
  return props.manifest?.explainability?.featureLabels?.[name] || name
}

function percent(value) {
  if (value == null || Number.isNaN(Number(value))) return 'N/A'
  return `${(Number(value) * 100).toFixed(1)}%`
}

function number(value, digits = 4) {
  if (value == null || Number.isNaN(Number(value))) return 'N/A'
  return Number(value).toFixed(digits)
}

function signed(value) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return 'N/A'
  return `${numeric >= 0 ? '+' : ''}${numeric.toFixed(4)}`
}

function barWidth(value) {
  return Math.min(100, Math.abs(Number(value || 0)) / maxContribution.value * 100)
}

async function compute(whatIf) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const payload = {
      rows: [{ ...form.value }],
      includeCounterfactuals: true,
    }
    const response = await explainModelSample(props.modelId, payload)
    const explanation = response?.results?.[0]
    if (!explanation) throw new Error('解释服务没有返回结果')
    if (whatIf) whatIfResult.value = explanation
    else {
      result.value = explanation
      whatIfResult.value = null
    }
  } catch (failure) {
    error.value = failure.message || '单样本解释失败'
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.value = { ...original }
  whatIfResult.value = null
  error.value = ''
}

function applyCounterfactual(item) {
  if (item?.feature) {
    form.value = {
      ...form.value,
      [item.feature]: typeOf(item.feature) === 'numeric' ? Number(item.to) : String(item.to),
    }
  }
  compute(true)
}
</script>

<style scoped>
.explain-body { display: grid; gap: 12px; }
.explain-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; }
.explain-hint { color: #6b7a72; font-size: 11px; }
.explain-actions { display: flex; gap: 6px; }
.explain-message { padding: 8px 10px; color: #7a6127; background: #fff9e9; border: 1px solid #efe2b9; border-radius: 6px; font-size: 11px; }
.explain-message.is-error { color: #8d3c35; background: #fff4f0; border-color: #f1d2c8; }
.explain-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr); gap: 12px; }
.explain-card { min-width: 0; padding: 10px; background: #fbfcfb; border: 1px solid #e3ebe6; border-radius: 8px; }
.explain-card h4 { margin: 0 0 8px; color: #2b4037; font-size: 12px; }
.feature-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px; max-height: 330px; overflow: auto; padding-right: 4px; }
.feature-field { display: grid; gap: 4px; min-width: 0; color: #5c6c64; font-size: 10px; }
.feature-field > span { display: flex; justify-content: space-between; gap: 6px; }
.feature-field small { color: #93a29a; }
.prediction-summary { display: flex; gap: 14px; align-items: center; }
.probability-ring { display: grid; width: 96px; height: 96px; flex: 0 0 auto; place-content: center; text-align: center; background: #eef4f1; border: 2px solid #c9dcd2; border-radius: 50%; }
.probability-ring strong { color: #2b4037; font-size: 18px; }
.probability-ring span { color: #6b7a72; font-size: 9px; }
.probability-ring.is-positive { background: #fff1ed; border-color: #f0c3b8; }
.prediction-meta { display: grid; flex: 1; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.prediction-meta > div { display: flex; align-items: baseline; justify-content: space-between; gap: 6px; padding: 5px 7px; background: #fff; border-radius: 5px; }
.prediction-meta span { color: #7b8a82; font-size: 10px; }
.prediction-meta strong { color: #263d33; font-size: 11px; }
.narrative { margin-top: 10px; padding: 8px 10px; color: #3c5248; background: #f1f6f3; border-radius: 6px; font-size: 11px; line-height: 1.65; }
.contribution-list { display: grid; gap: 4px; margin-top: 10px; }
.contribution-row { display: grid; grid-template-columns: minmax(70px, 110px) minmax(0, 1fr) 62px; align-items: center; gap: 6px; }
.contribution-label { overflow: hidden; color: #5c6c64; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.contribution-track { height: 10px; overflow: hidden; background: #edf2ef; border-radius: 5px; }
.contribution-bar { height: 100%; border-radius: 5px; }
.contribution-bar.is-positive { background: #d9694f; }
.contribution-bar.is-negative { background: #4b7fb5; }
.contribution-value { color: #4c5f55; font-size: 10px; text-align: right; }
.contribution-note { margin: 2px 0 0; color: #8b988f; font-size: 9px; }
.counterfactual-list { margin-top: 12px; }
.counterfactual-item { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 0; border-top: 1px dashed #e3ebe6; font-size: 10px; color: #4c5f55; }
.whatif-card { margin-top: 2px; }
.whatif-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.whatif-heading h4 { margin: 0; }
.whatif-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin-top: 8px; }
.whatif-cell { display: grid; gap: 3px; padding: 8px; background: #fff; border-radius: 6px; }
.whatif-cell span { color: #7b8a82; font-size: 10px; }
.whatif-cell strong { color: #263d33; font-size: 14px; }
.whatif-cell strong.is-up { color: #b4523c; }
.whatif-cell strong.is-down { color: #3d6f9f; }
.explain-empty { margin: 4px 0 0; color: #8b988f; font-size: 11px; }
@media (max-width: 760px) {
  .explain-grid { grid-template-columns: minmax(0, 1fr); }
  .whatif-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
