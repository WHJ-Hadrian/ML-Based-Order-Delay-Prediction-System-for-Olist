<template>
  <div class="curve-chart">
    <div class="curve-heading"><span>{{ title }}</span><small>{{ subtitle }}</small></div>
    <div ref="chartElement" class="curve-canvas" />
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps({
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  xLabel: { type: String, default: '' },
  yLabel: { type: String, default: '' },
  series: { type: Array, default: () => [] },
})

const chartElement = ref(null)
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
  if (!chartElement.value || !props.series.length) return
  chart = echarts.init(chartElement.value)
  const axisStyle = {
    axisLabel: { color: '#66766e', fontSize: 10 },
    splitLine: { lineStyle: { color: '#e8eeeb' } },
    nameTextStyle: { color: '#7b8a82', fontSize: 10 },
  }
  chart.setOption({
    animation: false,
    grid: { left: 8, right: 14, top: props.series.length > 1 ? 30 : 14, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      confine: true,
      textStyle: { fontSize: 11 },
    },
    legend: props.series.length > 1
      ? { top: 0, left: 0, itemWidth: 10, itemHeight: 10, textStyle: { color: '#66766e', fontSize: 10 } }
      : undefined,
    xAxis: { type: 'value', name: props.xLabel, min: 0, max: 1, ...axisStyle },
    yAxis: { type: 'value', name: props.yLabel, min: 0, max: 1, ...axisStyle },
    series: props.series.map((item) => ({
      name: item.name,
      type: 'line',
      showSymbol: false,
      smooth: false,
      lineStyle: { width: 2, color: item.color },
      itemStyle: { color: item.color },
      data: item.data,
    })),
  })
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(chartElement.value)
}

onMounted(() => nextTick(renderChart))
watch(() => props.series, () => nextTick(renderChart), { deep: true })
onBeforeUnmount(disposeChart)
</script>

<style scoped>
.curve-chart { min-width: 0; }
.curve-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 2px; }
.curve-heading span { color: #2b4037; font-size: 11px; font-weight: 650; }
.curve-heading small { color: #7c8983; font-size: 9px; }
.curve-canvas { width: 100%; height: 170px; }
</style>
