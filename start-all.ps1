$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$venv = if ($env:WHISPER_VENV) { $env:WHISPER_VENV } else { 'E:\model\toni-whisper-venv313' }
$python = Join-Path $venv 'Scripts\python.exe'
$requirements = Join-Path $projectRoot 'workers\requirements.txt'
$modelDir = if ($env:WHISPER_MODEL_DIR) { $env:WHISPER_MODEL_DIR } else { 'E:\model\whisper' }
$token = $env:WHISPER_SERVICE_TOKEN
if (-not $token) {
  $envPath = Join-Path $projectRoot '.env'
  if (Test-Path $envPath) {
    $tokenLine = Get-Content $envPath | Where-Object { $_ -match '^WHISPER_SERVICE_TOKEN=' } | Select-Object -Last 1
    if ($tokenLine) { $token = $tokenLine.Substring('WHISPER_SERVICE_TOKEN='.Length).Trim() }
  }
}
if (-not (Test-Path $python)) {
  New-Item -ItemType Directory -Force -Path (Split-Path $venv -Parent) | Out-Null
  $py311 = (py -0p | Select-String '3\.13' | Select-Object -First 1).ToString().Split()[-1]
  if (-not $py311 -or -not (Test-Path $py311)) { throw 'Python 3.13 was not found; install CPython 3.13.' }
  & $py311 -m venv $venv
  if ($LASTEXITCODE -ne 0) { throw 'Failed to create Python 3.13 virtual environment.' }
  & $python -m ensurepip --upgrade
  if ($LASTEXITCODE -ne 0) { throw 'Failed to bootstrap pip.' }
  $torchWheel = 'E:\Downloads\Compressed\torch-2.11.0+cu128-cp313-cp313-win_amd64.whl'
  if (Test-Path $torchWheel) {
    & $python -m pip install $torchWheel
    if ($LASTEXITCODE -ne 0) { throw 'Failed to install the local CUDA-enabled PyTorch wheel.' }
    & $python -m pip install --index-url https://pypi.tuna.tsinghua.edu.cn/simple numpy==2.1.3 numba==0.61.2 more-itertools==10.8.0 tiktoken==0.9.0 tqdm==4.67.1 regex==2024.11.6 requests==2.32.3 openai-whisper==20250625
  } else {
    & $python -m pip install -r $requirements
  }
  if ($LASTEXITCODE -ne 0) { throw 'Failed to install Whisper dependencies.' }
}
if (-not $token -or $token.Length -lt 32 -or $token.StartsWith('replace-with-')) { throw 'Set a random WHISPER_SERVICE_TOKEN (at least 32 characters) in .env.' }
$env:WHISPER_SERVICE_TOKEN = $token
$env:WHISPER_MODEL_DIR = $modelDir
$env:WHISPER_MODEL = if ($env:WHISPER_MODEL) { $env:WHISPER_MODEL } else { 'turbo' }
$env:PYTHONUNBUFFERED = '1'
& $python -c "import torch, whisper; print('Whisper device:', 'cuda' if torch.cuda.is_available() else 'cpu')"
if ($LASTEXITCODE -ne 0) { throw 'Whisper dependencies are unavailable in the configured venv.' }
$gpu = & $python -c "import torch; print(torch.cuda.is_available())"
if ($gpu -ne 'True') { Write-Warning 'CUDA is not available in this Python environment; Whisper will use CPU.' }
$composeConfig = docker compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration is invalid.' }
$logPath = Join-Path $projectRoot 'whisper-worker.log'
$errPath = Join-Path $projectRoot 'whisper-worker-error.log'
$worker = Start-Process -FilePath $python -ArgumentList @((Join-Path $projectRoot 'workers\whisper_worker.py'), '--serve') -WorkingDirectory $projectRoot -RedirectStandardOutput $logPath -RedirectStandardError $errPath -PassThru -WindowStyle Hidden
Set-Content -Path (Join-Path $projectRoot '.whisper-worker.pid') -Value $worker.Id
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
  if ($worker.HasExited) { Get-Content $errPath -ErrorAction SilentlyContinue; throw 'Whisper worker exited before becoming ready.' }
  try {
    $null = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/health' -TimeoutSec 2
    $ready = $true
    break
  } catch { Start-Sleep -Seconds 1 }
}
if (-not $ready) { Stop-Process -Id $worker.Id -Force -ErrorAction SilentlyContinue; throw 'Whisper worker health check timed out.' }
try {
  $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/health' -TimeoutSec 2
  Write-Host "Whisper worker ready; device=$($health.device)"
} catch {
  Stop-Process -Id $worker.Id -Force -ErrorAction SilentlyContinue
  throw 'Whisper worker did not return a valid health response.'
}
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
