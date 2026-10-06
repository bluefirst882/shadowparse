$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
# Whisper 转写服务已容器化（compose 里的 whisper 服务，见 workers/Dockerfile），
# 因此这里不再需要在宿主机上创建 venv、拉起 Python worker 并等它健康检查通过。
Push-Location $projectRoot
try { docker compose up -d --build } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'toni-2 Compose startup failed.' }
$gymRoot = Join-Path (Split-Path $projectRoot -Parent) 'gym-manaegement-system'
if (Test-Path (Join-Path $gymRoot 'compose.yaml')) {
  Push-Location $gymRoot
  try { docker compose up -d --build } finally { Pop-Location }
  if ($LASTEXITCODE -ne 0) { throw 'gym Compose startup failed.' }
}
Write-Host 'toni-2: http://localhost:5174 (backend http://localhost:8081)'
Write-Host 'gym: http://localhost:5173 (backend http://localhost:8082)'
Write-Host 'Whisper 推理设备：curl http://127.0.0.1:8090/health'
