<#
[INPUT]: 读取发布源码、服务模板与归档运维说明。
[OUTPUT]: 生成 Linux amd64 镜像包、部署文件与校验和。
[POS]: 离线部署打包入口。ZIP 模式验证并使用同版本源码包。
[PROTOCOL]: Update this header on change, then check AGENTS.md
#>
param(
    [string]$Version = '0.2.0',
    [string]$OutputDirectory = 'dist',
    [ValidateSet('tar.gz', 'zip')][string]$Format = 'tar.gz'
)

$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^[a-z0-9][a-z0-9._-]{0,63}$') { throw 'Invalid release version' }
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath((Join-Path $workspace $OutputDirectory))
if (-not $output.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Release output must stay inside the workspace' }
$name = "qqbot-pi-server-$Version-linux-amd64"
$archive = Join-Path $output "$name.$Format"
foreach ($artifact in @($archive, "$archive.sha256", (Join-Path $output "$name.README.md"))) {
    if (Test-Path -LiteralPath $artifact) { throw "Release artifact already exists: $artifact" }
}
[void](New-Item -ItemType Directory -Path $output -Force)
$staging = Join-Path $workspace ('.state/release-staging/' + [guid]::NewGuid().ToString('N'))
$bundle = Join-Path $staging $name
[void](New-Item -ItemType Directory -Path $bundle -Force)
$utf8 = [Text.UTF8Encoding]::new($false)
$buildWorkspace = $workspace
$sourceSnapshot = $null
if ($Format -eq 'zip') {
    $sourceArchive = Join-Path $output "qqbot-pi-source-$Version.zip"
    if (-not (Test-Path -LiteralPath $sourceArchive -PathType Leaf) -or -not (Test-Path -LiteralPath "$sourceArchive.sha256" -PathType Leaf)) { throw 'Create the matching source ZIP and checksum before the server ZIP' }
    $sourceHash = (Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    $expectedChecksum = "$sourceHash  qqbot-pi-source-$Version.zip"
    if ([IO.File]::ReadAllText("$sourceArchive.sha256").Trim() -ne $expectedChecksum) { throw 'Source archive checksum mismatch' }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sourceDirectory = Join-Path $staging 'source'
    $sourceZip = [IO.Compression.ZipFile]::OpenRead($sourceArchive)
    try {
        foreach ($entry in $sourceZip.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $sourceDirectory $entry.FullName))
            if (-not $entry.FullName.StartsWith('qqbot-pi/') -or -not $target.StartsWith($sourceDirectory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source archive contains an unsafe entry' }
        }
    } finally { $sourceZip.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($sourceArchive, $sourceDirectory)
    $buildWorkspace = Join-Path $sourceDirectory 'qqbot-pi'
    $sourceManifest = [IO.File]::ReadAllText((Join-Path $buildWorkspace 'SOURCE-MANIFEST.json')) | ConvertFrom-Json
    if ($sourceManifest.version -ne $Version -or $sourceManifest.archiveFormat -ne 'zip') { throw 'Source archive version or format mismatch' }
    $sourceChecksumFile = Join-Path $buildWorkspace 'SOURCE-FILES.sha256'
    foreach ($line in [IO.File]::ReadAllLines($sourceChecksumFile)) {
        $parts = $line -split '  ', 2
        if ($parts.Count -ne 2) { throw 'Malformed source checksum entry' }
        $target = [IO.Path]::GetFullPath((Join-Path $buildWorkspace $parts[1]))
        if (-not $target.StartsWith($buildWorkspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source checksum path escaped the snapshot' }
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $parts[0]) { throw "Source file checksum mismatch: $($parts[1])" }
    }
    $sourceSnapshot = [ordered]@{ archive = [IO.Path]::GetFileName($sourceArchive); archiveSHA256 = $sourceHash; sourceFilesSHA256 = (Get-FileHash -LiteralPath $sourceChecksumFile -Algorithm SHA256).Hash.ToLowerInvariant() }
}

Push-Location $buildWorkspace
try {
    docker build --platform linux/amd64 --tag "qqbot-pi:$Version" .
    if ($LASTEXITCODE -ne 0) { throw 'Bot image build failed' }
    docker build --platform linux/amd64 --build-arg VITE_ADMIN_PORT=8080 --tag "xiu-bot-front:$Version" ./xiu-bot-front
    if ($LASTEXITCODE -ne 0) { throw 'Showcase image build failed' }
    $images = @()
    foreach ($tag in @("qqbot-pi:$Version", "xiu-bot-front:$Version")) {
        $image = (docker image inspect $tag | ConvertFrom-Json)[0]
        if ($LASTEXITCODE -ne 0 -or $image.Os -ne 'linux' -or $image.Architecture -ne 'amd64') { throw "Unexpected image platform: $tag" }
        $images += [ordered]@{ tag = $tag; id = $image.Id; platform = 'linux/amd64' }
    }
    $templates = [ordered]@{
        'compose.server.yaml' = 'compose.yaml'
        'server.env.example' = '.env.example'
        'deploy.sh' = 'deploy.sh'
        'SERVER-README.md' = 'README.md'
        'raw-archive.md' = 'raw-archive.md'
    }
    foreach ($template in $templates.GetEnumerator()) {
        $extractCommand = if ($Format -eq 'zip') { "unzip $name.zip" } else { "tar -xzf $name.tar.gz" }
        $text = [IO.File]::ReadAllText((Join-Path $buildWorkspace "scripts/release/$($template.Key)")).Replace('__RELEASE_VERSION__', $Version).Replace('__ARCHIVE_EXTENSION__', $Format).Replace('__EXTRACT_COMMAND__', $extractCommand).Replace("`r`n", "`n")
        [IO.File]::WriteAllText((Join-Path $bundle $template.Value), $text, $utf8)
    }
    $manifest = [ordered]@{ version = $Version; createdAt = [DateTime]::UtcNow.ToString('o'); platform = 'linux/amd64'; database = 'sqlite'; archiveFormat = $Format; archiveImplementation = $(if ($Format -eq 'zip') { 'System.IO.Compression.ZipArchive' } else { 'tar' }); sourceSnapshot = $sourceSnapshot; images = $images }
    [IO.File]::WriteAllText((Join-Path $bundle 'release.json'), ($manifest | ConvertTo-Json -Depth 5) + "`n", $utf8)
    docker image save --output (Join-Path $bundle 'images.tar') "qqbot-pi:$Version" "xiu-bot-front:$Version"
    if ($LASTEXITCODE -ne 0) { throw 'Docker image export failed' }
    $checksums = Get-ChildItem -LiteralPath $bundle -File | Sort-Object Name | ForEach-Object {
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    }
    [IO.File]::WriteAllText((Join-Path $bundle 'FILES.sha256'), ($checksums -join "`n") + "`n", $utf8)
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
        tar -czf $archive -C $staging $name
        if ($LASTEXITCODE -ne 0) { throw 'Release archive creation failed' }
    }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$archive.sha256", "$hash  $name.$Format`n", $utf8)
    Copy-Item -LiteralPath (Join-Path $bundle 'README.md') -Destination (Join-Path $output "$name.README.md")
    [PSCustomObject]@{ Archive = $archive; SHA256 = $hash; Bytes = (Get-Item -LiteralPath $archive).Length; Staging = $bundle }
} finally {
    Pop-Location
}
