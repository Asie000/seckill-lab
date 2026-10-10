# ============================================================
# bench.ps1 —— 压测运行器（把「跑 JMeter + 导出 .jtl」固化成一条命令）
#
# 用法：
#   powershell -File scripts\bench.ps1 -Jmx bench\order-list.jmx -Threads 200 -Duration 60 -Tag r0-order-list
#
# 说明：
#   - JMX 里用 @THREADS@ / @RAMP@ / @DURATION@ 占位符，
#     本脚本替换后生成临时 jmx 再跑（比 ${__P()} 在 ThreadGroup 里更可靠）。
#   - 用 JDK 11 跑 JMeter（ms-11.0.29），避开高版本 JDK 兼容问题；
#     应用本身用 JDK 25 跑，两者互不影响（JMeter 只是 HTTP 客户端）。
#   - 结果写入 bench\results\<Tag>-<Threads>.jtl / .log
# ============================================================
param(
    [Parameter(Mandatory = $true)][string]$Jmx,
    [int]$Threads = 100,
    [int]$Duration = 60,
    [int]$Ramp = 10,
    [Parameter(Mandatory = $true)][string]$Tag
)

$ErrorActionPreference = 'Stop'

$root   = 'C:\Users\lsy1\Desktop\seckill-lab'
$jmeter = 'D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat'
$env:JAVA_HOME = 'C:\Users\lsy1\.jdks\ms-11.0.29'
# jmeter.bat 仅在 HEAP 未定义时用默认值，所以在此覆盖堆大小（1000 并发必需）
$env:HEAP = '-Xms1g -Xmx2g'

$outDir = Join-Path $root 'bench\results'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 占位符替换 -> 生成临时 jmx
$content = Get-Content -Raw $Jmx
$content = $content.Replace('@THREADS@', "$Threads")
$content = $content.Replace('@RAMP@', "$Ramp")
$content = $content.Replace('@DURATION@', "$Duration")
$tmpJmx = Join-Path $outDir "$Tag-$Threads.jmx"
Set-Content -Path $tmpJmx -Value $content -Encoding UTF8

$jtl = Join-Path $outDir "$Tag-$Threads.jtl"
$log = Join-Path $outDir "$Tag-$Threads.log"
if (Test-Path $jtl) { Remove-Item $jtl -Force }

Write-Host "[bench] jmx=$Jmx threads=$Threads duration=${Duration}s ramp=${Ramp}s"
Write-Host "[bench] tmpJmx=$tmpJmx"
Write-Host "[bench] jtl=$jtl"

& $jmeter -n -t $tmpJmx -l $jtl -j $log

Write-Host "[bench] done."
