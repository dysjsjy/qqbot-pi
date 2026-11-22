<#
[INPUT]: 读取工作区源码、发布模板与 Java 回归夹具。
[OUTPUT]: 生成源码包、逐文件清单与 SHA256 校验文件。
[POS]: 仅复制发布目录，隔离本机数据，生成 Docker 发布快照。
[PROTOCOL]: Update this header on change, then check AGENTS.md
#>
param(
    [string]$Version = '0.2.0',
    [string]$OutputDirectory = 'dist',
    [ValidateSet('tar.gz', 'zip')][string]$Format = 'tar.gz',
    [switch]$CodeOnly
)

$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^[a-z0-9][a-z0-9._-]{0,63}$') { throw 'Invalid source release version' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath((Join-Path $workspace $OutputDirectory))
if (-not $output.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source output must stay inside the workspace' }
$name = "qqbot-pi-source-$Version"
$archive = Join-Path $output "$name.$Format"
foreach ($artifact in @($archive, "$archive.sha256", (Join-Path $output "$name.README.md"))) {
    if (Test-Path -LiteralPath $artifact) { throw "Source artifact already exists: $artifact" }
}
[void](New-Item -ItemType Directory -Path $output -Force)
$staging = Join-Path $workspace ('.state/source-staging/' + [guid]::NewGuid().ToString('N'))
$bundle = Join-Path $staging 'qqbot-pi'
[void](New-Item -ItemType Directory -Path $bundle -Force)
$utf8 = [Text.UTF8Encoding]::new($false)
$excludedDirectories = @('.git', '.idea', '.state', '.tools', '.ssh', '.aws', '.pi', '.codex', '.cache', 'node_modules', 'target', 'dist', 'test-results', 'playwright-report', '__pycache__', '.pytest_cache', '.vite', 'backups', 'tmp', 'local', 'artifacts', 'coverage', 'blob-report')

function Copy-SourceEntry([string]$Source, [string]$Destination) {
    $entry = Get-Item -LiteralPath $Source -Force
    if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Source snapshot cannot follow a link: $Source" }
    if ($entry.PSIsContainer) {
        if ($entry.Name -in $excludedDirectories) { return }
        [void](New-Item -ItemType Directory -Path $Destination -Force)
        foreach ($child in Get-ChildItem -LiteralPath $Source -Force) {
            Copy-SourceEntry $child.FullName (Join-Path $Destination $child.Name)
        }
        return
    }
    if ($entry.Name -like '.env*' -and -not $entry.Name.EndsWith('.example')) { return }
    if ($entry.Name -match '(?i)(?:\.db(?:-.*)?|\.sqlite(?:3)?(?:-(?:wal|shm|journal))?|\.bak|\.backup|\.bot\.lock|\.log|\.pyc|\.tsbuildinfo|\.pem|\.key|\.pfx|\.p12|\.jks|\.keystore)$') { return }
    if ($entry.Name -match '(?i)^(?:chat|conversation|session|messages?|transcript)(?:[-_.].*)?\.jsonl$') { return }
    if ($entry.Name -in @('.npmrc', 'id_rsa', 'id_ed25519', 'credentials.json', 'auth.json', 'tokens.json', 'cookies.txt')) { return }
    if ($entry.Name -eq 'tmp.md') { return }
    Copy-Item -LiteralPath $Source -Destination $Destination
}

foreach ($directory in @('src', 'admin-web', 'xiu-bot-front', 'runtime', 'scripts', '.mvn', '.github')) {
    Copy-SourceEntry (Join-Path $workspace $directory) (Join-Path $bundle $directory)
}
foreach ($file in @('pom.xml', 'mvnw', 'mvnw.cmd', 'Dockerfile', 'compose.yaml', 'compose.sqlite.yaml', 'README.md', 'AGENTS.md', '.env.example', '.env.sqlite.example', '.env.qq-official.example', '.gitignore', '.gitattributes', '.dockerignore')) {
    Copy-SourceEntry (Join-Path $workspace $file) (Join-Path $bundle $file)
}
$extractCommand = if ($Format -eq 'zip') { "unzip $name.zip" } else { "tar -xzf $name.tar.gz" }
$guide = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'SOURCE-README.md')).Replace('__RELEASE_VERSION__', $Version).Replace('__ARCHIVE_EXTENSION__', $Format).Replace('__EXTRACT_COMMAND__', $extractCommand).Replace("`r`n", "`n")
[IO.File]::WriteAllText((Join-Path $bundle 'SOURCE-DEPLOY.md'), $guide, $utf8)
$sourceFiles = @(Get-ChildItem -LiteralPath $bundle -File -Recurse -Force)
$manifest = [ordered]@{ version = $Version; createdAt = [DateTime]::UtcNow.ToString('o'); type = 'source-workspace-snapshot'; codeOnly = [bool]$CodeOnly; archiveFormat = $Format; archiveImplementation = $(if ($Format -eq 'zip') { 'System.IO.Compression.ZipArchive' } else { 'tar' }); sourceFiles = $sourceFiles.Count; includesUncommittedFiles = $true; includesDependencies = $false; includesLocalConfigurationOrData = $false }
[IO.File]::WriteAllText((Join-Path $bundle 'SOURCE-MANIFEST.json'), ($manifest | ConvertTo-Json) + "`n", $utf8)
$checksums = Get-ChildItem -LiteralPath $bundle -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($bundle.Length + 1).Replace('\', '/')
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    "$hash  $relative"
}
[IO.File]::WriteAllText((Join-Path $bundle 'SOURCE-FILES.sha256'), ($checksums -join "`n") + "`n", $utf8)
if ($Format -eq 'zip') {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in Get-ChildItem -LiteralPath $bundle -File -Recurse -Force) {
            $relative = $file.FullName.Substring($staging.Length + 1).Replace('\', '/')
            $entry = $zip.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
            $inputStream = [IO.File]::OpenRead($file.FullName)
            try {
                $outputStream = $entry.Open()
                try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
            } finally { $inputStream.Dispose() }
        }
    } finally { $zip.Dispose() }
} else {
    tar -czf $archive -C $staging qqbot-pi
    if ($LASTEXITCODE -ne 0) { throw 'Source archive creation failed' }
}
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$archive.sha256", "$hash  $name.$Format`n", $utf8)
Copy-Item -LiteralPath (Join-Path $bundle 'SOURCE-DEPLOY.md') -Destination (Join-Path $output "$name.README.md")
[PSCustomObject]@{ Archive = $archive; SHA256 = $hash; Bytes = (Get-Item -LiteralPath $archive).Length; SourceFiles = $sourceFiles.Count; Staging = $bundle }
