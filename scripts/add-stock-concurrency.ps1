# ============================================================
# add-stock-concurrency.ps1 —— r9 加库存并发一致性测试
#   秒杀扣减进行中, 同时补货; 跑完等 Kafka 消费完, 校验 DB==Redis。
#   用法: powershell -File scripts\add-stock-concurrency.ps1
# ============================================================
param(
    [int]$Threads = 500,
    [int]$Duration = 20,
    [int]$InitialStock = 100000,
    [int]$AddQty = 100,
    [int]$AddTimes = 5,
    [string]$RunId = (Get-Date -Format 'HHmmss')
)

$ErrorActionPreference = 'Continue'
$root   = 'C:\Users\lsy1\Desktop\seckill-lab'
$jmeter = 'D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat'
$mysql  = 'C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe'
$env:JAVA_HOME = 'C:\Users\lsy1\.jdks\ms-11.0.29'
$env:HEAP = '-Xms1g -Xmx2g'
$env:MYSQL_PWD = '123456'
$outDir = Join-Path $root 'bench\results'
$base   = 'http://127.0.0.1:8081/api/seckill/1'

# 0. 先等上一轮 Kafka 消息消费完（避免上轮在途消息在本轮重置后落库，造成假不一致）
Start-Sleep -Seconds 8

# 1. 重置 DB 库存
$ordersBefore = (& $mysql -uroot seckill -N -B -e "SELECT COUNT(*) FROM orders WHERE order_type=1;" 2>$null)
& $mysql -uroot seckill -e "UPDATE seckill_stock SET stock=$InitialStock WHERE activity_id=1;" 2>$null | Out-Null
# 2. 预热（写 Redis、清已下单集合）
Invoke-WebRequest "$base/warmup" -Method POST -UseBasicParsing | Out-Null
$before = (Invoke-WebRequest "$base/stock" -UseBasicParsing).Content
Write-Host "BEFORE $before"

# 3. 后台并发补货
$addJob = Start-Job -ScriptBlock {
    param($base, $qty, $times, $runId)
    Start-Sleep -Seconds 4
    $res = @()
    for ($i = 1; $i -le $times; $i++) {
        $r = Invoke-WebRequest "$base/add-stock?qty=$qty&requestId=conc$RunId`_$i" -Method POST -UseBasicParsing
        $res += $r.Content
        Start-Sleep -Seconds 1
    }
    $res
} -ArgumentList $base, $AddQty, $AddTimes, $RunId

# 4. 同步跑秒杀压测
$tmpl = Get-Content -Raw (Join-Path $root 'bench\seckill-r9.jmx')
$jmx = Join-Path $outDir "r9-conc-$Threads.jmx"
$tmpl.Replace('@THREADS@', "$Threads").Replace('@RAMP@', '5').Replace('@DURATION@', "$Duration") |
    Set-Content -Path $jmx -Encoding UTF8
$jtl = Join-Path $outDir "r9-conc-$Threads.jtl"
$log = Join-Path $outDir "r9-conc-$Threads.log"
if (Test-Path $jtl) { Remove-Item $jtl -Force }
& $jmeter -n -t $jmx -l $jtl -j $log | Out-Null
$sum = Select-String -Path $log -Pattern 'summary =' | Select-Object -Last 1
Write-Host "JMETER $($sum.Line)"

# 5. 收补货结果
Wait-Job $addJob | Out-Null
Write-Host "ADDS:"
Receive-Job $addJob | ForEach-Object { Write-Host "  $_" }
Remove-Job $addJob

# 6. 等 Kafka 消费完落库
Start-Sleep -Seconds 12
$after = (Invoke-WebRequest "$base/stock" -UseBasicParsing).Content
Write-Host "AFTER $after"
$orderCount = (& $mysql -uroot seckill -N -B -e "SELECT COUNT(*) FROM orders WHERE order_type=1;" 2>$null)
Write-Host "SECKILL_ORDERS_TOTAL $orderCount (delta=$([int]$orderCount - [int]$ordersBefore))"
Write-Host 'CONCURRENCY DONE'
