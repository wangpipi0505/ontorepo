. (Join-Path $PSScriptRoot 'Environment.ps1')
$root = Get-OntoRoot
$configPath = Join-Path $root '.runtime/local-env.json'
$config = Import-OntoEnvironment
$asciiRoot = Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE

# 密钥只在首次初始化时随机生成。已存在的密钥绝不轮换，否则旧候选的源配置将无法解密。
# 实际生产部署应从受控秘密存储注入同一变量；本地文件已被 Git 忽略，输出不包含其值。
$keyProperty = $config.PSObject.Properties['ONTO_DATASOURCE_KEY']
if ($null -eq $keyProperty) {
    $keyBytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($keyBytes)
        $config | Add-Member -NotePropertyName ONTO_DATASOURCE_KEY -NotePropertyValue ([Convert]::ToBase64String($keyBytes))
    } finally {
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
        $random.Dispose()
    }
} else {
    $existingBytes = $null
    try {
        $existingBytes = [Convert]::FromBase64String([string]$keyProperty.Value)
        if ($existingBytes.Length -ne 32) { throw 'invalid length' }
    } catch {
        throw '现有 ONTO_DATASOURCE_KEY 不是有效的 32 字节 Base64 密钥，已拒绝覆盖。'
    } finally {
        if ($null -ne $existingBytes) { [Array]::Clear($existingBytes, 0, $existingBytes.Length) }
    }
}

$filesRoot = Join-Path $asciiRoot '.runtime/files'
$config | Add-Member -NotePropertyName ONTO_FILES_ROOT -NotePropertyValue $filesRoot -Force
New-Item -ItemType Directory -Path $filesRoot -Force | Out-Null

# 临时文件和替换目标均为本工程已解析的运行目录；单次替换防止中途退出留下半份 JSON。
$temporary = Join-Path $root '.runtime/local-env-data.tmp'
try {
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $configPath -Force
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
Write-Output '数据源密钥与受管文件目录已就绪；已有密钥保持不变，配置仅保存在本工程 .runtime。'
