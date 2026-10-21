# 浏览器自动化的本地辅助：将随机密码写入短生命周期脚本，并屏蔽 CLI 对执行代码的回显。
# 仅用于已经由 playwright-cli 打开的 onto-p1 会话，不向版本库保存密码或令牌。
. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
$config = Import-OntoEnvironment
$scriptPath = Join-Path $root '.runtime/browser-login.js'
$outputPath = Join-Path $root '.runtime/browser-login-output.txt'
$username = $config.ONTO_BOOTSTRAP_USERNAME | ConvertTo-Json -Compress
$password = $config.ONTO_BOOTSTRAP_PASSWORD | ConvertTo-Json -Compress
$code = @"
async (page) => {
  page.setDefaultTimeout(10000);
  await page.goto('http://127.0.0.1:15184/login');
  await page.getByPlaceholder('请输入账号').fill($username);
  await page.getByPlaceholder('请输入密码').fill($password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL('**/spaces');
  return { url: page.url(), loggedIn: true };
}
"@
try {
    [IO.File]::WriteAllText($scriptPath, $code, [Text.UTF8Encoding]::new($false))
    & npx.cmd --yes --package '@playwright/cli' playwright-cli -s=onto-p1 run-code --filename $scriptPath *> $outputPath
    $output = Get-Content -LiteralPath $outputPath -Raw -Encoding UTF8
    if ($LASTEXITCODE -ne 0 -or $output -notmatch '"loggedIn":true') {
        $safeOutput = $output.Replace($config.ONTO_BOOTSTRAP_PASSWORD, '[REDACTED]')
        Write-Output $safeOutput
        throw '浏览器登录未完成；请使用 playwright-cli snapshot 检查页面，凭据回显已屏蔽。'
    }
    Write-Output '真实浏览器登录成功，当前页 /spaces。'
} finally {
    foreach ($path in @($scriptPath, $outputPath)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
}
