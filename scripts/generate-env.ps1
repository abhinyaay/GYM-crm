<#
.SYNOPSIS
  Creates the GymOps settings file (.env) with strong random secrets. Windows twin of scripts/generate-env.sh.

.DESCRIPTION
  Copies .env.example to .env and replaces every placeholder secret with a random value, exactly like
  generate-env.sh: database / analytics / Redis passwords, JWT secret, bootstrap admin and demo passwords,
  simulated-gateway secret, access-control master key and unsubscribe-link secret. An existing .env is never touched.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\generate-env.ps1
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$example = Join-Path $root '.env.example'
$target = Join-Path $root '.env'

if (Test-Path -LiteralPath $target) {
    Write-Host '.env already exists - leaving it untouched'
    exit 0
}
if (-not (Test-Path -LiteralPath $example)) {
    Write-Error ".env.example not found in $root"
    exit 1
}

$alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

# Random letters and digits from a cryptographic generator (rejection sampling keeps every character equally likely).
function New-RandomString([int]$Length) {
    $chars = New-Object System.Text.StringBuilder
    $buffer = New-Object byte[] 1
    while ($chars.Length -lt $Length) {
        $rng.GetBytes($buffer)
        if ($buffer[0] -lt 248) {   # 248 = 4 * 62: drop the top values so the modulo is unbiased
            [void]$chars.Append($alphabet[$buffer[0] % 62])
        }
    }
    return $chars.ToString()
}

# Same keys, lengths and prefixes as generate-env.sh
$secrets = [ordered]@{
    'POSTGRES_PASSWORD'        = New-RandomString 32
    'ANALYTICS_DB_PASSWORD'    = New-RandomString 32
    'REDIS_PASSWORD'           = New-RandomString 32
    'JWT_SECRET'               = New-RandomString 64
    'BOOTSTRAP_ADMIN_PASSWORD' = 'Adm1n' + (New-RandomString 16)
    'DEMO_USER_PASSWORD'       = 'Dem0' + (New-RandomString 12)
    'SIMULATED_GATEWAY_SECRET' = New-RandomString 48
    'ACCESS_MASTER_KEY'        = New-RandomString 48
    'UNSUBSCRIBE_TOKEN_SECRET' = New-RandomString 48
}

$text = [System.IO.File]::ReadAllText($example)
foreach ($key in $secrets.Keys) {
    # whole line "KEY=anything" -> "KEY=<random>" (keeps a Windows line ending if the file has one)
    $pattern = '(?m)^' + [regex]::Escape($key) + '=[^\r\n]*'
    # values are letters and digits only, so they are safe as a regex replacement string
    $text = [regex]::Replace($text, $pattern, "$key=$($secrets[$key])")
}

# UTF-8 without BOM, like the shell version (Docker Compose reads it on every platform)
[System.IO.File]::WriteAllText($target, $text, (New-Object System.Text.UTF8Encoding $false))

# Equivalent of chmod 600: only the current user may read the file (best effort)
try {
    if ($IsLinux -or $IsMacOS) {
        & chmod 600 $target
    } else {
        $user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls $target /inheritance:r /grant:r "${user}:(R,W)" | Out-Null
    }
} catch {
    Write-Warning "Could not restrict access to .env: $($_.Exception.Message)"
}

Write-Host 'Generated .env (bootstrap admin + demo passwords are inside it)'
