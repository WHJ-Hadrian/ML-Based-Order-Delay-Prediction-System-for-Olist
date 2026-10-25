const form = document.getElementById('kc-register-form')

if (form) {
  const groups = [...form.querySelectorAll('#kc-register-fields .pf-v5-c-form__group')]
  const accountGroups = []
  const detailGroups = []

  for (const group of groups) {
    const fields = [...group.querySelectorAll('input, select, textarea')]
    if (fields.length === 0) continue

    const isAccountField = fields.some((field) => ['email', 'username'].includes(field.name))
    group.dataset.authStep = isAccountField ? 'account' : 'details'
    if (isAccountField) accountGroups.push(group)
    else detailGroups.push(group)
  }

  if (accountGroups.length > 0) {
    const accountFields = accountGroups.flatMap((group) => [...group.querySelectorAll('input, select, textarea')])
    const emailInput = accountFields.find((field) => field.name === 'email') || accountFields.find((field) => field.name === 'username')
    const continueButton = document.getElementById('kc-register-continue')
    const backButton = document.getElementById('kc-register-back')
    const accountActions = document.getElementById('kc-register-email-actions')
    const details = document.getElementById('kc-register-details')
    const detailsHeading = document.getElementById('kc-register-details-title')
    const emailHeading = document.getElementById('kc-register-email-title')
    const emailDescription = document.getElementById('kc-register-email-description')
    const emailSummary = document.getElementById('kc-register-account-email')

    form.classList.add('auth-flow-enabled')
    for (const field of accountFields) {
      if (field.type === 'hidden' || field.disabled) continue
      field.required = true
      if (field.name === 'email') field.type = 'email'
    }

    const showStep = (step) => {
      for (const group of accountGroups) group.hidden = step !== 'email'
      for (const group of detailGroups) group.hidden = step !== 'details'
      accountActions.hidden = step !== 'email'
      details.hidden = step !== 'details'
      detailsHeading.hidden = step !== 'details'
      emailHeading.hidden = step !== 'email'
      emailDescription.hidden = step !== 'email'
      emailSummary.textContent = emailInput?.value || ''
      if (backButton) backButton.hidden = step !== 'details'

      const target = step === 'email' ? emailInput : detailGroups[0]?.querySelector('input, select, textarea')
      target?.focus()
    }

    const continueToDetails = () => {
      const invalidField = accountFields.find((field) => field.type !== 'hidden' && !field.disabled && !field.checkValidity())
      if (invalidField) {
        invalidField.reportValidity()
        return
      }
      showStep('details')
    }

    continueButton.addEventListener('click', continueToDetails)
    backButton.addEventListener('click', () => showStep('email'))
    for (const field of accountFields) {
      field.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          continueToDetails()
        }
      })
    }

    const invalidField = form.querySelector('[aria-invalid="true"]')
    const invalidGroup = invalidField?.closest('.pf-v5-c-form__group')
    showStep(invalidGroup?.dataset.authStep === 'details' ? 'details' : 'email')
  }
}
