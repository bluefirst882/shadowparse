$ErrorActionPreference = 'Continue'
$projectRoot = $PSScriptRoot
Push-Location $projectRoot
try { docker compose down } finally { Pop-Location }
$gymRoot = Join-Path (Split-Path $projectRoot -Parent) 'gym-manaegement-system'
if (Test-Path (Join-Path $gymRoot 'compose.yaml')) {
  Push-Location $gymRoot
  try { docker compose down } finally { Pop-Location }
}
Write-Host 'Docker services stopped; model cache and data volumes were retained.'
