<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import brandArtwork from '@/assets/img/chahua.png'
import {
  createRegisteredAccount,
  requestRegistrationCode,
  verifyRegistrationCode,
} from '@/api/registration'
import { loginWithPassword } from '@/auth/keycloak'


const stages = Object.freeze({
  identifier: 'identifier',
  password: 'password',
  code: 'code',
  name: 'name',
  birthday: 'birthday',
  complete: 'complete',
})
const CODE_RESEND_DELAY_SECONDS = 60

const router = useRouter()

function initialQueryEmail() {
  return new URLSearchParams(window.location.search).get('email') || ''
}

const isRegistration = ref(false)
const stage = ref(stages.identifier)
const email = ref(initialQueryEmail())
const password = ref('')
const confirmPassword = ref('')
const familyName = ref('')
const givenName = ref('')
const displayName = ref('')
const birthYear = ref('')
const birthMonth = ref('')
const birthDay = ref('')
const gender = ref('')
const registrationCode = ref('')
const verificationToken = ref('')
const message = ref('')
const messageTone = ref('error')
const isSubmitting = ref(false)
const resendSeconds = ref(0)
const loginProfile = ref(null)
const accountInitial = computed(() => (displayName.value || email.value || 'U').charAt(0).toUpperCase())
const isBusy = computed(() => isSubmitting.value)
const genderOptions = [
  { value: 'female', label: '女' },
  { value: 'male', label: '男' },
  { value: 'other', label: '其他' },
  { value: 'unspecified', label: '不愿透露' },
]

const currentTitle = computed(() => {
  if (stage.value === stages.code) return '验证您的邮箱'
  if (stage.value === stages.name) return '创建您的 SeekDateSet 账号'
  if (stage.value === stages.birthday) return '基本信息'
  if (stage.value === stages.complete) return '账号已创建'
  if (stage.value === stages.password) {
    return isRegistration.value ? '创建密码' : (loginProfile.value?.displayName || '欢迎')
  }
  return isRegistration.value ? '创建账号' : '登录'
})

let resendInterval = null

function setMessage(text, tone = 'error') {
  message.value = text
  messageTone.value = tone
}

function clearResendCountdown() {
  if (resendInterval !== null) {
    window.clearInterval(resendInterval)
    resendInterval = null
  }
}

function resetMessages() {
  message.value = ''
  messageTone.value = 'error'
}

function resetRegistrationFields() {
  familyName.value = ''
  givenName.value = ''
  displayName.value = ''
  birthYear.value = ''
  birthMonth.value = ''
  birthDay.value = ''
  gender.value = ''
  registrationCode.value = ''
  verificationToken.value = ''
}


onBeforeUnmount(() => {
  clearResendCountdown()
})


function startResendCountdown() {
  clearResendCountdown()
  resendSeconds.value = CODE_RESEND_DELAY_SECONDS
  resendInterval = window.setInterval(() => {
    resendSeconds.value = Math.max(0, resendSeconds.value - 1)
    if (resendSeconds.value === 0) clearResendCountdown()
  }, 1000)
}

async function requestRegistrationCodeForEmail() {
  isSubmitting.value = true
  resetMessages()
  try {
    await requestRegistrationCode(email.value.trim())
    registrationCode.value = ''
    stage.value = stages.code
    setMessage('如果该邮箱可以注册，验证码已发送。请查看收件箱。', 'notice')
    startResendCountdown()
  } catch (error) {
    setMessage(error.message)
  } finally {
    isSubmitting.value = false
  }
}

async function sendVerificationCode() {
  if (isBusy.value) return
  isSubmitting.value = true
  resetMessages()
  try {
    await requestRegistrationCode(email.value.trim())
    registrationCode.value = ''
    stage.value = stages.code
    setMessage('新的验证码已发送。请查看收件箱。', 'notice')
    startResendCountdown()
  } catch (error) {
    setMessage(error.message)
  } finally {
    isSubmitting.value = false
  }
}

async function verifyCode() {
  if (isBusy.value) return
  isSubmitting.value = true
  resetMessages()
  try {
    const response = await verifyRegistrationCode(email.value.trim(), registrationCode.value.trim())
    verificationToken.value = response.verificationToken
    stage.value = stages.name
  } catch (error) {
    setMessage(error.message)
  } finally {
    isSubmitting.value = false
  }
}

function joinDisplayName(family, given) {
  if (!family) return given
  const containsCjk = /[\u3400-\u9fff]/.test(family + given)
  return containsCjk ? `${family}${given}` : `${family} ${given}`
}

function continueFromName() {
  if (isBusy.value) return
  const family = familyName.value.trim()
  const given = givenName.value.trim()
  if (!given) {
    setMessage('请输入您的名字。')
    return
  }
  displayName.value = joinDisplayName(family, given)
  resetMessages()
  stage.value = stages.birthday
}

function validateBirthday() {
  const year = Number(birthYear.value)
  const month = Number(birthMonth.value)
  const day = Number(birthDay.value)
  if (!year || !month || !day) return '请输入完整的生日。'

  const birthday = new Date(year, month - 1, day)
  if (birthday.getFullYear() !== year
    || birthday.getMonth() !== month - 1
    || birthday.getDate() !== day) {
    return '请输入有效的出生日期。'
  }

  const today = new Date()
  today.setHours(0, 0, 0, 0)
  if (birthday > today) return '出生日期不能晚于今天。'

  const minimumAgeDate = new Date(today.getFullYear() - 13, today.getMonth(), today.getDate())
  if (birthday > minimumAgeDate) return '您必须年满 13 岁才能创建账号。'
  return ''
}

function toIsoBirthday() {
  const month = String(birthMonth.value).padStart(2, '0')
  const day = String(birthDay.value).padStart(2, '0')
  return `${birthYear.value}-${month}-${day}`
}

function continueFromBirthday() {
  if (isBusy.value) return
  const error = validateBirthday()
  if (error) {
    setMessage(error)
    return
  }
  if (!gender.value) {
    setMessage('请选择性别。')
    return
  }
  password.value = ''
  confirmPassword.value = ''
  resetMessages()
  stage.value = stages.password
}

async function submitRegistration() {
  if (isBusy.value) return
  if (password.value.length < 10) {
    setMessage('密码长度至少为 10 个字符。')
    return
  }
  if (password.value !== confirmPassword.value) {
    setMessage('两次输入的密码不一致。')
    return
  }

  isSubmitting.value = true
  resetMessages()
  try {
    await createRegisteredAccount({
      verificationToken: verificationToken.value,
      displayName: displayName.value.trim(),
      password: password.value,
      birthday: toIsoBirthday(),
      gender: gender.value,
    })
    password.value = ''
    confirmPassword.value = ''
    stage.value = stages.complete
  } catch (error) {
    setMessage(error.message)
  } finally {
    isSubmitting.value = false
  }
}

async function submitLogin() {
  if (isBusy.value || !loginProfile.value) return
  if (!password.value) {
    setMessage('请输入密码。')
    return
  }

  isSubmitting.value = true
  resetMessages()
  try {
    await loginWithPassword(loginProfile.value.email, password.value)
    password.value = ''
    await router.push('/')
  } catch (error) {
    setMessage(error.message)
  } finally {
    isSubmitting.value = false
  }
}

function continueWithEmail() {
  const normalizedEmail = email.value.trim().toLowerCase()
  if (!normalizedEmail || isBusy.value) return

  if (isRegistration.value) {
    void requestRegistrationCodeForEmail()
    return
  }

  email.value = normalizedEmail
  loginProfile.value = { email: normalizedEmail }
  password.value = ''
  resetMessages()
  stage.value = stages.password
}

function startRegistration() {
  isRegistration.value = true
  stage.value = stages.identifier
  email.value = ''
  password.value = ''
  confirmPassword.value = ''
  loginProfile.value = null
  resetRegistrationFields()
  resetMessages()
}

function returnToLogin() {
  clearResendCountdown()
  isRegistration.value = false
  stage.value = stages.identifier
  email.value = ''
  password.value = ''
  confirmPassword.value = ''
  loginProfile.value = null
  resetRegistrationFields()
  resetMessages()
}

function backToEmail() {
  stage.value = stages.identifier
  registrationCode.value = ''
  verificationToken.value = ''
  resetMessages()
}

function backToName() {
  stage.value = stages.name
  password.value = ''
  confirmPassword.value = ''
  resetMessages()
}

function backToBirthday() {
  stage.value = stages.birthday
  password.value = ''
  confirmPassword.value = ''
  resetMessages()
}

async function resendCode() {
  if (resendSeconds.value > 0 || isBusy.value) return
  await sendVerificationCode()
}

function returnToLoginAfterRegistration() {
  const registeredEmail = email.value
  returnToLogin()
  email.value = registeredEmail
  router.replace({
    name: 'login',
    query: registeredEmail ? { email: registeredEmail } : {},
  }).catch(() => {
    setMessage('无法返回登录页面，请刷新后重试。')
  })
}
</script>

<template>
  <main class="auth-study-page">
    <div class="auth-card-wrap">
      <section class="auth-card" aria-labelledby="auth-title">
        <div v-if="isBusy" class="auth-progress" role="progressbar" :aria-label="isRegistration ? '正在处理注册' : '正在连接登录服务'">
          <span class="auth-progress-indicator"></span>
        </div>
        <div class="auth-copy">
          <img class="brand-artwork" :src="brandArtwork" alt="SeekDateSet">
          <h1 id="auth-title">{{ currentTitle }}</h1>
          <p v-if="stage === stages.identifier" class="auth-description">
            <template v-if="!isRegistration">请使用您的 SeekDateSet 账号登录。该浏览器中的其他 SeekDateSet 应用都将可以访问此账号。</template>
            <template v-else>创建您的 SeekDateSet 账号，开始管理数据资产。</template>
          </p>

          <p v-else-if="stage === stages.code" class="auth-description">
            我们会向您的邮箱发送一次性验证码。
          </p>
          <p v-else-if="stage === stages.name" class="auth-description">
            请输入您的姓名，它将显示在您的 SeekDateSet 账号中。
          </p>
          <p v-else-if="stage === stages.birthday" class="auth-description">
            输入您的生日和性别。
          </p>
          <p v-else-if="stage === stages.complete" class="auth-description">
            您的邮箱已验证，可以使用新账号登录。
          </p>
          <p v-else-if="isRegistration" class="auth-description">
            设置一个至少 10 个字符的密码。
          </p>
          <p v-else class="auth-description">
            输入密码以继续登录 SeekDateSet。
          </p>

        </div>

        <div
          class="auth-form-column"
          :class="{ 'is-loading': isBusy }"
          :aria-busy="isBusy"
          :inert="isBusy"
        >
          <form v-if="stage === stages.identifier" class="auth-form" @submit.prevent="continueWithEmail">
            <div class="auth-field">
              <input
                id="account-email"
                v-model="email"
                autocomplete="username"
                inputmode="email"
                name="email"
                placeholder=" "
                type="email"
                :disabled="isBusy"
                required
              >
              <label for="account-email">邮箱地址</label>
            </div>

            <p
              v-if="message"
              class="auth-message"
              :class="{ 'is-notice': messageTone === 'notice' }"
              :role="messageTone === 'error' ? 'alert' : 'status'"
            >{{ message }}</p>

            <div class="form-actions">
              <button v-if="!isRegistration" class="text-link create-account" type="button" @click="startRegistration">创建账号</button>
              <button v-else class="text-link create-account" type="button" @click="returnToLogin">返回登录</button>
              <button class="primary-action" type="submit" :disabled="isBusy">
                {{ isRegistration ? '发送验证码' : '继续' }}
              </button>
            </div>
          </form>


          <form v-else-if="stage === stages.code" class="auth-form registration-form" @submit.prevent="verifyCode">
            <div class="selected-account">
              <span class="account-avatar" aria-hidden="true">{{ email.charAt(0).toUpperCase() }}</span>
              <span class="selected-identity">
                <span class="selected-email">{{ email }}</span>
                <span class="selected-caption">验证码将发送到此邮箱</span>
              </span>
              <button class="change-account" type="button" @click="backToEmail">更改</button>
            </div>
            <div class="auth-field">
              <input
                id="registration-code"
                v-model="registrationCode"
                autocomplete="one-time-code"
                inputmode="numeric"
                maxlength="6"
                name="code"
                pattern="[0-9]{6}"
                placeholder=" "
                type="text"
                required
              >
              <label for="registration-code">输入验证码</label>
            </div>
            <p class="privacy-hint">请检查收件箱和垃圾邮件文件夹。</p>
            <p
              v-if="message"
              class="auth-message"
              :class="{ 'is-notice': messageTone === 'notice' }"
              :role="messageTone === 'error' ? 'alert' : 'status'"
            >{{ message }}</p>
            <div class="form-actions">
              <button class="text-link create-account" type="button" @click="backToEmail">更改邮箱</button>
              <button class="primary-action" type="submit" :disabled="isBusy">验证</button>
            </div>
            <button class="text-link resend-code" type="button" :disabled="resendSeconds > 0 || isBusy" @click="resendCode">
              {{ resendSeconds > 0 ? `${resendSeconds} 秒后可重新发送` : '重新发送验证码' }}
            </button>
          </form>

          <form v-else-if="stage === stages.name" class="auth-form registration-form" @submit.prevent="continueFromName">
            <div class="selected-account">
              <span class="account-avatar" aria-hidden="true">{{ email.charAt(0).toUpperCase() }}</span>
              <span class="selected-identity">
                <span class="selected-email">{{ email }}</span>
                <span class="selected-caption">邮箱已验证</span>
              </span>
            </div>
            <div class="auth-field">
              <input
                id="registration-family-name"
                v-model="familyName"
                autocomplete="family-name"
                maxlength="40"
                name="family-name"
                placeholder=" "
                type="text"
              >
              <label for="registration-family-name">姓氏（选填）</label>
            </div>
            <div class="auth-field">
              <input
                id="registration-given-name"
                v-model="givenName"
                autocomplete="given-name"
                maxlength="40"
                name="given-name"
                placeholder=" "
                type="text"
                required
              >
              <label for="registration-given-name">名字</label>
            </div>
            <p
              v-if="message"
              class="auth-message"
              :class="{ 'is-notice': messageTone === 'notice' }"
              :role="messageTone === 'error' ? 'alert' : 'status'"
            >{{ message }}</p>
            <div class="form-actions">
              <button class="text-link create-account" type="button" @click="backToEmail">返回</button>
              <button class="primary-action" type="submit" :disabled="isBusy">下一步</button>
            </div>
          </form>

          <form v-else-if="stage === stages.birthday" class="auth-form registration-form" @submit.prevent="continueFromBirthday">
            <div class="selected-account">
              <span class="account-avatar" aria-hidden="true">{{ accountInitial }}</span>
              <span class="selected-identity">
                <span class="selected-email">{{ displayName }}</span>
                <span class="selected-caption">{{ email }}</span>
              </span>
            </div>
            <div class="birthday-row">
              <div class="auth-field birthday-year">
                <input
                  id="birth-year"
                  v-model="birthYear"
                  inputmode="numeric"
                  maxlength="4"
                  name="birth-year"
                  pattern="[0-9]{4}"
                  placeholder=" "
                  type="text"
                  required
                >
                <label for="birth-year">年</label>
              </div>
              <div class="auth-field auth-select-field birthday-month">
                <select id="birth-month" v-model="birthMonth" name="birth-month" required>
                  <option value="" disabled></option>
                  <option v-for="month in 12" :key="month" :value="month">{{ month }} 月</option>
                </select>
                <label for="birth-month">月</label>
                <span class="select-chevron" aria-hidden="true"></span>
              </div>
              <div class="auth-field birthday-day">
                <input
                  id="birth-day"
                  v-model="birthDay"
                  inputmode="numeric"
                  maxlength="2"
                  name="birth-day"
                  pattern="[0-9]{1,2}"
                  placeholder=" "
                  type="text"
                  required
                >
                <label for="birth-day">日</label>
              </div>
            </div>
            <div class="auth-field auth-select-field gender-field">
              <select id="gender" v-model="gender" name="gender" required>
                <option value="" disabled></option>
                <option v-for="option in genderOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
              <label for="gender">性别</label>
              <span class="select-chevron" aria-hidden="true"></span>
            </div>
            <p class="privacy-hint">您的生日和性别将用于提供个性化体验。</p>
            <p
              v-if="message"
              class="auth-message"
              :class="{ 'is-notice': messageTone === 'notice' }"
              :role="messageTone === 'error' ? 'alert' : 'status'"
            >{{ message }}</p>
            <div class="form-actions">
              <button class="text-link create-account" type="button" @click="backToName">返回</button>
              <button class="primary-action" type="submit" :disabled="isBusy">下一步</button>
            </div>
          </form>

          <section v-else-if="stage === stages.complete" class="registration-complete" aria-live="polite">
            <span class="complete-avatar" aria-hidden="true">{{ accountInitial }}</span>
            <strong>{{ displayName }}</strong>
            <span>{{ email }}</span>
            <p>账号已创建，邮箱验证完成。</p>
            <button class="primary-action" type="button" @click="returnToLoginAfterRegistration">返回登录</button>
          </section>

          <form v-else class="auth-form password-form" @submit.prevent="isRegistration ? submitRegistration() : submitLogin()">
            <input
              v-if="!isRegistration"
              type="text"
              name="username"
              autocomplete="username"
              :value="loginProfile?.email || email"
              hidden
            >
            <div class="selected-account">
              <span class="account-avatar" aria-hidden="true">{{ isRegistration ? accountInitial : (loginProfile?.email || email).charAt(0).toUpperCase() }}</span>
              <span class="selected-identity">
                <span class="selected-email">{{ isRegistration ? displayName : (loginProfile?.email || email) }}</span>
                <span class="selected-caption">{{ isRegistration ? email : 'SeekDateSet 账号' }}</span>
              </span>
            </div>
            <div class="auth-field">
              <input
                id="account-password"
                v-model="password"
                :autocomplete="isRegistration ? 'new-password' : 'current-password'"
                :minlength="isRegistration ? 10 : undefined"
                maxlength="128"
                name="password"
                placeholder=" "
                type="password"
                :autofocus="!isRegistration"
                required
              >
              <label for="account-password">{{ isRegistration ? '创建密码' : '输入密码' }}</label>
            </div>
            <div v-if="isRegistration" class="auth-field">
              <input
                id="registration-password-confirm"
                v-model="confirmPassword"
                autocomplete="new-password"
                minlength="10"
                maxlength="128"
                name="password-confirm"
                placeholder=" "
                type="password"
                required
              >
              <label for="registration-password-confirm">再次输入密码</label>
            </div>
            <p v-if="isRegistration" class="privacy-hint">密码长度至少为 10 个字符。</p>
            <button v-else class="text-link forgot-password" type="button" @click="setMessage('请联系管理员重置密码。', 'notice')">忘记了密码？</button>

            <p
              v-if="message"
              class="auth-message"
              :class="{ 'is-notice': messageTone === 'notice' }"
              :role="messageTone === 'error' ? 'alert' : 'status'"
            >{{ message }}</p>
            <div class="form-actions password-actions">
              <button
                class="text-link create-account"
                type="button"
                @click="isRegistration ? backToBirthday() : backToEmail()"
              >返回</button>
              <button class="primary-action" type="submit" :disabled="isBusy">{{ isRegistration ? '创建账号' : '继续' }}</button>
            </div>
          </form>
        </div>      </section>

      <footer class="auth-footer">
        <button class="footer-language" type="button">
          简体中文
          <span aria-hidden="true"></span>
        </button>
        <nav aria-label="帮助与政策">
          <a href="#help">帮助</a>
          <a href="#privacy">隐私权</a>
          <a href="#terms">条款</a>
        </nav>
      </footer>
    </div>
  </main>
</template>

<style scoped>
.auth-study-page {
  position: fixed;
  inset: 0;
  z-index: 10;
  display: grid;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 40px 24px 30px;
  overflow-y: auto;
  place-items: center;
  font-family: var(--font-sans);
  color: #1f1f1f;
  background: #f0f4f9;
  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

.auth-study-page *,
.auth-study-page *::before,
.auth-study-page *::after {
  box-sizing: border-box;
}

.auth-study-page button,
.auth-study-page input,
.auth-study-page select {
  font: inherit;
}

.auth-study-page button {
  -webkit-tap-highlight-color: transparent;
}

.auth-card-wrap {
  width: min(100%, 1040px);
  margin: auto;
}

.auth-card {
  position: relative;
  display: grid;
  min-height: 384px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 48px;
  padding: 36px;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 28px;
  overflow: hidden;
  background: #fff;
  box-shadow: 0 1px 2px rgba(60, 64, 67, 0.03);
}

.auth-progress {
  position: absolute;
  top: 0;
  right: 36px;
  left: 36px;
  height: 4px;
  overflow: hidden;
  border-radius: 999px;
  background: #f1f3f4;
}

.auth-progress-indicator {
  display: block;
  width: 36%;
  height: 100%;
  border-radius: inherit;
  background: #0b57d0;
  animation: auth-progress-slide 0.8s ease-in-out infinite;
}

@keyframes auth-progress-slide {
  from { transform: translateX(-110%); }
  to { transform: translateX(390%); }
}

.auth-copy {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding-top: 2px;
}

.brand-artwork {
  display: block;
  width: 96px;
  height: 48px;
  object-fit: contain;
  object-position: left center;
}

.auth-copy h1 {
  margin: 24px 0 16px;
  color: #1f1f1f;
  font-size: 44px;
  font-weight: 400;
  line-height: 1.22;
}

.auth-description {
  max-width: 460px;
  margin: 0;
  color: #1f1f1f;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.5;
}

.profile-chip {
  display: flex;
  max-width: 100%;
  align-items: center;
  gap: 10px;
  margin-top: 24px;
  padding: 4px 10px 4px 4px;
  border: 1px solid #747775;
  border-radius: 999px;
  background: #fff;
  color: #1f1f1f;
  text-align: left;
}

.profile-chip-avatar {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  font-size: 14px;
}

.profile-chip-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.profile-chip-name,
.profile-chip-email {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-chip-name {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.3;
}

.profile-chip-email {
  color: #5f6368;
  font-size: 12px;
  line-height: 1.3;
}

.profile-chip-chevron {
  display: inline-flex;
  width: 20px;
  flex: 0 0 20px;
  align-items: center;
  justify-content: center;
  color: #444746;
}

.auth-form-column {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 312px;
  flex-direction: column;
  justify-content: center;
  transition: filter 180ms ease, opacity 180ms ease;
}

.auth-form-column.is-loading {
  filter: blur(1.2px);
  opacity: 0.62;
}

.footer-language span {
  position: absolute;
  right: 14px;
  width: 0;
  height: 0;
  border-top: 5px solid #5f6368;
  border-right: 4px solid transparent;
  border-left: 4px solid transparent;
  pointer-events: none;
}

.auth-form {
  width: 100%;
  margin-top: 48px;
}

.auth-field {
  position: relative;
  width: 100%;
}

.auth-field > input {
  display: block;
  width: 100%;
  height: 56px;
  padding: 0 16px;
  border: 1px solid #747775;
  border-radius: 4px;
  outline: none;
  background: #fff;
  color: #1f1f1f;
  font-size: 16px;
  transition:
    border-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
    padding 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.auth-field > input::placeholder {
  color: transparent;
}

.auth-field > input:hover:not(:focus) {
  border-color: #202124;
}

.auth-field > input:focus {
  border: 2px solid #0b57d0;
  padding: 0 15px;
}

.auth-field > select {
  display: block;
  width: 100%;
  height: 56px;
  padding: 0 40px 0 16px;
  border: 1px solid #747775;
  border-radius: 4px;
  outline: none;
  appearance: none;
  background: #fff;
  color: #1f1f1f;
  font-size: 16px;
  cursor: pointer;
  transition:
    border-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
    padding 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.auth-field > select:hover:not(:focus) {
  border-color: #202124;
}

.auth-field > select:focus {
  border: 2px solid #0b57d0;
  padding: 0 39px 0 15px;
}

.auth-select-field > select:focus + label,
.auth-select-field > select:not(:invalid) + label {
  top: 0;
  transform: translateY(-50%) scale(0.75);
}

.auth-select-field > select:focus + label {
  color: #0b57d0;
}

.select-chevron {
  position: absolute;
  top: 50%;
  right: 16px;
  width: 0;
  height: 0;
  border-top: 5px solid #444746;
  border-right: 4px solid transparent;
  border-left: 4px solid transparent;
  pointer-events: none;
  transform: translateY(-30%);
}

.auth-select-field > select:focus ~ .select-chevron {
  border-top-color: #0b57d0;
}

.auth-field > label {
  position: absolute;
  top: 50%;
  left: 12px;
  z-index: 1;
  max-width: calc(100% - 24px);
  overflow: hidden;
  padding: 0 4px;
  color: #444746;
  font-size: 16px;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
  background: #fff;
  transform: translateY(-50%);
  transform-origin: left center;
  transition:
    top 150ms cubic-bezier(0.4, 0, 0.2, 1),
    transform 150ms cubic-bezier(0.4, 0, 0.2, 1),
    color 150ms cubic-bezier(0.4, 0, 0.2, 1);
}

.auth-field > input:focus + label,
.auth-field > input:not(:placeholder-shown) + label {
  top: 0;
  transform: translateY(-50%) scale(0.75);
}

.auth-field > input:focus + label {
  color: #0b57d0;
}

.form-link-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}

.text-link {
  display: inline;
  padding: 0;
  border: 0;
  background: none;
  color: #0b57d0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.text-link:hover,
.auth-footer a:hover {
  text-decoration: underline;
}

.privacy-hint {
  max-width: 560px;
  margin: 60px 0 0;
  color: #5f6368;
  font-size: 14px;
  line-height: 1.5;
}

.privacy-hint .text-link {
  margin-left: 4px;
}

.remember-account {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  color: #444746;
  font-size: 14px;
}

.remember-account input {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: #0b57d0;
}

.remember-account label {
  cursor: pointer;
}

.saved-account-link {
  margin-top: 18px;
}

.auth-message {
  margin: 16px 0 0;
  color: #b3261e;
  font-size: 13px;
  line-height: 1.5;
}

.auth-message.is-notice {
  color: #3c4043;
}

.form-actions {
  display: flex;
  min-height: 40px;
  align-items: center;
  justify-content: flex-end;
  gap: 28px;
  margin-top: 48px;
}

.create-account {
  font-size: 15px;
}

.primary-action {
  display: inline-flex;
  min-width: 90px;
  height: 40px;
  align-items: center;
  justify-content: center;
  padding: 0 20px;
  border: 0;
  border-radius: 20px;
  background: #0b57d0;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 120ms ease, box-shadow 120ms ease;
}

.primary-action:hover {
  background: #0842a0;
  box-shadow: 0 1px 3px rgba(60, 64, 67, 0.3);
}

.primary-action:disabled,
.text-link:disabled {
  cursor: wait;
  opacity: 0.55;
}

.primary-action:focus-visible,
.text-link:focus-visible,
.account-choice:focus-visible,
.remove-account:focus-visible,
.change-account:focus-visible {
  outline: 2px solid #0b57d0;
  outline-offset: 3px;
}

.auth-footer {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 18px 0;
  color: #444746;
  font-size: 13px;
}

.footer-language {
  position: relative;
  padding: 4px 26px 4px 0;
  border: 0;
  background: transparent;
  color: inherit;
  font-size: inherit;
  cursor: pointer;
}

.footer-language span {
  top: 50%;
  right: 5px;
  transform: translateY(-30%);
}

.auth-footer nav {
  display: flex;
  align-items: center;
  gap: 28px;
}

.auth-footer a {
  color: inherit;
  text-decoration: none;
}

.account-stage {
  width: 100%;
  margin-top: 40px;
}

.account-list {
  border-top: 1px solid #dadce0;
}

.account-row {
  display: flex;
  min-height: 64px;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #dadce0;
}

.account-choice {
  display: flex;
  min-width: 0;
  min-height: 62px;
  flex: 1;
  align-items: center;
  gap: 14px;
  padding: 8px 6px;
  border: 0;
  background: transparent;
  color: #202124;
  text-align: left;
  cursor: pointer;
}

.account-choice:hover {
  background: #f8fafd;
}

.account-avatar {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  border-radius: 50%;
  background: #e8f0fe;
  color: #174ea6;
  font-size: 16px;
  font-weight: 600;
}

.account-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
}

.account-identity {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}

.account-identity strong,
.account-identity > span {
  overflow: hidden;
  font-size: 14px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-identity > span {
  color: #5f6368;
}

.account-status {
  flex: 0 0 auto;
  color: #5f6368;
  font-size: 12px;
}

.account-avatar-outline {
  border: 1px solid #5f6368;
  background: transparent;
  color: #5f6368;
  font-size: 21px;
  font-weight: 400;
}

.other-account {
  width: 100%;
  border-bottom: 1px solid #dadce0;
}

.other-account strong {
  font-size: 15px;
  font-weight: 600;
}

.remove-account,
.account-actions .text-link {
  padding: 8px;
  border: 0;
  background: transparent;
  color: #0b57d0;
  font-size: 14px;
  cursor: pointer;
}

.account-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 22px;
}

.selected-account {
  display: flex;
  min-height: 50px;
  align-items: center;
  gap: 10px;
  margin-bottom: 22px;
  padding: 4px 10px;
  border: 1px solid #dadce0;
  border-radius: 25px;
}

.selected-identity {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.selected-email {
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-caption {
  overflow: hidden;
  color: #5f6368;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.change-account {
  display: inline-flex;
  min-width: 44px;
  height: 32px;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #0b57d0;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.change-account .el-icon {
  font-size: 16px;
}

.forgot-password {
  display: block;
  margin-top: 10px;
}

.password-actions {
  margin-top: 56px;
}

.registration-form {
  margin-top: 20px;
}

.registration-form > .auth-field + .auth-field {
  margin-top: 12px;
}

.registration-form > .birthday-row + .auth-field {
  margin-top: 12px;
}

.birthday-row {
  display: flex;
  gap: 10px;
}

.birthday-row .auth-field {
  min-width: 0;
}

.birthday-year {
  flex: 1.2;
}

.birthday-month {
  flex: 1.15;
}

.birthday-day {
  flex: 0.8;
}

.password-form > .auth-field + .auth-field {
  margin-top: 12px;
}

.password-form .privacy-hint {
  margin-top: 14px;
}

.password-form .password-actions {
  margin-top: 40px;
}

.registration-form .privacy-hint {
  margin-top: 14px;
}

.registration-form .form-actions {
  margin-top: 28px;
}

.resend-code {
  margin-top: 18px;
}

.registration-complete {
  display: flex;
  min-height: 312px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 9px;
  margin-top: 24px;
  text-align: center;
}

.complete-avatar {
  display: grid;
  width: 56px;
  height: 56px;
  place-items: center;
  margin-bottom: 4px;
  border-radius: 50%;
  background: #e8f0fe;
  color: #174ea6;
  font-size: 22px;
  font-weight: 600;
}

.registration-complete strong {
  max-width: 100%;
  overflow-wrap: anywhere;
  font-size: 16px;
  font-weight: 600;
}

.registration-complete > span:not(.complete-avatar) {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: #5f6368;
  font-size: 14px;
}

.registration-complete p {
  margin: 8px 0 14px;
  color: #5f6368;
  font-size: 14px;
}


.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  clip-path: inset(50%);
}

@media (max-width: 760px) {
  .auth-study-page {
    padding: 24px 18px;
  }

  .auth-card {
    min-height: 0;
    grid-template-columns: minmax(0, 1fr);
    gap: 18px;
    padding: 32px 28px 28px;
    border-radius: 28px;
  }

  .auth-progress {
    right: 28px;
    left: 28px;
  }

  .auth-copy {
    padding-top: 0;
  }

  .brand-artwork {
    width: 96px;
    height: 48px;
  }

  .auth-copy h1 {
    margin: 20px 0 12px;
    font-size: 32px;
  }

  .auth-description {
    font-size: 16px;
  }

  .auth-form-column {
    min-height: 340px;
    padding-top: 20px;
  }

  .auth-form {
    margin-top: 44px;
  }

  .registration-form {
    margin-top: 22px;
  }

  .registration-complete {
    min-height: 280px;
    margin-top: 18px;
  }

  .privacy-hint {
    margin-top: 30px;
  }

  .form-actions {
    margin-top: 32px;
  }

  .account-stage {
    margin-top: 36px;
  }

  .auth-footer {
    padding: 12px 8px 0;
  }

  .auth-footer nav {
    gap: 18px;
  }
}

@media (max-width: 420px) {
  .auth-study-page {
    padding: 18px 12px;
  }

  .auth-card {
    gap: 16px;
    padding: 28px 20px 24px;
    border-radius: 24px;
  }

  .auth-progress {
    right: 20px;
    left: 20px;
  }

  .brand-artwork {
    width: 96px;
    height: 48px;
  }

  .auth-form-column {
    min-height: 360px;
  }

  .auth-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
    padding: 18px 8px 0;
  }

  .auth-footer nav {
    align-self: flex-end;
  }

  .account-status {
    max-width: 76px;
    text-align: right;
  }

  .form-actions {
    gap: 18px;
  }

  .account-status {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .auth-study-page *,
  .auth-study-page *::before,
  .auth-study-page *::after {
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
  }

  .auth-progress-indicator {
    animation: none;
    transform: translateX(90%);
  }
}
</style>
