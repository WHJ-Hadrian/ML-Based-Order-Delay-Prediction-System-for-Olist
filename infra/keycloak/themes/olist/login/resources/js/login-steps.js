const form = document.getElementById('kc-form-login')

if (form) {
  const accountStep = document.getElementById('kc-account-step')
  const emailStep = document.getElementById('kc-email-step')
  const passwordStep = document.getElementById('kc-password-step')
  const emailInput = document.getElementById('username')
  const passwordInput = document.getElementById('password')
  const continueButton = document.getElementById('kc-email-continue')
  const backButton = document.getElementById('kc-email-back')
  const accountBackButton = document.getElementById('kc-account-chooser-back')
  const emailSummary = document.getElementById('kc-account-email')
  const savedAccountList = document.getElementById('kc-saved-accounts')
  const otherAccountButton = document.getElementById('kc-account-other')
  const manageAccountsButton = document.getElementById('kc-account-manage')
  const rememberAccountInput = document.getElementById('kc-remember-account')
  const hasEmailStep = Boolean(emailStep && emailInput)
  const accountStorageKey = 'olist.keycloak.saved-accounts.v1'
  const maxSavedAccounts = 5
  const emailPattern = /^[^\s@]+@[^\s@]+$/
  let savedAccounts = readSavedAccounts()
  let managingAccounts = false

  form.classList.add('auth-flow-enabled')

  function isValidEmail(value) {
    return typeof value === 'string' && value.length <= 254 && emailPattern.test(value)
  }

  function readSavedAccounts() {
    try {
      const parsed = JSON.parse(window.localStorage.getItem(accountStorageKey) || '[]')
      if (!Array.isArray(parsed)) return []

      const seen = new Set()
      return parsed.filter((value) => {
        if (!isValidEmail(value)) return false
        const normalized = value.toLowerCase()
        if (seen.has(normalized)) return false
        seen.add(normalized)
        return true
      }).slice(0, maxSavedAccounts)
    } catch {
      return []
    }
  }

  function persistSavedAccounts(accounts) {
    savedAccounts = accounts.slice(0, maxSavedAccounts)
    try {
      window.localStorage.setItem(accountStorageKey, JSON.stringify(savedAccounts))
    } catch {
      // Login remains available when browser storage is disabled or full.
    }
  }

  function removeSavedAccount(email) {
    const normalized = email.toLowerCase()
    persistSavedAccounts(savedAccounts.filter((account) => account.toLowerCase() !== normalized))
    if (savedAccounts.length === 0) managingAccounts = false
    renderSavedAccounts()
    if (savedAccounts.length === 0) showStep('email')
  }

  function renderSavedAccounts() {
    if (!savedAccountList) return
    savedAccountList.replaceChildren()

    for (const email of savedAccounts) {
      const row = document.createElement('div')
      row.className = 'auth-saved-account'
      row.setAttribute('role', 'listitem')

      const selectButton = document.createElement('button')
      selectButton.type = 'button'
      selectButton.className = 'auth-saved-account-select'
      selectButton.setAttribute('aria-label', email)

      const avatar = document.createElement('span')
      avatar.className = 'auth-account-avatar'
      avatar.setAttribute('aria-hidden', 'true')
      avatar.textContent = email.charAt(0).toUpperCase()

      const identity = document.createElement('span')
      identity.className = 'auth-account-identity'

      const accountEmail = document.createElement('span')
      accountEmail.className = 'auth-account-email'
      accountEmail.textContent = email

      const accountStatus = document.createElement('span')
      accountStatus.className = 'auth-account-status'
      accountStatus.textContent = savedAccountList.dataset.savedLabel || ''

      identity.append(accountEmail, accountStatus)
      selectButton.append(avatar, identity)
      selectButton.addEventListener('click', () => {
        emailInput.value = email
        if (rememberAccountInput) rememberAccountInput.checked = true
        showStep('password')
      })
      row.append(selectButton)

      if (managingAccounts) {
        const removeButton = document.createElement('button')
        removeButton.type = 'button'
        removeButton.className = 'auth-account-remove'
        removeButton.textContent = savedAccountList.dataset.removeLabel || ''
        removeButton.setAttribute('aria-label', `${removeButton.textContent}: ${email}`)
        removeButton.addEventListener('click', () => removeSavedAccount(email))
        row.append(removeButton)
      }

      savedAccountList.append(row)
    }

    if (manageAccountsButton) {
      manageAccountsButton.textContent = managingAccounts
        ? manageAccountsButton.dataset.doneLabel
        : manageAccountsButton.dataset.removeLabel
      manageAccountsButton.setAttribute('aria-pressed', String(managingAccounts))
    }
  }

  function showStep(step) {
    if (step === 'chooser' && savedAccounts.length === 0) step = 'email'
    if (accountStep) accountStep.hidden = step !== 'chooser'
    if (emailStep) emailStep.hidden = step !== 'email'
    if (passwordStep) passwordStep.hidden = step !== 'password'
    if (emailSummary && emailInput) emailSummary.textContent = emailInput.value
    if (backButton) {
      backButton.hidden = step !== 'password'
      backButton.textContent = savedAccounts.length > 0
        ? backButton.dataset.chooserLabel
        : backButton.dataset.emailLabel
    }
    if (accountBackButton) accountBackButton.hidden = step !== 'email' || savedAccounts.length === 0

    const focusTarget = step === 'email'
      ? emailInput
      : step === 'password'
        ? passwordInput
        : savedAccountList?.querySelector('.auth-saved-account-select') || otherAccountButton
    focusTarget?.focus()
  }

  if (hasEmailStep) {
    emailInput.type = 'email'
    emailInput.required = true

    const continueToPassword = () => {
      emailInput.value = emailInput.value.trim()
      if (!emailInput.reportValidity()) return
      showStep('password')
    }

    continueButton?.addEventListener('click', continueToPassword)
    backButton?.addEventListener('click', () => {
      showStep(savedAccounts.length > 0 ? 'chooser' : 'email')
    })
    accountBackButton?.addEventListener('click', () => showStep('chooser'))
    otherAccountButton?.addEventListener('click', () => {
      if (rememberAccountInput) rememberAccountInput.checked = true
      showStep('email')
    })
    manageAccountsButton?.addEventListener('click', () => {
      managingAccounts = !managingAccounts
      renderSavedAccounts()
    })

    emailInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        event.preventDefault()
        continueToPassword()
      }
    })

    renderSavedAccounts()
    const hasPasswordError = form.dataset.initialStep === 'password'
    const hasUsernameError = form.dataset.usernameError === 'true'
    let initialStep = 'email'
    if (hasPasswordError) {
      initialStep = 'password'
    } else if (!hasUsernameError && savedAccounts.length > 0) {
      initialStep = 'chooser'
    } else if (!hasUsernameError && emailInput.value) {
      initialStep = 'password'
    }
    showStep(initialStep)
  } else {
    if (passwordStep) passwordStep.hidden = false
    passwordInput?.focus()
  }

  form.addEventListener('submit', (event) => {
    if (hasEmailStep && !emailStep.hidden) {
      event.preventDefault()
      continueButton?.click()
      return
    }

    if (accountStep && !accountStep.hidden) {
      event.preventDefault()
      return
    }

    if (passwordInput) {
      passwordInput.required = true
      if (!passwordInput.reportValidity()) {
        event.preventDefault()
        return
      }
    }

    const email = emailInput?.value.trim()
    if (!hasEmailStep || !isValidEmail(email)) return

    const existingAccounts = savedAccounts.filter((account) => account.toLowerCase() !== email.toLowerCase())
    persistSavedAccounts(rememberAccountInput?.checked
      ? [email, ...existingAccounts]
      : existingAccounts)
  })
}
