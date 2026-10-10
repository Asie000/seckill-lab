# ============================================================
# hot-sweep.ps1 —— r5 缓存击穿梯度压测
#   对给定模式，依次跑多档并发；每档前重置回源统计并清缓存；
#   每档后读取回源次数（dbLoads）。
#
# 用法（需先以对应模式启动应用）：
#   powershell -File scripts\hot-sweep.ps1 -Mode off -Threads 100,200,500,1000
# ============================================================
param(
    [string]$Mode = 'off',
    [int[]]$Threads = @(100, 200, 500, 1000),
    [int]$Duration = 30
)

$ErrorActionPreference = 'Stop'
$root   = 'C:\Users\lsy1\Desktop\seckill-lab'
$jmeter = 'D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat'
$env:JAVA_HOME = 'C:\Users\lsy1\.jdks\ms-11.0.29'
$env:HEAP = '-Xms1g -Xmx2g'
$outDir = Join-Path $root 'bench\results'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$tmpl = Get-Content -Raw (Join-Path $root 'bench\hot-goods.jmx')

foreach ($t in $Threads) {
    Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/hot/stats/reset' -Method POST -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/1/hot/evict' -Method POST -UseBasicParsing | Out-Null

    $jmx = Join-Path $outDir "r5-hot-$Mode-$t.jmx"
    $tmpl.Replace('@THREADS@', "$t").Replace('@RAMP@', '10').Replace('@DURATION@', "$Duration") |
        Set-Content -Path $jmx -Encoding UTF8

    $jtl = Join-Path $outDir "r5-hot-$Mode-$t.jtl"
    $log = Join-Path $outDir "r5-hot-$Mode-$t.log"
    # JMeter -l 会追加，跑前必须删除旧文件，否则数据会累加
    if (Test-Path $jtl) { Remove-Item $jtl -Force }
    & $jmeter -n -t $jmx -l $jtl -j $log | Out-Null

    $stats = (Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/hot/stats' -UseBasicParsing).Content
    Write-Host "RESULT mode=$Mode threads=$t $stats"
}

Write-Host 'HOT SWEEP DONE'
