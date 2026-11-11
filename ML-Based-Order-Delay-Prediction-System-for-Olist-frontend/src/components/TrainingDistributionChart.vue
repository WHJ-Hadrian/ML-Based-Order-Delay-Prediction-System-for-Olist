<template>
  <div class="dist-chart">
    <div class="dist-heading"><span>{{ title }}</span><small>{{ subtitle }}</small></div>
    <div ref="chartElement" class="dist-canvas" />
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  labels: { type: Array, default: () => [] },
  values: { type: Array, default: () => [] },
})

const chartElement = ref(null)
let chart
let observer

function renderChart() {
  observer?.disconnect()
  chart?.dispose()
  chart = null
  if (!chartElement.value || !props.values.length) return
  chart = echarts.init(chartElement.value)
  chart.setOption({
    animation: false,
    grid: { left: 8, right: 14, top: 10, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, textStyle: { fontSize: 11 } },
    xAxis: {
      type: 'category',
      data: props.labels,
      axisLabel: { color: '#66766e', fontSize: 9, hideOverlap: true },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#66766e', fontSize: 9 },
      splitLine: { lineStyle: { color: '#e8eeeb' } },
    },
    series: [{ type: 'bar', data: props.values, itemStyle: { color: '#6b9c86' }, barMaxWidth: 18 }],
  })
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(chartElement.value)
}

onMounted(() => nextTick(renderChart))
watch([() => props.labels, () => props.values], () => nextTick(renderChart), { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.dist-chart { min-width: 0; }
.dist-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 2px; }
.dist-heading span { color: #2b4037; font-size: 11px; font-weight: 650; }
.dist-heading small { color: #7c8983; font-size: 9px; }
.dist-canvas { width: 100%; height: 170px; }
</style>
