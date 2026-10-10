# ============================================================
# pay-test.ps1 —— 并发支付同一订单，验证支付乐观锁
#   预期：无论并发多少次，只有 1 次成功
# ============================================================
param([int]$Times = 20)

$mysql = 'C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe'
$orderNo = (& $mysql -uroot -p123456 -N -B seckill -e 'SELECT order_no FROM orders WHERE order_type=1 ORDER BY id DESC LIMIT 1;' 2>$null |
    Select-Object -First 1)
$orderNo = "$orderNo".Trim()
Write-Host "[pay-test] orderNo=$orderNo, 并发 $Times 次支付"

$jobs = 1..$Times | ForEach-Object {
    Start-Job -ScriptBlock {
        param($o)
        (Invoke-WebRequest -Uri "http://127.0.0.1:8081/api/seckill/pay/$o" -Method POST -UseBasicParsing -TimeoutSec 15).Content
    } -ArgumentList $orderNo
}
$res = $jobs | Wait-Job | Receive-Job
$ok = ($res | Where-Object { $_ -match '"code":200' }).Count
Write-Host "[pay-test] 支付成功次数 = $ok / $Times （预期 1）"
Remove-Job $jobs -Force
