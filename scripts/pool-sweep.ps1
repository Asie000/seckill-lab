# ============================================================
# pool-sweep.ps1 —— 连接池梯度实验
#   固定并发下，轮流用不同 maximum-pool-size 重启应用并压测。
#
# 用法：
#   powershell -File scripts\pool-sweep.ps1 -Pools 10,20,30,50 -Threads 200 -Duration 60 -Tag r2-poolsweep
# ============================================================
param(
    [string[]]$Pools = @('10', '20', '30', '50'),
    [int]$Threads = 200,
    [int]$Duration = 60,
    [string]$Tag = 'r2-poolsweep'
)

$ErrorActionPreference = 'Stop'
$root   = 'C:\Users\lsy1\Desktop\seckill-lab'
$jmeter = 'D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat'
$env:JAVA_HOME = 'C:\Users\lsy1\.jdks\ms-11.0.29'
$env:HEAP = '-Xms1g -Xmx2g'
$jar  = Join-Path $root 'target\seckill-lab-0.0.1-SNAPSHOT.jar'
$java = 'C:\Users\lsy1\.jdks\openjdk-25.0.1\bin\java.exe'
$outDir = Join-Path $root 'bench\results'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Stop-App {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*seckill-lab-0.0.1-SNAPSHOT.jar*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 2
}

function Start-App([string]$pool) {
    Start-Process -FilePath $java -ArgumentList @('-jar', $jar, "--spring.datasource.hikari.maximum-pool-size=$pool") `
        -WorkingDirectory $root `
        -RedirectStandardOutput (Join-Path $root 'target\app.log') `
        -RedirectStandardError  (Join-Path $root 'target\app.err.log') `
        -WindowStyle Hidden | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 1
        if ((New-Object Net.Sockets.TcpClient).ConnectAsync('127.0.0.1', 8081).Wait(500)) { return $true }
    }
    return $false
}

$tmpl = Get-Content -Raw (Join-Path $root 'bench\order-list.jmx')

foreach ($pool in $Pools) {
    Write-Host "===== pool=$pool threads=$Threads ====="
    Stop-App
    if (-not (Start-App $pool)) { Write-Host "[sweep] app NOT up for pool=$pool"; continue }
    $jmx = Join-Path $outDir "$Tag-$pool.jmx"
    $tmpl.Replace('@THREADS@', "$Threads").Replace('@RAMP@', '10').Replace('@DURATION@', "$Duration") |
        Set-Content -Path $jmx -Encoding UTF8
    $jtl = Join-Path $outDir "$Tag-$pool.jtl"
    & $jmeter -n -t $jmx -l $jtl -j (Join-Path $outDir "$Tag-$pool.log")
}

Write-Host 'SWEEP DONE'
