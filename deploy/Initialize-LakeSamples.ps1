# 复用本工程已经验证过的依赖classpath；不运行并发Maven、不修改共享target。
. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
$config = Import-OntoEnvironment
Set-OntoJava
$asciiRoot = (Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE).Replace('\','/')
$reportPath = Join-Path $root 'onto-data/target/surefire-reports/TEST-cn.ontorepo.data.domain.ParquetIntegrationTest.xml'
if (-not (Test-Path -LiteralPath $reportPath)) {
    throw '请先运行onto-data真实测试，以建立本工程已解析的Parquet/MinIO依赖classpath。'
}
[xml]$testReport = Get-Content -LiteralPath $reportPath -Raw
$classpath = ($testReport.testsuite.properties.property | Where-Object name -EQ 'java.class.path').value
if (-not $classpath) { throw '测试报告缺少classpath，拒绝从其他工程搜索依赖。' }
$classpath = $classpath.Replace('\','/').Replace($root.Replace('\','/'), $asciiRoot.TrimEnd('/'))
$argfile = Join-Path $root '.runtime/lake-sample-java.args'
$arguments = @(
    '-Xmx512m'
    '-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp'
    '--class-path'
    ('"' + $classpath + '"')
    '--source'
    '21'
    ('"' + $asciiRoot + 'deploy/InitializeLakeSamples.java"')
)
[IO.File]::WriteAllLines($argfile, $arguments, [Text.UTF8Encoding]::new($false))
Push-Location $root
try { Invoke-OntoCommand (Join-Path $env:JAVA_HOME 'bin/java.exe') @("@$argfile") }
finally { Pop-Location }
