<#
[INPUT]: 读取根目录配置、Java 与 Node 环境和本机运行依赖。
[OUTPUT]: 检查、构建或启动本机应用。
[POS]: Windows 启动入口。与发布版 README 共用部署步骤。
[PROTOCOL]: Update this header on change, then check AGENTS.md
#>
param(
    [switch]$Check,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    # ASCII messages also work in Windows PowerShell 5.1 without a UTF-8 BOM.
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'JDK 17 or 21 is required. Install a JDK and add java to PATH.' }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js 22.19+ is required. Install Node and reopen PowerShell.' }
    $javaVersion = (& java --version | Out-String)
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?m)^(?:openjdk|java) (17|21)(\.|\s|-)') { throw 'This project requires Java 17 or 21. Check java -version and JAVA_HOME.' }
    $nodeVersion = (& node --version).Trim().TrimStart('v')
    if ([version]$nodeVersion -lt [version]'22.19.0') { throw 'Node.js 22.19+ is required.' }

    $envPath = Join-Path $projectRoot '.env'
    if (-not (Test-Path -LiteralPath $envPath)) {
        throw 'Missing .env. Copy .env.qq-official.example to .env, then fill in your model and QQ credentials. See README.md.'
    }
    function Read-Settings {
        $settings = @{}
        foreach ($line in [System.IO.File]::ReadAllLines($envPath, [System.Text.Encoding]::UTF8)) {
            if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
                $key = $Matches[1]
                $value = $Matches[2]
                if ($value.Length -gt 1 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or ($value.StartsWith('"') -and $value.EndsWith('"')))) {
                    $value = $value.Substring(1, $value.Length - 2)
                } else { $value = ($value -replace '\s+#.*$', '').Trim() }
                $settings[$key] = $value
            }
        }
        # Match the application's precedence: process environment overrides .env.
        foreach ($key in @($settings.Keys)) {
            $processValue = [Environment]::GetEnvironmentVariable($key, 'Process')
            if ($null -ne $processValue) { $settings[$key] = $processValue }
        }
        foreach ($key in @('ADMIN_PASSWORD_HASH','PI_PROVIDER','PI_MODEL','PI_CLI','QQ_ENABLED','QQ_APP_ID','QQ_APP_SECRET','PORT')) {
            $processValue = [Environment]::GetEnvironmentVariable($key, 'Process')
            if ($null -ne $processValue) { $settings[$key] = $processValue }
        }
        return $settings
    }
    function Require-Value($settings, [string]$key) {
        if ([string]::IsNullOrWhiteSpace($settings[$key]) -or $settings[$key] -match '^replace-with|^your-') {
            throw "Configure $key in .env before starting. Values are not printed."
        }
    }
    $settings = Read-Settings
    foreach ($key in @('PI_PROVIDER','PI_MODEL')) { Require-Value $settings $key }
    if ($settings['QQ_ENABLED'] -eq 'true') {
        foreach ($key in @('QQ_APP_ID','QQ_APP_SECRET')) { Require-Value $settings $key }
    }
    $validHash = $settings['ADMIN_PASSWORD_HASH'] -match '^\$2[aby]\$\d{2}\$.{53}$'
    if (-not $validHash) {
        if ($Check) { throw 'ADMIN_PASSWORD_HASH is missing or still a template. Run this script without -Check to set your password and save the hash automatically.' }
        if ($null -ne [Environment]::GetEnvironmentVariable('ADMIN_PASSWORD_HASH', 'Process')) {
            throw 'An invalid ADMIN_PASSWORD_HASH process variable overrides .env. Correct or remove that variable, then run again.'
        }
        Write-Host 'Administrator password is not configured. Set and confirm a password (12+ characters).'
        & (Join-Path $PSScriptRoot 'admin/password-hash.ps1') -SaveToEnv
        $settings = Read-Settings
        if ($settings['ADMIN_PASSWORD_HASH'] -notmatch '^\$2[aby]\$\d{2}\$.{53}$') { throw 'Administrator hash was not saved. See README.md.' }
    }
    $cli = $settings['PI_CLI']
    if ([string]::IsNullOrWhiteSpace($cli)) { $cli = 'runtime/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js' }
    $jar = Join-Path $projectRoot 'target/qqbot-pi-0.2.0-SNAPSHOT.jar'
    if ($Check) {
        if (-not (Test-Path -LiteralPath $jar)) { throw 'JAR is missing. Run scripts/start.ps1 -Build or follow the README build commands.' }
        if (-not (Test-Path -LiteralPath $cli)) { throw 'pi runtime is missing. Run npm.cmd --prefix runtime/pi ci --ignore-scripts.' }
        Write-Host 'Configuration preflight passed. This does not test platform or model connectivity.'
        return
    }
    if ($Build -or -not (Test-Path -LiteralPath $jar)) {
        Write-Host 'Building application. Stop any existing Java instance before continuing.'
        & npm.cmd --prefix runtime/pi ci --ignore-scripts
        if ($LASTEXITCODE -ne 0) { throw 'pi dependency installation failed' }
        & npm.cmd --prefix admin-web ci
        if ($LASTEXITCODE -ne 0) { throw 'Admin dependency installation failed' }
        & npm.cmd --prefix admin-web run build
        if ($LASTEXITCODE -ne 0) { throw 'Admin build failed' }
        & .\mvnw.cmd verify -Pfrontend
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification/build failed; application was not started' }
    }
    if (-not (Test-Path -LiteralPath $cli)) { throw 'pi runtime is missing. Run npm.cmd --prefix runtime/pi ci --ignore-scripts.' }
    $port = if ($settings['PORT']) { $settings['PORT'] } else { '8080' }
    Write-Host "Starting qqbot-pi from project root. Admin: http://localhost:$port ; stop with Ctrl+C."
    & java -jar $jar
    if ($LASTEXITCODE -notin @(0, 130, -1073741510)) { throw 'Application startup failed. Read the final Caused by line above and check the setup in README.md.' }
} catch {
    Write-Host ('Startup stopped: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally { Pop-Location }
