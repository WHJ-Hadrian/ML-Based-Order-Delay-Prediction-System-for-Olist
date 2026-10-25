<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "social-providers.ftl" as identityProviders>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
<!-- template: login.ftl -->

    <#if section = "header">
        <span class="auth-brand">
            <img src="${url.resourcesPath}/img/favicon.ico" alt="" aria-hidden="true" />
            <span>SeekDateSet 数据分析工作台</span>
        </span>
        <span class="auth-main-title">${msg("loginAccountTitle")}</span>
        <span class="auth-main-description">${msg("loginAccountDescription")}</span>
    <#elseif section = "form">
        <div id="kc-form">
          <div id="kc-form-wrapper">
            <#if realm.password>
                <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate"
                      data-initial-step="<#if usernameHidden?? || messagesPerField.existsError('password')>password<#else>email</#if>"
                      data-username-error="<#if messagesPerField.existsError('username')>true<#else>false</#if>">
                    <#if !usernameHidden??>
                        <section id="kc-account-step" class="auth-step" aria-labelledby="kc-account-step-title" hidden>
                            <h2 id="kc-account-step-title" class="auth-step-title">${msg("chooseAccountTitle")}</h2>
                            <p class="auth-step-description">${msg("chooseAccountDescription")}</p>
                            <div id="kc-saved-accounts" class="auth-saved-account-list" role="list"
                                 data-saved-label="${msg("savedAccountStatus")}"
                                 data-remove-label="${msg("removeSavedAccount")}"></div>
                            <div class="auth-account-actions">
                                <button id="kc-account-other" class="auth-step-control auth-account-action" type="button">${msg("useAnotherAccount")}</button>
                                <button id="kc-account-manage" class="auth-step-control auth-account-action" type="button"
                                        data-remove-label="${msg("removeAccounts")}"
                                        data-done-label="${msg("finishAccountManagement")}" aria-pressed="false">${msg("removeAccounts")}</button>
                            </div>
                        </section>
                        <section id="kc-email-step" class="auth-step" aria-labelledby="kc-email-step-title">
                            <h2 id="kc-email-step-title" class="auth-step-title">${msg("accountEmailTitle")}</h2>
                            <p class="auth-step-description">${msg("accountEmailDescription")}</p>
                            <#assign label>
                                <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                            </#assign>
                            <@field.input name="username" label=label error=messagesPerField.getFirstError('username')
                                autofocus=true autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" value=login.username!'' />
                            <label class="auth-remember-account" for="kc-remember-account">
                                <input id="kc-remember-account" type="checkbox" checked />
                                <span>${msg("rememberAccount")}</span>
                            </label>
                            <div class="auth-step-actions">
                                <button id="kc-account-chooser-back" class="auth-step-control auth-step-back" type="button" hidden>${msg("backToAccounts")}</button>
                                <button id="kc-email-continue" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} auth-step-control" type="button">
                                    ${msg("stepContinue")}
                                </button>
                            </div>
                        </section>
                    </#if>

                    <section id="kc-password-step" class="auth-step" aria-labelledby="kc-password-step-title">
                        <#if !usernameHidden??>
                            <div class="auth-account-summary">
                                <span id="kc-account-email" aria-live="polite"></span>
                                <button id="kc-email-back" class="auth-step-control auth-step-back" type="button"
                                        data-chooser-label="${msg("backToAccounts")}" data-email-label="${msg("stepBack")}">${msg("stepBack")}</button>
                            </div>
                        </#if>
                        <h2 id="kc-password-step-title" class="auth-step-title">${msg("passwordStepTitle")}</h2>
                        <@field.password name="password" label=msg("password") error=messagesPerField.getFirstError('password') forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??>
                                <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                            </#if>
                        </@field.password>
                        <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                        <@buttons.loginButton />
                    </section>
                </form>
                <script src="${url.resourcesPath}/js/login-steps.js" defer></script>
            </#if>
            </div>
        </div>
        <@passkeys.conditionalUIData />
    <#elseif section = "socialProviders" >
        <#if realm.password && social.providers?? && social.providers?has_content>
            <@identityProviders.show social=social/>
        </#if>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration">
                    <span>${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></span>
                </div>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
