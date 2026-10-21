# 使用本工程已解析的测试依赖生成样例，不启动Maven、不写共享target。
. (Join-Path $PSScriptRoot 'Environment.ps1')
$projectRoot = Get-OntoRoot
$config = Import-OntoEnvironment
Set-OntoJava
$asciiRoot = (Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE).Replace('\','/')
$reportPath = Join-Path $projectRoot 'onto-designer-api/target/surefire-reports/TEST-cn.ontorepo.P3IngestTest.xml'
if (-not (Test-Path -LiteralPath $reportPath)) { throw '请先运行P3IngestTest，以解析本工程的POI/PDFBox依赖。' }
[xml]$testReport = Get-Content -LiteralPath $reportPath -Raw
$classpath = ($testReport.testsuite.properties.property | Where-Object name -EQ 'java.class.path').value
if (-not $classpath) { throw '当前工程测试报告缺少classpath。' }
$classpath = $classpath.Replace('\','/').Replace($projectRoot.Replace('\','/'), $asciiRoot.TrimEnd('/'))
$argfile = Join-Path $projectRoot '.runtime/document-sample-java.args'
$arguments = @(
    '-Xmx256m'
    '--class-path'
    ('"' + $classpath + '"')
    '--source'
    '21'
    ('"' + $asciiRoot + 'deploy/InitializeDocumentSamples.java"')
)
[IO.File]::WriteAllLines($argfile, $arguments, [Text.UTF8Encoding]::new($false))
Push-Location $projectRoot
try { Invoke-OntoCommand (Join-Path $env:JAVA_HOME 'bin/java.exe') @("@$argfile") }
finally { Pop-Location }
