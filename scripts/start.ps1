[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('dev', 'demo', 'test')]
    [string]$DataProfile,

    [ValidateSet('migrate', 'init-admin', 'serve')]
    [string]$Mode = 'serve',

    [guid]$TestRunId
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'backend/target/gpu-queue.jar'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$Mode = $Mode.ToLowerInvariant()

if ($DataProfile -eq 'test') {
    if (!$PSBoundParameters.ContainsKey('TestRunId') -or $TestRunId -eq [guid]::Empty) {
        throw '请通过 -TestRunId 提供本次验收的 GUID。'
    }
    $dataDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "gpu-queue-test-$TestRunId"
} else {
    $dataDirectory = Join-Path $projectRoot ".local-data/$DataProfile"
}

New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null
Write-Host "H2 数据文件：$(Join-Path $dataDirectory 'gpu-queue.mv.db')"
& $java -jar $jar migrate $dataDirectory
if ($LASTEXITCODE -ne 0) {
    throw "数据库迁移失败，退出码 $LASTEXITCODE。"
}

if ($Mode -ne 'migrate') {
    & $java -jar $jar $Mode $dataDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "$Mode 失败，退出码 $LASTEXITCODE。"
    }
}
