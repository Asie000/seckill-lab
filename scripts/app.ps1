# ============================================================
# app.ps1 —— 应用启停（供压测反复重启用）
#
# 用法：
#   powershell -File scripts\app.ps1 -Action restart                # 用 yml 默认连接池
#   powershell -File scripts\app.ps1 -Action restart -Pool 20       # 运行时覆盖连接池大小
#
# 说明：-Pool 通过 --spring.datasource.hikari.maximum-pool-size=N 覆盖，
#       用于连接池梯度实验，不必反复改 yml。
# ============================================================
param(
    [ValidateSet('start', 'stop', 'restart')][string]$Action = 'restart',
    [int]$Pool = 0,
    [switch]$NoSqlLog,
    [switch]$CacheOff,
    [switch]$AsyncOff
)

$ErrorActionPreference = 'Stop'
$root = 'C:\Users\lsy1\Desktop\seckill-lab'
$jar  = Join-Path $root 'target\seckill-lab-0.0.1-SNAPSHOT.jar'
$java = 'C:\Users\lsy1\.jdks\openjdk-25.0.1\bin\java.exe'

function Stop-App {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*seckill-lab-0.0.1-SNAPSHOT.jar*' } |
        ForEach-Object { Write-Host "[app] kill pid=$($_.ProcessId)"; Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 2
}

function Start-App {
    $javaArgs = @('-jar', $jar)
    if ($Pool -gt 0) { $javaArgs += "--spring.datasource.hikari.maximum-pool-size=$Pool" }
    if ($NoSqlLog) { $javaArgs += '--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl' }
    if ($CacheOff) { $javaArgs += '--seckill.cache.goods.enabled=false' }
    if ($AsyncOff) { $javaArgs += '--seckill.order.async.enabled=false' }
    Write-Host "[app] start pool=$(if($Pool -gt 0){$Pool}else{'yml默认'}) sqlLog=$(if($NoSqlLog){'off'}else{'on'}) cache=$(if($CacheOff){'off'}else{'on'}) async=$(if($AsyncOff){'off'}else{'on'})"
    Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $root `
        -RedirectStandardOutput (Join-Path $root 'target\app.log') `
        -RedirectStandardError  (Join-Path $root 'target\app.err.log') `
        -WindowStyle Hidden | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 1
        if ((New-Object Net.Sockets.TcpClient).ConnectAsync('127.0.0.1', 8081).Wait(500)) {
            Write-Host "[app] up after ${i}s"
            return
        }
    }
    Write-Host "[app] NOT up after 40s"
}

switch ($Action) {
    'stop'    { Stop-App }
    'start'   { Start-App }
    'restart' { Stop-App; Start-App }
}
