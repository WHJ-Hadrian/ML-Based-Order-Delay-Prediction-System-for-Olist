<template>
  <div class="scatter-chart">
    <div class="scatter-heading"><span>{{ title }}</span><small>{{ subtitle }}</small></div>
    <div ref="chartElement" class="scatter-canvas" />
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, ScatterChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, ScatterChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  xLabel: { type: String, default: '' },
  yLabel: { type: String, default: '' },
  points: { type: Array, default: () => [] },
  line: { type: Array, default: null },
})

const chartElement = ref(null)
let chart
let observer

function renderChart() {
  observer?.disconnect()
  chart?.dispose()
  chart = null
  if (!chartElement.value || !props.points.length) return
  chart = echarts.init(chartElement.value)
  const axisStyle = {
    axisLabel: { color: '#66766e', fontSize: 10 },
    splitLine: { lineStyle: { color: '#e8eeeb' } },
    nameTextStyle: { color: '#7b8a82', fontSize: 10 },
  }
  const series = [{
    name: '样本',
    type: 'scatter',
    symbolSize: 5,
    itemStyle: { color: '#4b7fb5', opacity: 0.7 },
    data: props.points,
  }]
  if (props.line?.length) {
    series.push({
      name: '参考线',
      type: 'line',
      showSymbol: false,
      lineStyle: { color: '#c0644b', width: 1, type: 'dashed' },
      data: props.line,
    })
  }
  chart.setOption({
    animation: false,
    grid: { left: 8, right: 14, top: 10, bottom: 8, containLabel: true },
    tooltip: { trigger: 'item', renderMode: 'richText', confine: true, textStyle: { fontSize: 11 } },
    xAxis: { type: 'value', name: props.xLabel, ...axisStyle, scale: true },
    yAxis: { type: 'value', name: props.yLabel, ...axisStyle, scale: true },
    series,
  })
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(chartElement.value)
}

onMounted(() => nextTick(renderChart))
watch([() => props.points, () => props.line], () => nextTick(renderChart), { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.scatter-chart { min-width: 0; }
.scatter-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 2px; }
.scatter-heading span { color: #2b4037; font-size: 11px; font-weight: 650; }
.scatter-heading small { color: #7c8983; font-size: 9px; }
.scatter-canvas { width: 100%; height: 170px; }
</style>
