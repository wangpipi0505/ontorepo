param(
    [string]$PostgresBin,
    [int]$DatabasePort = 15472,
    [string]$Drive = 'O:',
    [string]$JavaHome = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
)
. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
if (-not $PostgresBin) { $PostgresBin = Join-Path $root '.tools/postgresql-16.15/pgsql/bin' }
$PostgresBin = [IO.Path]::GetFullPath($PostgresBin)
if (-not (Test-Path -LiteralPath (Join-Path $PostgresBin 'pg_ctl.exe'))) {
    throw '请将 PostgreSQL Windows 二进制解压到 .tools/postgresql-16.15，或指定 -PostgresBin。'
}
$runtime = Join-Path $root '.runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$asciiRoot = Get-OntoAsciiRoot $Drive
$dataDirectory = Join-Path $asciiRoot '.runtime/pgsql'
# PostgreSQL 的 Windows 进程在 C locale 下不能可靠地把中文安装路径写入 UTF-8 系统目录。
# 映射盘仅为本工程提供 ASCII 入口，实际数据和二进制仍在原工作目录内。
$mappedBin = Join-Path $asciiRoot '.tools/postgresql-16.15/pgsql/bin'
if (Test-Path -LiteralPath $mappedBin) { $PostgresBin = $mappedBin }
$configPath = Join-Path $runtime 'local-env.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    $config = [ordered]@{
        ONTO_DB_URL = "jdbc:postgresql://127.0.0.1:${DatabasePort}/onto_dev"
        ONTO_DB_USER = 'onto'
        ONTO_DB_PASSWORD = New-OntoPassword
        ONTO_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:${DatabasePort}/onto_test"
        ONTO_TEST_DB_USER = 'onto'
        ONTO_BOOTSTRAP_USERNAME = 'admin'
        ONTO_BOOTSTRAP_PASSWORD = New-OntoPassword
        ONTO_BOOTSTRAP_DISPLAY_NAME = '本体管理员'
        ONTO_JAVA_HOME = $JavaHome
        ONTO_PG_BIN = $PostgresBin
        ONTO_PG_PORT = "$DatabasePort"
        ONTO_LOCAL_DRIVE = $Drive
    }
    $config.ONTO_TEST_DB_PASSWORD = $config.ONTO_DB_PASSWORD
    $config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
}
$savedConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$savedConfig.ONTO_PG_BIN = $PostgresBin
$savedConfig | Add-Member -NotePropertyName ONTO_LOCAL_DRIVE -NotePropertyValue $Drive -Force
$savedConfig | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
$config = Import-OntoEnvironment
# 已初始化时以工程保存的端口和二进制为准，防止第二次执行参数误指向其他数据库。
$PostgresBin = $config.ONTO_PG_BIN
$DatabasePort = [int]$config.ONTO_PG_PORT
if (-not (Test-Path -LiteralPath (Join-Path $dataDirectory 'PG_VERSION'))) {
    $passwordFile = Join-Path $asciiRoot '.runtime/initdb-password.tmp'
    try {
        [IO.File]::WriteAllText($passwordFile, $config.ONTO_DB_PASSWORD, [Text.UTF8Encoding]::new($false))
        Invoke-OntoCommand (Join-Path $PostgresBin 'initdb.exe') @(
            '-D', $dataDirectory, '-U', 'onto', '--auth=scram-sha-256',
            '--encoding=UTF8', '--locale=C', "--pwfile=$passwordFile"
        )
    } finally {
        if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile }
    }
}
& (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataDirectory status *> $null
if ($LASTEXITCODE -ne 0) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $DatabasePort -ErrorAction SilentlyContinue
    if ($listener) { throw "端口 $DatabasePort 已占用，未启动或修改占用它的服务。" }
    Invoke-OntoCommand (Join-Path $PostgresBin 'pg_ctl.exe') @(
        '-D', $dataDirectory, '-l', (Join-Path $asciiRoot '.runtime/postgres.log'),
        '-o', "-p $DatabasePort -h 127.0.0.1", '-w', 'start'
    )
}
$env:PGPASSWORD = $config.ONTO_DB_PASSWORD
try {
    foreach ($database in @('onto_dev', 'onto_test')) {
        $exists = & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $DatabasePort -U onto -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$database'"
        if ($LASTEXITCODE -ne 0) { throw '读取本工程数据库列表失败。' }
        if ($exists -ne '1') {
            Invoke-OntoCommand (Join-Path $PostgresBin 'createdb.exe') @(
                '-h', '127.0.0.1', '-p', "$DatabasePort", '-U', 'onto', $database
            )
        }
    }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
Write-Output "独立 PostgreSQL 已就绪：127.0.0.1:$DatabasePort（onto_dev / onto_test）。"
Write-Output '开发账号与随机口令保存在 .runtime/local-env.json；该文件已被 Git 忽略，请勿提交或分享。'
