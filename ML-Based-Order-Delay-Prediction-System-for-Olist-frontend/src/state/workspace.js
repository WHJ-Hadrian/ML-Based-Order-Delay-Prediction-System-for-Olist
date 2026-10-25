import { inject, provide, ref } from 'vue'

export const DATASET_TOTAL_KEY = Symbol('dataset-total')

export function provideWorkspaceState() {
  const datasetTotal = ref(0)
  provide(DATASET_TOTAL_KEY, datasetTotal)
  return { datasetTotal }
}

export function useDatasetTotal() {
  return inject(DATASET_TOTAL_KEY, ref(0))
}
