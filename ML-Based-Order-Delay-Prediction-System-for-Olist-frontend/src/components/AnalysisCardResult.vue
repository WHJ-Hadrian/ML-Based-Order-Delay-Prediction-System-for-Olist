<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, ScatterChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, ScatterChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps({
  card: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['display-mode-change'])
const chartElement = ref(null)
const mode = computed(() => props.card.displayMode === 'table' ? 'table' : 'chart')
const points = computed(() => props.card.points || [])
const scatter = computed(() => props.card.chartType === 'scatter')
const grouped = computed(() => Boolean(props.card.relationColumnId) && !scatter.value)
const options = [{ label: '图表', value: 'chart' }, { label: '数据', value: 'table' }]
const headings = computed(() => {
  if (scatter.value) return [props.card.xColumnName || 'X', props.card.relationColumnName || 'Y']
  if (grouped.value) return [props.card.groupColumnName || '分组', `平均 ${props.card.valueColumnName || '数值'}`, '有效记录数']
  return [props.card.chartType === 'histogram' ? '区间' : '类别', '记录数']
})
const rows = computed(() => points.value.map((point) => {
  if (scatter.value) return [point.x, point.y]
  if (grouped.value) return [point.label, point.y, point.rowCount]
  return [point.label, point.value]
}))
const resultScope = computed(() => {
  if (scatter.value) return `${points.value.length} 个确定性样本点`
  if (grouped.value) return `${points.value.length} 个分组 · 分组均值`
  return props.card.chartType === 'histogram'
    ? `${points.value.length} 个区间`
    : `${points.value.length} 个高频类别`
})
let chart
let observer

function disposeChart() {
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
}

function renderChart() {
  disposeChart()
  if (!chartElement.value || !points.value.length || props.card.error) return
  chart = echarts.init(chartElement.value)
  const axisStyle = { axisLabel: { color: '#66766e', fontSize: 10 }, splitLine: { lineStyle: { color: '#e8eeeb' } } }
  chart.setOption({
    animation: false,
    color: [scatter.value ? '#247f88' : '#367d64'],
    grid: { left: 12, right: 16, top: 12, bottom: 12, containLabel: true },
    tooltip: {
      trigger: 'item',
      renderMode: 'richText',
      confine: true,
      textStyle: { fontSize: 11, width: 190, overflow: 'breakAll' },
      formatter: (item) => rows.value[item.dataIndex]
        .map((value, index) => `${headings.value[index]}: ${value ?? '—'}`).join('\n'),
    },
    xAxis: scatter.value
      ? { type: 'value', ...axisStyle }
      : { type: 'value', ...axisStyle },
    yAxis: scatter.value
      ? { type: 'value', ...axisStyle }
      : {
          type: 'category', inverse: true,
          data: points.value.map((point) => point.label),
          axisLabel: { color: '#66766e', fontSize: 10, width: 80, overflow: 'truncate', hideOverlap: true },
          axisTick: { show: false }, axisLine: { show: false },
        },
    series: [{
      type: scatter.value ? 'scatter' : 'bar',
      data: scatter.value
        ? points.value.map((point) => [point.x, point.y])
        : points.value.map((point) => grouped.value ? point.y : point.value),
      barMaxWidth: 16,
      symbolSize: 6,
    }],
  })
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(chartElement.value)
}

watch([chartElement, points, () => props.card.chartType, () => props.card.error], renderChart, { flush: 'post' })
onBeforeUnmount(disposeChart)
</script>

<template>
  <div class="card-result">
    <div class="result-toolbar">
      <el-segmented :model-value="mode" :options="options" :disabled="disabled" size="small" aria-label="分析卡片视图"
        @change="emit('display-mode-change', $event)" />
      <span :title="resultScope">{{ resultScope }}</span>
    </div>
    <el-empty v-if="card.error || !points.length" :description="card.error || '没有可展示的数据'" :image-size="42" />
    <div v-else-if="mode === 'table'" class="result-table-wrap" tabindex="0" role="region" :aria-label="`${card.columnName} 分析数据`">
      <table class="result-table">
        <caption class="sr-only">{{ card.columnName }} · {{ resultScope }}</caption>
        <thead><tr><th v-for="(heading, index) in headings" :key="index" scope="col" :title="heading">{{ heading }}</th></tr></thead>
        <tbody>
          <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
            <td v-for="(value, columnIndex) in row" :key="columnIndex" :title="String(value ?? '')">{{ value ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else ref="chartElement" class="result-chart" role="img" :aria-label="`${card.columnName} · ${resultScope}`"></div>
  </div>
</template>

<style scoped>
.card-result { min-width: 0; margin-top: 10px; }
.result-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; margin-bottom: 10px; }
.result-toolbar :deep(.el-segmented) { flex-shrink: 0; }
.result-toolbar > span { min-width: 0; overflow: hidden; color: #718178; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.result-chart { width: 100%; height: 240px; min-width: 0; }
.result-table-wrap { height: 240px; max-width: 100%; overflow: auto; overscroll-behavior: contain; border: 1px solid #e4ebe7; border-radius: 5px; }
.result-table-wrap:focus-visible { outline: 2px solid #367d64; outline-offset: 2px; }
.result-table { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: 11px; font-variant-numeric: tabular-nums; }
.result-table th, .result-table td { padding: 7px 8px; border-bottom: 1px solid #edf1ef; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.result-table th { position: sticky; top: 0; background: #f5f8f6; color: #607069; font-weight: 600; }
.result-table td { color: #394c43; }
.card-result :deep(.el-empty__description) { max-width: 100%; overflow-wrap: anywhere; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
</style>
