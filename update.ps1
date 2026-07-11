# fund-tracker local updater. The implementation is update.py.
$ErrorActionPreference = 'Stop'
$env:Path = [System.Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [System.Environment]::GetEnvironmentVariable('Path','User')
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root
try {
  python update.py
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally { Pop-Location }
