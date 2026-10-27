import { readonly, ref } from 'vue'

export const APPEARANCE_STORAGE_KEY = 'seekdateset.appearance.v1'

export const DEFAULT_APPEARANCE = Object.freeze({
  backgroundMotion: true,
  watermark: true,
  sidebarCollapsed: false,
})

export function createDefaultAppearance() {
  return { ...DEFAULT_APPEARANCE }
}

export function normalizeAppearance(raw) {
  const source = raw && typeof raw === 'object' ? raw : {}
  return {
    backgroundMotion: source.backgroundMotion !== false,
    watermark: source.watermark !== false,
    sidebarCollapsed: source.sidebarCollapsed === true,
  }
}

export function cloneAppearance(value) {
  return normalizeAppearance(JSON.parse(JSON.stringify(value || {})))
}

function readStoredAppearance() {
  try {
    const raw = window.localStorage.getItem(APPEARANCE_STORAGE_KEY)
    return normalizeAppearance(raw ? JSON.parse(raw) : null)
  } catch {
    return createDefaultAppearance()
  }
}

const appearance = ref(readStoredAppearance())

export function saveAppearance(nextAppearance) {
  const normalized = normalizeAppearance(nextAppearance)
  appearance.value = normalized
  try {
    window.localStorage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(normalized))
  } catch {
    // Storage may be unavailable; the in-memory setting still applies.
  }
  return normalized
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key === APPEARANCE_STORAGE_KEY) {
      appearance.value = readStoredAppearance()
    }
  })
}

export function useAppearance() {
  return {
    appearance: readonly(appearance),
    saveAppearance,
  }
}
