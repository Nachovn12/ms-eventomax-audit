param([string]$Image = 'eventomax-audit:emx69-review')
$ErrorActionPreference = 'Stop'
$taskSuffix = [guid]::NewGuid().ToString('N').Substring(0,12)
$taskNetwork = "audit-smoke-$taskSuffix"
$taskDb = "audit-smoke-db-$taskSuffix"
$taskApp = "audit-smoke-app-$taskSuffix"
$taskPassword = [guid]::NewGuid().ToString('N')
function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($args[0])" }
}
try {
    Invoke-Docker network create --label eventomax.task=EMX-69-smoke $taskNetwork | Out-Null
    Invoke-Docker run -d --name $taskDb --network $taskNetwork --network-alias audit-db --label eventomax.task=EMX-69-smoke -e POSTGRES_DB=audit -e POSTGRES_USER=audit -e "POSTGRES_PASSWORD=$taskPassword" postgres:17-alpine | Out-Null
    $taskDbReady = $false
    for ($i=0; $i -lt 30; $i++) {
        & docker exec $taskDb pg_isready -U audit -d audit *> $null
        if ($LASTEXITCODE -eq 0) { $taskDbReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $taskDbReady) { throw 'Test PostgreSQL did not become ready' }
    Invoke-Docker run -d --name $taskApp --network $taskNetwork --label eventomax.task=EMX-69-smoke -e DB_URL=jdbc:postgresql://audit-db:5432/audit -e DB_USER=audit -e "DB_PASSWORD=$taskPassword" -e ENTRA_ISSUER_URI=https://issuer.invalid/test -e ENTRA_AUDIENCE=audit-test -e AUDIT_KAFKA_ENABLED=false $Image | Out-Null
    $taskHealthy = $false
    for ($i=0; $i -lt 60; $i++) {
        $taskHealth = Invoke-Docker inspect --format '{{.State.Health.Status}}' $taskApp
        if ($taskHealth -eq 'healthy') { $taskHealthy = $true; break }
        if ((Invoke-Docker inspect --format '{{.State.Status}}' $taskApp) -eq 'exited') { throw 'Audit container exited' }
        Start-Sleep -Seconds 1
    }
    if (-not $taskHealthy) { throw 'Audit Docker healthcheck did not pass' }
    $taskApiStatus = Invoke-Docker exec $taskApp curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/audit/timeline
    if ($taskApiStatus -ne '401') { throw "Expected 401 without JWT, got $taskApiStatus" }
    $taskUser = Invoke-Docker exec $taskApp id -u
    if ($taskUser -eq '0') { throw 'Runtime must not run as root' }
    $taskMigrationCount = Invoke-Docker exec $taskDb psql -U audit -d audit -tAc "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2')"
    if ($taskMigrationCount.Trim() -ne '2') { throw 'Expected both Flyway migrations' }
    Write-Output 'PASS: Docker healthy; timeline without JWT=401; non-root runtime; Flyway V1/V2 applied.'
} finally {
    # Names belong solely to this invocation. Remove only these ephemeral resources.
    & docker rm -f -v $taskApp $taskDb 2>$null | Out-Null
    & docker network rm $taskNetwork 2>$null | Out-Null
}
