# ============================================================
# ml-sweep.ps1 —— r6 多级缓存梯度压测
#   对给定模式（ml=多级 / redis=仅Redis），依次跑多档并发；
#   每档前重置统计并清缓存；每档后读取 L1/L2/DB 命中数。
#
# 用法（需先以对应开关启动应用）：
#   powershell -File scripts\ml-sweep.ps1 -Mode ml     -Threads 100,200,500,1000
#   powershell -File scripts\ml-sweep.ps1 -Mode redis  -Threads 100,200,500,1000
# ============================================================
param(
    [string]$Mode = 'ml',
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

$tmpl = Get-Content -Raw (Join-Path $root 'bench\ml-goods.jmx')

foreach ($t in $Threads) {
    Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/ml/stats/reset' -Method POST -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/1/ml/evict' -Method POST -UseBasicParsing | Out-Null

    $jmx = Join-Path $outDir "r6-ml-$Mode-$t.jmx"
    $tmpl.Replace('@THREADS@', "$t").Replace('@RAMP@', '10').Replace('@DURATION@', "$Duration") |
        Set-Content -Path $jmx -Encoding UTF8

    $jtl = Join-Path $outDir "r6-ml-$Mode-$t.jtl"
    $log = Join-Path $outDir "r6-ml-$Mode-$t.log"
    if (Test-Path $jtl) { Remove-Item $jtl -Force }
    & $jmeter -n -t $jmx -l $jtl -j $log | Out-Null

    $stats = (Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/ml/stats' -UseBasicParsing).Content
    Write-Host "RESULT mode=$Mode threads=$t $stats"
}

Write-Host 'ML SWEEP DONE'
