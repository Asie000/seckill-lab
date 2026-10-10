# 实验环境搭建与冒烟验证

> 本文记录从零把实验跑起来的过程：启动中间件 → 建库造数 → 编译 → 起应用 → JMeter 冒烟。
> 所有命令均为**实际执行过的**，输出与报错原样保留（含 Windows/WSL 的编码乱码说明）。

## 0. 背景：命令在哪跑？

本项目在 Windows 上开发，但 AI 助手（我）的命令行跑在 **WSL** 里。
两者通过 `powershell.exe` 桥接——即从 WSL 调用 Windows 的 PowerShell 执行 Windows 命令。

> WSL 每次输出顶部那行乱码
> `wsl: 检测到 localhost 代理配置...NAT 模式...`
> 是 WSL 的**无害提示**，与实验无关，忽略即可。

## 1. 启动三个中间件

### 1.1 先看启动脚本做了什么

```bash
cat /mnt/c/Users/lsy1/mysql-8.0.29/start-mysql.bat
cat /mnt/c/Users/lsy1/redis/start-redis.bat
cat /mnt/c/Users/lsy1/kafka/start-kafka.bat
```

关键发现：
- MySQL 脚本内部用 `start /MIN mysqld.exe`，**自己就后台化了**。
- Redis 脚本 `redis-server.exe redis.windows.conf` 是**前台阻塞**进程。
- Kafka 脚本 `kafka-server-start.bat ... >> kafka-start.log` 也是**前台阻塞**。

所以 Redis / Kafka 需要用 `Start-Process` 挂到后台，否则会卡住命令行。

### 1.2 实际启动命令

```powershell
Start-Process -FilePath 'C:\Users\lsy1\mysql-8.0.29\start-mysql.bat' -WorkingDirectory 'C:\Users\lsy1\mysql-8.0.29' -WindowStyle Minimized
Start-Process -FilePath 'C:\Users\lsy1\redis\start-redis.bat'        -WorkingDirectory 'C:\Users\lsy1\redis'        -WindowStyle Minimized
Start-Process -FilePath 'C:\Users\lsy1\kafka\start-kafka.bat'        -WorkingDirectory 'C:\Users\lsy1\kafka'        -WindowStyle Minimized
```

> Kafka 首次使用（或删过 `kafka-data`）要先跑 `init-kafka.bat` 格式化 KRaft 存储。
> 本次检查 `C:\Users\lsy1\kafka-data` 已有 58 个文件 → **已初始化，无需再 init**。

### 1.3 验证端口

```powershell
3306,6379,9092 | ForEach-Object {
  $p=$_; $ok=(New-Object Net.Sockets.TcpClient).ConnectAsync('127.0.0.1',$p).Wait(1500)
  "port $p = $ok"
}
```

结果：

```
port 3306 = True
port 6379 = True
port 9092 = True
```

进程核对（`Get-Process mysqld,redis-server,java`）：`mysqld`×2、`redis-server`×1、`java`×1（Kafka）。

## 2. 建库 + 造数

```powershell
$mysql='C:\Users\lsy1\mysql-8.0.29\bin\mysql.exe'
& $mysql -uroot -p123456 -e 'source C:/Users/lsy1/Desktop/seckill-lab/src/main/resources/db/schema.sql'
& $mysql -uroot -p123456 -e 'source C:/Users/lsy1/Desktop/seckill-lab/src/main/resources/db/seed-orders.sql'
```

建表后核对：

```
Tables_in_seckill
goods
order_item          <- 本次 r0 新增
order_logistics     <- 本次 r0 新增
orders              <- 本次 r0 新增（统一订单表，替代原 seckill_order）
seckill_activity
seckill_stock
stock_log
```

造数结果：

```
tbl              cnt
user             5000
goods            21
orders           2000
order_item       2000
order_logistics  2000
```

> 注意：命令末尾 `Command exited with code 1` 是 **MySQL 的密码明文警告走了 stderr**，不是错误。
> 判断成败要看结果表是否存在、行数是否正确。

## 3. 编译

```powershell
cd C:\Users\lsy1\Desktop\seckill-lab
set JAVA_HOME=C:\Users\lsy1\.jdks\openjdk-25.0.1
mvnw.cmd -DskipTests package
```

结果：

```
[INFO] BUILD SUCCESS
[INFO] Total time:  7.663 s
```

产物：`target/seckill-lab-0.0.1-SNAPSHOT.jar`

> **坑**：应用在跑的时候 jar 被占用，`package` 会失败。
> 必须先 `Stop-Process -Id <pid> -Force` 停掉旧应用再编译。

## 4. 启动应用（后台）

```powershell
$p = Start-Process -FilePath 'C:\Users\lsy1\.jdks\openjdk-25.0.1\bin\java.exe' `
  -ArgumentList '-jar','C:\Users\lsy1\Desktop\seckill-lab\target\seckill-lab-0.0.1-SNAPSHOT.jar' `
  -WorkingDirectory 'C:\Users\lsy1\Desktop\seckill-lab' `
  -RedirectStandardOutput 'C:\Users\lsy1\Desktop\seckill-lab\target\app.log' `
  -RedirectStandardError  'C:\Users\lsy1\Desktop\seckill-lab\target\app.err.log' `
  -WindowStyle Hidden -PassThru
'app pid = ' + $p.Id
```

启动日志（`target/app.log`）：

```
Tomcat initialized with port 8081 (http)
Tomcat started on port 8081 (http) with context path '/'
Started SeckillLabApplication in 3.289 seconds
```

## 5. 接口冒烟

```powershell
Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/goods?pageNum=1&pageSize=5' -UseBasicParsing
Invoke-WebRequest -Uri 'http://127.0.0.1:8081/api/health' -UseBasicParsing
```

`/api/health` 返回：

```json
{"code":200,"message":"操作成功","data":{"status":"UP","mysql":"UP","redis":"UP","kafka":"UP"}}
```

> 中文在 PowerShell 输出里显示成乱码（`æä½æå`），是终端编码问题，**接口本身是正确的 UTF-8**。

## 6. JMeter 冒烟

最小计划 `bench/goods-smoke.jmx`（10 线程 × 20 循环打 `/api/goods`）：

```powershell
$env:JAVA_HOME='C:\Users\lsy1\.jdks\ms-11.0.29'
& 'D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat' -n -t bench\goods-smoke.jmx -l bench\results\smoke.jtl
```

结果：

```
summary =    200 in 00:00:01 =  182.6/s Avg: 12 Min: 6 Max: 43 Err: 0 (0.00%)
```

`.jtl` 解析（Python）：

```
samples=200 errors=0 (0.00%) avg=12.4ms p95=17ms p99=19ms max=43ms
```

**结论：命令行 → JMeter → 应用 → MySQL 全链路打通。**

---

## 7. 踩坑记录（重要）

### 坑 1：`${__P(threads,100)}` 在 ThreadGroup 里不生效

首版 `order-list.jmx` 用 JMeter 属性函数传线程数：

```xml
<stringProp name="ThreadGroup.num_threads">${__P(threads,100)}</stringProp>
```

运行结果：

```
summary =      0 in 00:00:00 = ******/s ... Err: 0
```

JMeter 日志显示：

```
Starting 0 threads for group order-list-tg.
ThreadGroup: Starting thread group... number=1 threads=0 ramp-up=0
```

**线程数被解析成 0**，测试 84ms 就结束了。

**解决**：JMX 里改用占位符 `@THREADS@` / `@RAMP@` / `@DURATION@`，
由 `scripts/bench.ps1` 在读入后字符串替换、生成临时 jmx 再跑。确定性更强。

### 坑 2：写文件时相对路径拼错，文件落到仓库根目录

某次把 `Order.java` 的路径写成含 `../../../` 的相对路径，
结果文件被写到了 `seckill-lab/com/lsy/.../Order.java`（仓库根多出一个 `com/` 目录）。

**解决**：`mv` 回正确位置并 `rm -rf com`。以后一律用完整绝对路径写文件。

### 坑 3：PowerShell 长命令在 WSL 里偶发超时

`Start-Process` 后紧跟 `TcpClient` 探测时，工具调用有时会等到超时（60s），
但**应用其实已经起来了**。

**解决**：启动与探测拆成两次独立调用；探测用 `ConnectAsync(...).Wait(2000)` 自身带超时。
