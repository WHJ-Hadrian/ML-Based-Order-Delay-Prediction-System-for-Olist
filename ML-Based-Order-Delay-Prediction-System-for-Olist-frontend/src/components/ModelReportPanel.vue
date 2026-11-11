<template>
  <el-dialog
    v-model="open"
    :title="`导出评估报告 · ${modelName || '模型'}`"
    width="min(760px, 94vw)"
    top="8vh"
    destroy-on-close
    @close="$emit('close')"
  >
    <div class="report-body">
      <label class="report-field">
        <span>报告标题</span>
        <el-input v-model="title" maxlength="200" size="small" />
      </label>
      <div class="report-field">
        <span>导出格式</span>
        <el-radio-group v-model="format" size="small">
          <el-radio-button value="pdf">PDF</el-radio-button>
          <el-radio-button value="docx">Word</el-radio-button>
          <el-radio-button value="html">HTML</el-radio-button>
          <el-radio-button value="json">JSON</el-radio-button>
          <el-radio-button value="csv">CSV</el-radio-button>
        </el-radio-group>
      </div>
      <div class="report-field">
        <span>包含内容</span>
        <el-checkbox-group v-model="sections" size="small">
          <el-checkbox value="overview">模型概况</el-checkbox>
          <el-checkbox value="detail">评估指标</el-checkbox>
          <el-checkbox value="comparison">模型对比</el-checkbox>
          <el-checkbox value="importance">特征重要性</el-checkbox>
          <el-checkbox value="sample">单样本解释</el-checkbox>
          <el-checkbox value="conclusion">结论摘要</el-checkbox>
        </el-checkbox-group>
      </div>

      <div v-if="error" class="report-message is-error">{{ error }}</div>
      <div v-else-if="notice" class="report-message is-success">{{ notice }}</div>

      <div class="report-actions">
        <el-button type="primary" size="small" :loading="busy" :disabled="!sections.length" @click="generate">生成并下载</el-button>
        <span class="report-hint">报告由训练产物中的指标、曲线、特征重要性与解释结果汇总生成。</span>
      </div>

      <section class="report-records">
        <div class="records-heading">
          <h4>导出记录</h4>
          <el-button size="small" text :loading="recordsLoading" @click="loadRecords">刷新</el-button>
        </div>
        <el-table v-if="records.length" :data="records" size="small" max-height="220">
          <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
          <el-table-column prop="format" label="格式" width="80" />
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <p v-else class="records-empty">暂无导出记录。</p>
      </section>
    </div>
  </el-dialog>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { downloadModelReport, listModelReports } from '@/api/models'

const props = defineProps({
  modelId: { type: String, required: true },
  modelName: { type: String, default: '' },
  manifest: { type: Object, default: null },
  comparison: { type: Array, default: () => [] },
})
defineEmits(['close'])

const open = ref(true)
const format = ref('pdf')
const title = ref(`${props.modelName || '模型'} 评估报告`)
const sections = ref(['overview', 'detail', 'comparison', 'importance', 'sample', 'conclusion'])
const busy = ref(false)
const error = ref('')
const notice = ref('')
const records = ref([])
const recordsLoading = ref(false)

function formatTime(value) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
    : '—'
}

async function generate() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const { blob, fileName } = await downloadModelReport(props.modelId, {
      format: format.value,
      sections: [...sections.value],
      title: title.value.trim() || '模型评估报告',
      comparison: props.comparison,
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 3000)
    notice.value = `报告已生成：${fileName}`
    await loadRecords()
  } catch (failure) {
    error.value = failure.message || '报告生成失败'
  } finally {
    busy.value = false
  }
}

async function loadRecords() {
  recordsLoading.value = true
  try {
    records.value = await listModelReports(props.modelId)
  } catch {
    records.value = []
  } finally {
    recordsLoading.value = false
  }
}

onMounted(loadRecords)
</script>

<style scoped>
.report-body { display: grid; gap: 12px; }
.report-field { display: grid; gap: 6px; color: #5c6c64; font-size: 11px; }
.report-field > span { font-weight: 600; }
.report-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.report-hint { color: #8b988f; font-size: 10px; }
.report-message { padding: 8px 10px; border-radius: 6px; font-size: 11px; }
.report-message.is-error { color: #8d3c35; background: #fff4f0; border: 1px solid #f1d2c8; }
.report-message.is-success { color: #2f6b4c; background: #f1f8f3; border: 1px solid #cfe5d6; }
.report-records { padding-top: 8px; border-top: 1px solid #e3ebe6; }
.records-heading { display: flex; align-items: center; justify-content: space-between; }
.records-heading h4 { margin: 0; color: #2b4037; font-size: 12px; }
.records-empty { margin: 6px 0 0; color: #8b988f; font-size: 11px; }
</style>
