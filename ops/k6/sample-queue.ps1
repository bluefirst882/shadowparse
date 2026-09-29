# 压测期间每 5 秒采样一次：broker 真实积压 + 该批次任务的状态分布。
# 在仓库根目录运行（需要 docker compose 与后端在跑），输出 CSV 供 README 的容量表引用。
# 用法：pwsh -File ops/k6/sample-queue.ps1 -Samples 42
param(
  [int]$Samples = 42,
  [int]$IntervalSeconds = 5,
  [string]$FileNamePattern = 'sample-4s%',
  [string]$Backend = 'http://127.0.0.1:8081',
  [string]$Out = 'ops/k6/results/queue.csv'
)
$header = 'time,depth,queued,processing,completed,failed'
Set-Content -Path $Out -Value $header -Encoding utf8

for ($i = 0; $i -lt $Samples; $i++) {
  $stamp = (Get-Date).ToString('HH:mm:ss')
  $response = Invoke-WebRequest -UseBasicParsing -Uri "$Backend/actuator/prometheus"
  # PowerShell 7 对带 charset 的响应可能返回 byte[]，两种形态都兜住，否则正则匹配不到。
  $body = if ($response.Content -is [byte[]]) {
    [System.Text.Encoding]::UTF8.GetString($response.Content)
  } else {
    $response.Content
  }
  $depthMatch = [regex]::Match($body, '(?m)^workbench_queue_depth\{[^}]*\} ([0-9.]+)')
  # 读不到 broker 时后端给的是 -1，如实记录。
  $depth = if ($depthMatch.Success) { $depthMatch.Groups[1].Value -replace '\.0$', '' } else { '' }
  $sql = "select status, count(*) from tasks where file_name like '$FileNamePattern' group by status"
  $rows = docker compose exec -T mysql mysql -uroot -padmin -N -B video_workbench -e $sql 2>$null
  $counts = @{ QUEUED = 0; PROCESSING = 0; COMPLETED = 0; FAILED = 0 }
  foreach ($row in $rows) {
    $parts = $row -split "`t"
    if ($parts.Count -eq 2 -and $counts.ContainsKey($parts[0])) { $counts[$parts[0]] = [int]$parts[1] }
  }
  Add-Content -Path $Out -Encoding utf8 -Value (
    "$stamp,$depth,$($counts.QUEUED),$($counts.PROCESSING),$($counts.COMPLETED),$($counts.FAILED)")
  Start-Sleep -Seconds $IntervalSeconds
}
Get-Content $Out | Select-Object -Last 1
