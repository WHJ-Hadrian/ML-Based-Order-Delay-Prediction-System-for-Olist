<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#import "register-commons.ftl" as registerCommons>
<#import "password-validation.ftl" as validator>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=true; section>
<!-- template: register.ftl -->

    <#if section = "header">
        <span class="auth-brand">
            <img src="${url.resourcesPath}/img/favicon.ico" alt="" aria-hidden="true" />
            <span>SeekDateSet 数据分析工作台</span>
        </span>
        <span class="auth-main-title"><#if messageHeader??>${msg(messageHeader)}<#else>${msg("registerTitle")}</#if></span>
        <span class="auth-main-description">${msg("registerAccountDescription")}</span>
    <#elseif section = "form">
        <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.registrationAction}" method="post" novalidate="novalidate">
            <h2 id="kc-register-email-title" class="auth-step-title">${msg("registerEmailTitle")}</h2>
            <p id="kc-register-email-description" class="auth-step-description">${msg("registerEmailDescription")}</p>

            <div id="kc-register-fields">
                <@userProfileCommons.userProfileFormFields; callback, attribute>
                    <#if callback = "afterField">
                        <#if attribute.name == 'username' || (attribute.name == 'email' && realm.registrationEmailAsUsername)>
                            <@field.password name="password" required=true label=msg("password") autocomplete="new-password" />
                            <@field.password name="password-confirm" required=true label=msg("passwordConfirm") autocomplete="new-password" />
                        </#if>
                    </#if>
                </@userProfileCommons.userProfileFormFields>
            </div>

            <div id="kc-register-email-actions">
                <button id="kc-register-continue" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} auth-step-control" type="button">
                    ${msg("stepContinue")}
                </button>
            </div>

            <section id="kc-register-details" aria-labelledby="kc-register-details-title">
                <div class="auth-account-summary">
                    <span id="kc-register-account-email" aria-live="polite"></span>
                    <button id="kc-register-back" class="auth-step-control auth-step-back" type="button">${msg("stepBack")}</button>
                </div>
                <h2 id="kc-register-details-title" class="auth-step-title">${msg("registerDetailsTitle")}</h2>
                <p class="auth-step-description">${msg("registerDetailsDescription")}</p>

                <@registerCommons.termsAcceptance/>

                <#if recaptchaRequired?? && (recaptchaVisible!false)>
                    <div class="form-group">
                        <div class="${properties.kcInputWrapperClass!}">
                            <div class="g-recaptcha" data-size="compact" data-sitekey="${recaptchaSiteKey}" data-action="${recaptchaAction}"></div>
                        </div>
                    </div>
                </#if>

                <#if recaptchaRequired?? && !(recaptchaVisible!false)>
                    <script>
                        function onSubmitRecaptcha(token) {
                            document.getElementById("kc-register-form").requestSubmit();
                        }
                    </script>
                    <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} g-recaptcha"
                                data-sitekey="${recaptchaSiteKey}" data-callback="onSubmitRecaptcha" data-action="${recaptchaAction}" type="submit" id="kc-submit">
                            ${msg("doRegister")}
                        </button>
                    </div>
                <#else>
                    <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                        <input id="kc-submit" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doRegister")}"/>
                    </div>
                </#if>
            </section>

            <div class="${properties.kcFormGroupClass!} pf-v5-c-login__main-footer-band">
                <div id="kc-form-options" class="${properties.kcFormOptionsClass!} pf-v5-c-login__main-footer-band-item">
                    <div class="${properties.kcFormOptionsWrapperClass!}">
                        <span><a href="${url.loginUrl}">${msg("backToLogin")}</a></span>
                    </div>
                </div>
            </div>
        </form>

        <script src="${url.resourcesPath}/js/register-steps.js" defer></script>
        <@validator.templates/>
        <@validator.script field="password"/>
    </#if>
</@layout.registrationLayout>
