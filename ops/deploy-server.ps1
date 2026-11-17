# Builds the server fat jar, copies it to the HP and restarts the service.
# Stops at the first failing step. Run from the project root:
#   powershell -ExecutionPolicy Bypass -File ops\deploy-server.ps1
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

& .\gradlew :server:buildFatJar
if ($LASTEXITCODE -ne 0) { Write-Error "gradle build failed (exit $LASTEXITCODE)"; exit 1 }

$jar = "server\build\libs\server-all.jar"
if (-not (Test-Path $jar)) { Write-Error "missing $jar"; exit 1 }

& scp $jar "hp:/tmp/server.jar"
if ($LASTEXITCODE -ne 0) { Write-Error "scp failed (exit $LASTEXITCODE)"; exit 1 }

# sudo -n never asks for a password: it only runs the commands allowed in /etc/sudoers.d/assistant-deploy (D53)
& ssh hp "sudo -n mv /tmp/server.jar /opt/assistant/server.jar && sudo -n systemctl restart assistant"
if ($LASTEXITCODE -ne 0) { Write-Error "ssh deploy step failed (exit $LASTEXITCODE)"; exit 1 }

Write-Host "Deployed."
