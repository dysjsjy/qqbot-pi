$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & .\mvnw.cmd -q -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/password-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Maven compilation failed' }
    $taskClasspath = 'target/classes;' + (Get-Content -Raw target/password-classpath.txt).Trim()
    & java -cp $taskClasspath dev.qqbot.security.PasswordHash
} finally { Pop-Location }
