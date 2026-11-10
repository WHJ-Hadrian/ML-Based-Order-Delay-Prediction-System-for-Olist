import { computed, reactive } from 'vue'
import { findNavigationItemByPath } from '@/config/navigation'

const STORAGE_KEY = 'seekdataset-workbench'

function readStoredState() {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}')
    const openPaths = Array.isArray(stored.openPaths)
      ? [...new Set(stored.openPaths.filter((path) => typeof path === 'string' && findNavigationItemByPath(path).path === path))]
      : []
    return {
      openPaths,
      bottomPanelOpen: stored.bottomPanelOpen === true,
    }
  } catch {
    return { openPaths: [], bottomPanelOpen: true }
  }
}

const stored = typeof localStorage === 'undefined' ? { openPaths: [], bottomPanelOpen: false } : readStoredState()
const state = reactive({
  openPaths: stored.openPaths,
  bottomPanelOpen: stored.bottomPanelOpen,
})

function persist() {
  if (typeof localStorage === 'undefined') return
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      openPaths: state.openPaths,
      bottomPanelOpen: state.bottomPanelOpen,
    }))
  } catch {
    // Keep the workbench usable when browser storage is disabled or full.
  }
}

function openDocument(path) {
  const item = findNavigationItemByPath(path)
  if (!item || item.path !== path) return
  if (!state.openPaths.includes(path)) state.openPaths.push(path)
  persist()
}

function closeDocument(path, activePath) {
  const index = state.openPaths.indexOf(path)
  if (index < 0) return activePath
  state.openPaths.splice(index, 1)
  persist()
  if (path !== activePath) return activePath
      return state.openPaths[index] || state.openPaths[index - 1] || '/workspace'
}

function toggleBottomPanel() {
  state.bottomPanelOpen = !state.bottomPanelOpen
  persist()
}

export function useWorkbench() {
  const documents = computed(() => state.openPaths.map(findNavigationItemByPath))
  return {
    state,
    documents,
    openDocument,
    closeDocument,
    toggleBottomPanel,
  }
}
