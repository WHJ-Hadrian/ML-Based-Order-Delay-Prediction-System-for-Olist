import { readonly, ref } from 'vue'
import {
  SOFT_TIPS_STORAGE_KEY,
  normalizeSoftTipSettings,
} from '@/config/softTips'

function readStoredSettings() {
  try {
    const raw = window.localStorage.getItem(SOFT_TIPS_STORAGE_KEY)
    return normalizeSoftTipSettings(raw ? JSON.parse(raw) : null)
  } catch {
    return normalizeSoftTipSettings(null)
  }
}

const settings = ref(readStoredSettings())

export function saveSoftTipSettings(nextSettings) {
  const normalized = normalizeSoftTipSettings(nextSettings)
  settings.value = normalized
  try {
    window.localStorage.setItem(SOFT_TIPS_STORAGE_KEY, JSON.stringify(normalized))
  } catch {
    // Storage may be unavailable; the in-memory setting still applies.
  }
  return normalized
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (event.key === SOFT_TIPS_STORAGE_KEY) {
      settings.value = readStoredSettings()
    }
  })
}

export function useSoftTipSettings() {
  return {
    settings: readonly(settings),
    saveSoftTipSettings,
  }
}
