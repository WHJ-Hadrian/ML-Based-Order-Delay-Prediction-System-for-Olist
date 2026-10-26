param(
    [string]$RepoRoot,
    [string]$KeycloakUrl = 'http://localhost:8180',
    [switch]$SkipDockerStart,
    [switch]$RotateRegistrationSecret
)

$ErrorActionPreference = 'Stop'

function Resolve-RepoRoot {
    param([string]$Explicit)

    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($PSScriptRoot) {
        $candidates += $PSScriptRoot
        $candidates += (Join-Path $PSScriptRoot 'ML-Based-Order-Delay-Prediction-System-for-Olist')
        $candidates += (Split-Path -Parent $PSScriptRoot)
        $candidates += (Join-Path (Split-Path -Parent $PSScriptRoot) 'ML-Based-Order-Delay-Prediction-System-for-Olist')
    }
    $candidates += (Get-Location).Path
    $candidates += (Join-Path (Get-Location).Path 'ML-Based-Order-Delay-Prediction-System-for-Olist')

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'compose.yaml'))) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw 'Project root not found. Put this script in the whjbs folder and run it again.'
}

function Read-DotEnv {
    param([string]$Path)

    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $index = $trimmed.IndexOf('=')
        if ($index -lt 1) { continue }

        $name = $trimmed.Substring(0, $index).Trim()
        $value = $trimmed.Substring($index + 1).Trim()
        if ($value.Length -ge 2 -and (
                ($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }

    return $values
}

function Get-ConfiguredValue {
    param($Values, [string]$Name, [string]$Default)

    if ($Values.ContainsKey($Name) -and $Values[$Name]) { return $Values[$Name] }
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if ($processValue) { return $processValue }
    return $Default
}

function Set-DotEnvValue {
    param([string]$Path, [string]$Name, [string]$Value)

    $pattern = '^\s*' + [regex]::Escape($Name) + '='
    $found = $false
    $output = New-Object System.Collections.Generic.List[string]

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match $pattern) {
            if (-not $found) {
                $output.Add("$Name=$Value")
                $found = $true
            }
        } else {
            $output.Add($line)
        }
    }
    if (-not $found) { $output.Add("$Name=$Value") }

    [System.IO.File]::WriteAllLines($Path, $output, (New-Object System.Text.UTF8Encoding($false)))
}

function New-RandomSecret {
    param([int]$Bytes = 48)

    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    return -join ($buffer | ForEach-Object { $_.ToString('x2') })
}

$repo = Resolve-RepoRoot $RepoRoot
$envPath = Join-Path $repo '.env'
$secretsChanged = $false

if (-not (Test-Path -LiteralPath $envPath)) {
    $examplePath = Join-Path $repo '.env.example'
    if (-not (Test-Path -LiteralPath $examplePath)) {
        throw '.env is missing and .env.example was not found.'
    }
    Copy-Item -LiteralPath $examplePath -Destination $envPath
    Write-Host 'Created .env from .env.example.'
    $secretsChanged = $true
}

$values = Read-DotEnv $envPath
$realm = Get-ConfiguredValue $values 'KEYCLOAK_REALM' 'olist'
$webClientId = Get-ConfiguredValue $values 'KEYCLOAK_WEB_CLIENT_ID' 'olist-web'
$clientId = Get-ConfiguredValue $values 'KEYCLOAK_REGISTRATION_CLIENT_ID' 'olist-registration-service'
$clientSecret = Get-ConfiguredValue $values 'KEYCLOAK_REGISTRATION_CLIENT_SECRET' ''
$otpSecret = Get-ConfiguredValue $values 'REGISTRATION_OTP_SECRET' ''
$adminUser = Get-ConfiguredValue $values 'KEYCLOAK_ADMIN' 'admin'
$adminPassword = Get-ConfiguredValue $values 'KEYCLOAK_ADMIN_PASSWORD' 'local-admin-change-me'
$configuredKeycloakUrl = Get-ConfiguredValue $values 'KEYCLOAK_ADMIN_API_URL' $KeycloakUrl
if ($configuredKeycloakUrl -match '^https?://(localhost|127\.0\.0\.1)(:\d+)?/?$') {
    $KeycloakUrl = $configuredKeycloakUrl.TrimEnd('/')
} else {
    $KeycloakUrl = $KeycloakUrl.TrimEnd('/')
}

if (-not $clientSecret -or $RotateRegistrationSecret) {
    $clientSecret = New-RandomSecret 48
    Set-DotEnvValue $envPath 'KEYCLOAK_REGISTRATION_CLIENT_SECRET' $clientSecret
    Write-Host 'Generated a new registration client secret and wrote it to .env.'
    $secretsChanged = $true
}
if (-not $otpSecret -or $otpSecret.Length -lt 32) {
    $otpSecret = New-RandomSecret 32
    Set-DotEnvValue $envPath 'REGISTRATION_OTP_SECRET' $otpSecret
    Write-Host 'Generated REGISTRATION_OTP_SECRET and wrote it to .env.'
    $secretsChanged = $true
}

if (-not $SkipDockerStart) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { throw 'Docker was not found. Install Docker Desktop or run with -SkipDockerStart.' }

    $dependencies = @(
        @{ Name = 'mysql'; Port = 3307 },
        @{ Name = 'redis'; Port = 6379 },
        @{ Name = 'mailpit'; Port = 8025 },
        @{ Name = 'keycloak'; Port = 8180 }
    )
    $missingServices = @($dependencies | Where-Object {
        -not (Get-NetTCPConnection -State Listen -LocalPort $_.Port -ErrorAction SilentlyContinue)
    } | ForEach-Object { $_.Name })

    if ($missingServices.Count -gt 0) {
        Write-Host ('Starting auth dependencies: ' + ($missingServices -join ', '))
        Push-Location $repo
        try {
            & docker compose up -d $missingServices
            if ($LASTEXITCODE -ne 0) { throw 'docker compose failed to start the auth dependencies.' }
        } finally {
            Pop-Location
        }
    } else {
        Write-Host 'Auth dependency containers are already running.'
    }
}

Write-Host "Waiting for Keycloak at $KeycloakUrl ..."
$deadline = (Get-Date).AddMinutes(2)
$ready = $false
while (-not $ready -and (Get-Date) -lt $deadline) {
    try {
        Invoke-RestMethod -Uri "$KeycloakUrl/realms/master" -TimeoutSec 3 | Out-Null
        $ready = $true
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $ready) { throw "Keycloak did not become ready at $KeycloakUrl." }

try {
    $adminToken = Invoke-RestMethod -Uri "$KeycloakUrl/realms/master/protocol/openid-connect/token" `
        -Method Post `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{
            grant_type = 'password'
            client_id = 'admin-cli'
            username = $adminUser
            password = $adminPassword
        }
} catch {
    throw 'Keycloak admin login failed. Check KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD in .env.'
}

$headers = @{ Authorization = "Bearer $($adminToken.access_token)" }
$adminPath = "$KeycloakUrl/admin/realms/$([uri]::EscapeDataString($realm))"

try {
    $realmRepresentation = Invoke-RestMethod -Uri $adminPath -Headers $headers
} catch {
    throw "Keycloak realm '$realm' was not found. Start Keycloak with the project compose file."
}

$expectedDisplayName = 'SeekDateSet ' + (-join ([char[]](0x6570,0x636E,0x5206,0x6790,0x5DE5,0x4F5C,0x53F0)))
$realmChanged = $false
if ($realmRepresentation.verifyEmail) {
    $realmRepresentation.verifyEmail = $false
    $realmChanged = $true
}
if ([string]::IsNullOrWhiteSpace($realmRepresentation.displayName) -or $realmRepresentation.displayName.Contains('?')) {
    $realmRepresentation.displayName = $expectedDisplayName
    $realmChanged = $true
}
if ($realmChanged) {
    $realmJson = $realmRepresentation | ConvertTo-Json -Depth 50
    Invoke-RestMethod -Method Put -Uri $adminPath -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($realmJson)) | Out-Null
    Write-Host 'Updated Keycloak realm settings (email verification and display name).'
}

$profilePath = "$adminPath/users/profile"
$userProfile = Invoke-RestMethod -Uri $profilePath -Headers $headers
$profileChanged = $false
if ($userProfile.PSObject.Properties.Name -contains 'unmanagedAttributePolicy') {
    if ($userProfile.unmanagedAttributePolicy -ne 'ENABLED') {
        $userProfile.unmanagedAttributePolicy = 'ENABLED'
        $profileChanged = $true
    }
} else {
    $userProfile | Add-Member -NotePropertyName unmanagedAttributePolicy -NotePropertyValue 'ENABLED' -Force
    $profileChanged = $true
}
foreach ($attribute in @($userProfile.attributes)) {
    if ($attribute.name -eq 'lastName' -and $attribute.PSObject.Properties.Name -contains 'required') {
        $attribute.PSObject.Properties.Remove('required')
        $profileChanged = $true
    }
}
if ($profileChanged) {
    Invoke-RestMethod -Method Put -Uri $profilePath -Headers $headers `
        -ContentType 'application/json' -Body ($userProfile | ConvertTo-Json -Depth 50) | Out-Null
    Write-Host 'Adjusted Keycloak user profile: surname is optional and custom attributes are enabled.'
} else {
    Write-Host 'Keycloak user profile is already configured.'
}

$encodedWebClientId = [uri]::EscapeDataString($webClientId)
$webClients = @(Invoke-RestMethod -Uri "$adminPath/clients?clientId=$encodedWebClientId" -Headers $headers)
$webClient = $webClients | Select-Object -First 1
if (-not $webClient) { throw "Keycloak web client '$webClientId' was not found." }
$webClientAttributes = @{}
if ($webClient.attributes) {
    foreach ($property in $webClient.attributes.PSObject.Properties) {
        $webClientAttributes[$property.Name] = $property.Value
    }
}
$webClientAttributes['pkce.code.challenge.method'] = 'S256'
$webClientChanged = $false
if (-not $webClient.publicClient) { $webClient.publicClient = $true; $webClientChanged = $true }
if (-not $webClient.standardFlowEnabled) { $webClient.standardFlowEnabled = $true; $webClientChanged = $true }
if ($webClient.implicitFlowEnabled) { $webClient.implicitFlowEnabled = $false; $webClientChanged = $true }
if ($webClient.directAccessGrantsEnabled) { $webClient.directAccessGrantsEnabled = $false; $webClientChanged = $true }
if ($webClient.attributes.'pkce.code.challenge.method' -ne 'S256') { $webClientChanged = $true }
$webClient.attributes = $webClientAttributes
if ($webClientChanged) {
    Invoke-RestMethod -Method Put -Uri "$adminPath/clients/$($webClient.id)" -Headers $headers `
        -ContentType 'application/json' -Body ($webClient | ConvertTo-Json -Depth 50) | Out-Null
    Write-Host "Updated Keycloak web client '$webClientId' to Authorization Code + PKCE."
} else {
    Write-Host "Keycloak web client '$webClientId' already uses Authorization Code + PKCE."
}

$encodedClientId = [uri]::EscapeDataString($clientId)
$clients = @(Invoke-RestMethod -Uri "$adminPath/clients?clientId=$encodedClientId" -Headers $headers)
$client = $clients | Select-Object -First 1

$clientData = [ordered]@{
    clientId = $clientId
    name = 'SeekDateSet registration service'
    enabled = $true
    protocol = 'openid-connect'
    publicClient = $false
    clientAuthenticatorType = 'client-secret'
    secret = $clientSecret
    standardFlowEnabled = $false
    implicitFlowEnabled = $false
    directAccessGrantsEnabled = $false
    serviceAccountsEnabled = $true
    fullScopeAllowed = $true
    redirectUris = @()
    webOrigins = @()
}

if ($client) {
    $update = [ordered]@{ id = $client.id }
    foreach ($key in $clientData.Keys) { $update[$key] = $clientData[$key] }
    Invoke-RestMethod -Method Put -Uri "$adminPath/clients/$($client.id)" -Headers $headers `
        -ContentType 'application/json' -Body ($update | ConvertTo-Json -Depth 20) | Out-Null
    Write-Host "Updated Keycloak client '$clientId'."
} else {
    Invoke-RestMethod -Method Post -Uri "$adminPath/clients" -Headers $headers `
        -ContentType 'application/json' -Body ($clientData | ConvertTo-Json -Depth 10) | Out-Null
    $clients = @(Invoke-RestMethod -Uri "$adminPath/clients?clientId=$encodedClientId" -Headers $headers)
    $client = $clients | Select-Object -First 1
    if (-not $client) { throw "Failed to create Keycloak client '$clientId'." }
    Write-Host "Created Keycloak client '$clientId'."
}

$serviceAccount = Invoke-RestMethod -Uri "$adminPath/clients/$($client.id)/service-account-user" -Headers $headers
$managementClients = @(Invoke-RestMethod -Uri "$adminPath/clients?clientId=realm-management" -Headers $headers)
$managementClientId = ($managementClients | Select-Object -First 1).id
if (-not $serviceAccount.id -or -not $managementClientId) {
    throw 'Could not resolve the registration service account or realm-management client.'
}

$currentRoles = @(Invoke-RestMethod -Uri "$adminPath/users/$($serviceAccount.id)/role-mappings/clients/$managementClientId" -Headers $headers)
$currentRoleNames = @($currentRoles | ForEach-Object { $_.name })
$missingRoles = @()
foreach ($roleName in @('manage-users', 'query-users', 'view-users')) {
    if ($currentRoleNames -notcontains $roleName) {
        $missingRoles += Invoke-RestMethod -Uri "$adminPath/clients/$managementClientId/roles/$roleName" -Headers $headers
    }
}
if ($missingRoles.Count -gt 0) {
    Invoke-RestMethod -Method Post -Uri "$adminPath/users/$($serviceAccount.id)/role-mappings/clients/$managementClientId" -Headers $headers -ContentType 'application/json' -Body (ConvertTo-Json -InputObject @($missingRoles) -Depth 10) | Out-Null
    Write-Host 'Granted manage-users, query-users, and view-users to the service account.'
} else {
    Write-Host 'Registration service-account roles are already configured.'
}
$verified = $false
for ($attempt = 0; $attempt -lt 5 -and -not $verified; $attempt++) {
    try {
        $tokenBody = @{
            grant_type = 'client_credentials'
            client_id = $clientId
            client_secret = $clientSecret
        }
        $verify = Invoke-RestMethod -Uri "$KeycloakUrl/realms/$realm/protocol/openid-connect/token" -Method Post -ContentType 'application/x-www-form-urlencoded' -Body $tokenBody
        Invoke-RestMethod -Uri "$adminPath/users?max=1" -Headers @{ Authorization = "Bearer $($verify.access_token)" } | Out-Null
        $verified = $true
    } catch {
        Start-Sleep -Seconds 1
    }
}
if (-not $verified) { throw 'Keycloak client verification failed.' }

Write-Host ''
Write-Host 'Auth bootstrap complete.'
Write-Host "Keycloak: $KeycloakUrl"
Write-Host "Realm: $realm"
Write-Host "Client: $clientId"
Write-Host 'The client secret matches KEYCLOAK_REGISTRATION_CLIENT_SECRET in .env.'
Write-Host 'Start api-gateway if it is not running, then retry login or registration.'

if ($secretsChanged) {
    Write-Host 'Secrets in .env changed. Restart api-gateway so it reloads them.'
}
