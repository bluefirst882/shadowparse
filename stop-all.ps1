$ErrorActionPreference = 'Continue'
$projectRoot = $PSScriptRoot
Push-Location $projectRoot
try { docker compose down } finally { Pop-Location }
$gymRoot = Join-Path (Split-Path $projectRoot -Parent) 'gym-manaegement-system'
if (Test-Path (Join-Path $gymRoot 'compose.yaml')) {
  Push-Location $gymRoot
  try { docker compose down } finally { Pop-Location }
}
$pidPath = Join-Path $projectRoot '.whisper-worker.pid'
if (Test-Path $pidPath) {
  $workerId = [int](Get-Content $pidPath -Raw).Trim()
  Stop-Process -Id $workerId -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $pidPath -Force
}
Write-Host 'Docker services and this project Whisper worker stopped; model cache and venv were retained.'
