param([switch]$SaveToEnv)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $projectRoot
try {
    & .\mvnw.cmd -q -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/password-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Maven compilation failed' }
    $taskClasspath = 'target/classes;' + (Get-Content -Raw target/password-classpath.txt).Trim()
    if ($SaveToEnv) {
        & java -cp $taskClasspath dev.qqbot.security.PasswordHash --write-env (Join-Path $projectRoot '.env')
    } else {
        & java -cp $taskClasspath dev.qqbot.security.PasswordHash
    }
    if ($LASTEXITCODE -ne 0) { throw 'Password setup failed; .env was not updated' }
} finally { Pop-Location }
