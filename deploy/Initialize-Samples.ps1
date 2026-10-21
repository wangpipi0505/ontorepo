. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
$config = Import-OntoEnvironment
$env:PGPASSWORD = $config.ONTO_DB_PASSWORD
try {
    # 源样例与运行/测试库隔离，测试夹具 TRUNCATE 不会清空人工验收数据。
    $exists = & (Join-Path $config.ONTO_PG_BIN 'psql.exe') -h 127.0.0.1 -p $config.ONTO_PG_PORT -U onto -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname='onto_sources'"
    if ($LASTEXITCODE -ne 0) { throw '无法检查本工程样例数据库。' }
    if ($exists -ne '1') {
        Invoke-OntoCommand (Join-Path $config.ONTO_PG_BIN 'createdb.exe') @('-h','127.0.0.1','-p',$config.ONTO_PG_PORT,'-U','onto','onto_sources')
    }
    Invoke-OntoCommand (Join-Path $config.ONTO_PG_BIN 'psql.exe') @(
        '-h','127.0.0.1','-p',$config.ONTO_PG_PORT,'-U','onto','-d','onto_sources',
        '-v','ON_ERROR_STOP=1','-f',(Join-Path $root 'docs/fixtures/p3/source.sql')
    )
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
Write-Output '独立数据源样例已就绪：onto_sources / onto_sample；包含 A、B、未分类与跨标签关系反例。'
