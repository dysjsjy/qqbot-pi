# [INPUT]: 接收停机后的 SQLite、状态目录及离线冷留存水位。
# [OUTPUT]: 调用归档命令并保存校验或迁移报告。
# [POS]: PowerShell 入口；归档读写由 RawArchiveCli 执行。
# [PROTOCOL]: Update this header on change, then check AGENTS.md
param(
    [Parameter(Mandatory=$true)][ValidateSet('inspect','migrate','verify','finalize','export-legacy')][string]$Command,
    [Parameter(Mandatory=$true)][string]$Database,
    [Parameter(Mandatory=$true)][string]$StateDir,
    [Parameter(Mandatory=$true)][string]$Report,
    [string]$GroupId,
    [string]$Now,
    [string]$OutputDir,
    [ValidateRange(0,3650)][int]$ArchiveRetentionDays=0,
    [string]$Jar
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$arguments = @('--command',$Command,'--database',[IO.Path]::GetFullPath($Database),'--state-dir',[IO.Path]::GetFullPath($StateDir),'--report',[IO.Path]::GetFullPath($Report))
if ($GroupId) { $arguments += @('--group-id',$GroupId) }
if ($Now) { $arguments += @('--now',$Now) }
$arguments += @('--archive-retention-days',[string]$ArchiveRetentionDays)
if ($OutputDir) { $arguments += @('--output-dir',[IO.Path]::GetFullPath($OutputDir)) }
if ($Jar) {
    & java '-Dloader.main=dev.qqbot.memory.RawArchiveCli' -cp ([IO.Path]::GetFullPath($Jar)) 'org.springframework.boot.loader.launch.PropertiesLauncher' @arguments
} else {
    Push-Location -LiteralPath $workspace
    try {
        & ./mvnw.cmd -q -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/raw-archive.classpath'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot build offline archive tool' }
        $dependencies = (Get-Content -LiteralPath 'target/raw-archive.classpath' -Raw).Trim()
        $classpath = (Join-Path $workspace 'target/classes') + [IO.Path]::PathSeparator + $dependencies
        & java -cp $classpath 'dev.qqbot.memory.RawArchiveCli' @arguments
    } finally { Pop-Location }
}
if ($LASTEXITCODE -ne 0) { throw "RAW archive command failed with exit code $LASTEXITCODE" }
