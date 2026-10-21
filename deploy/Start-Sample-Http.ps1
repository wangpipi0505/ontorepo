# 显式启动验收样例；不在正常业务启动时自动增加监听器。
. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
if (Get-NetTCPConnection -State Listen -LocalPort 19180 -ErrorAction SilentlyContinue) {
    throw '样例端口19180已占用；未复用或终止未知服务。'
}
$sampleScript = Join-Path $root 'deploy/Sample-Http.mjs'
$process = Start-Process -FilePath (Get-Command node.exe).Source `
    -ArgumentList @('"' + $sampleScript + '"') -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $root '.runtime/sample-http.log') `
    -RedirectStandardError (Join-Path $root '.runtime/sample-http-error.log')
@{ pid = $process.Id; executable = $process.Path; marker = $sampleScript } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.runtime/sample-http-process.json') -Encoding UTF8
Write-Output '已启动本工程HTTP验收样例：127.0.0.1:19180；仅测试夹具数据。'
