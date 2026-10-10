# ============================================================
# pen-sweep.ps1 —— r7 缓存穿透梯度压测
#   对给定模式（none=无防护 / null=缓存空值 / bloom=布隆），依次跑多档并发；
#   用 MySQL 自身的 Com_select 增量作为"真实查库次数"地面真相。
#
# 用法（需先以对应模式启动应用）：
#   powershell -File scripts\pen-sweep.ps1 -Mode none  -Threads 100,200,500,1000
# ============================================================
param(
    [string]$Mode = 'none',
    [int[]]$Threads = @(100, 200, 500, 1000),
    [int]$Duration = 30
)

$ErrorActionPreference = 'Stop'
$root   = 'C:\Users\lsy1\Desktop\seckill-lab'
$jmeter = 'D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat'
$mysql  = 'C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe'
$env:JAVA_HOME = 'C:\Users\lsy1\.jdks\ms-11.0.29'
$env:HEAP = '-Xms1g -Xmx2g'
$env:MYSQL_PWD = '123456'
$outDir = Join-Path $root 'bench\results'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Get-ComSelect {
    $out = & $mysql -uroot -N -B -e "SHOW GLOBAL STATUS LIKE 'Com_select'" 2>$null
    foreach ($line in $out) {
        $p = $line -split "`t"
        if ($p.Count -ge 2 -and $p[0] -eq 'Com_select') { return [long]$p[1] }
    }
    return 0
}

$tmpl = Get-Content -Raw (Join-Path $root 'bench\pen.jmx')

# 空转基线
$c0 = Get-ComSelect
Start-Sleep -Seconds 10
$c1 = Get-ComSelect
Write-Host "BASELINE idle=10s mysql-com-select-delta=$($c1 - $c0)"

foreach ($t in $Threads) {
    Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/pen/stats/reset' -Method POST -UseBasicParsing | Out-Null

    $jmx = Join-Path $outDir "r7-pen-$Mode-$t.jmx"
    $tmpl.Replace('@THREADS@', "$t").Replace('@RAMP@', '10').Replace('@DURATION@', "$Duration") |
        Set-Content -Path $jmx -Encoding UTF8

    $jtl = Join-Path $outDir "r7-pen-$Mode-$t.jtl"
    $log = Join-Path $outDir "r7-pen-$Mode-$t.log"
    if (Test-Path $jtl) { Remove-Item $jtl -Force }

    $selBefore = Get-ComSelect
    & $jmeter -n -t $jmx -l $jtl -j $log | Out-Null
    $selAfter  = Get-ComSelect
    $dbSelect  = $selAfter - $selBefore

    $stats = (Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods/pen/stats' -UseBasicParsing).Content
    Write-Host "RESULT mode=$Mode threads=$t mysqlComSelect=$dbSelect $stats"
}

Write-Host 'PEN SWEEP DONE'
