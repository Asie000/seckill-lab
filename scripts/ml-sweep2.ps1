# ============================================================
# ml-sweep2.ps1 —— r6b 多级缓存加强压测
#   与 ml-sweep.ps1 的区别：从 Redis 自身（INFO commandstats）
#   读取真实的 GET 命令次数，而不是靠应用内计数器推算。
#
# 用法（需先以对应开关启动应用）：
#   powershell -File scripts\ml-sweep2.ps1 -Mode ml    -Threads 100,200,500,1000
#   powershell -File scripts\ml-sweep2.ps1 -Mode redis -Threads 100,200,500,1000
# ============================================================
param(
    [string]$Mode = 'ml',
    [int[]]$Threads = @(100, 200, 500, 1000),
    [int]$Duration = 30,
    [string]$Jmx = 'bench\ml-goods-random.jmx',
    [string]$JmxTag = 'rand'
)

$ErrorActionPreference = 'Stop'
$root     = 'C:\Users\lsy1\Desktop\seckill-lab'
$jmeter   = 'D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat'
$redisCli = 'C:\Users\lsy1\redis\redis-cli.exe'
$env:JAVA_HOME = 'C:\Users\lsy1\.jdks\ms-11.0.29'
$env:HEAP = '-Xms1g -Xmx2g'
$outDir = Join-Path $root 'bench\results'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Get-RedisCalls([string]$cmd) {
    $out  = & $redisCli INFO commandstats
    $line = $out | Select-String "cmdstat_${cmd}:calls=(\d+)"
    if ($line) { return [long]$line.Matches[0].Groups[1].Value }
    return 0
}

$tmpl = Get-Content -Raw (Join-Path $root $Jmx)

# 先测一段空转，估算背景 Redis 流量
$g0 = Get-RedisCalls 'get'
Start-Sleep -Seconds 10
$g1 = Get-RedisCalls 'get'
Write-Host "BASELINE idle=10s redis-get-delta=$($g1 - $g0)"

foreach ($t in $Threads) {
    Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/ml/stats/reset' -Method POST -UseBasicParsing | Out-Null
    foreach ($id in 1..21) {
        Invoke-WebRequest -Uri "http://127.0.0.1:8081/api/goods/$id/ml/evict" -Method POST -UseBasicParsing | Out-Null
    }

    $jmx = Join-Path $outDir "r6b-ml-$Mode-$JmxTag-$t.jmx"
    $tmpl.Replace('@THREADS@', "$t").Replace('@RAMP@', '10').Replace('@DURATION@', "$Duration") |
        Set-Content -Path $jmx -Encoding UTF8

    $jtl = Join-Path $outDir "r6b-ml-$Mode-$JmxTag-$t.jtl"
    $log = Join-Path $outDir "r6b-ml-$Mode-$JmxTag-$t.log"
    if (Test-Path $jtl) { Remove-Item $jtl -Force }

    $getBefore = Get-RedisCalls 'get'
    & $jmeter -n -t $jmx -l $jtl -j $log | Out-Null
    $getAfter  = Get-RedisCalls 'get'
    $redisGet  = $getAfter - $getBefore

    $stats = (Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/ml/stats' -UseBasicParsing).Content
    Write-Host "RESULT mode=$Mode threads=$t redisGet=$redisGet $stats"
}

Write-Host 'ML SWEEP2 DONE'
