param(
    [ValidateSet('create', 'verify', 'pause', 'verify-paused', 'resume', 'manual', 'manual-paused', 'cleanup')]
    [string]$Phase,
    [long]$JobId = 0,
    [string]$BaseUrl = 'http://127.0.0.1:7799'
)

$ErrorActionPreference = 'Stop'
if ($env:DB_NAME -ne 'xinghuiadmintest') {
    throw 'DB_NAME must be xinghuiadmintest for JOB-02 smoke.'
}
if ([string]::IsNullOrWhiteSpace($env:JOB02_LOGIN_PASSWORD)) {
    throw 'Set JOB02_LOGIN_PASSWORD in this process.'
}
$base = $BaseUrl.TrimEnd('/')

function Assert-Ok($label, $response) {
    if ($response.code -ne 200) {
        throw "$label failed: code=$($response.code), message=$($response.message)"
    }
    return $response.data
}

function Job-Api($path, $method = 'Get', $body = $null) {
    $params = @{ Uri = "$base$path"; Method = $method; Headers = $headers }
    if ($null -ne $body) {
        $params.ContentType = 'application/json'
        $params.Body = $body | ConvertTo-Json -Depth 8
    }
    return Assert-Ok $path (Invoke-RestMethod @params)
}

Assert-Ok 'captcha' (Invoke-RestMethod -Uri "$base/captcha") | Out-Null
$loginBody = @{ phone = '13800000000'; password = $env:JOB02_LOGIN_PASSWORD } | ConvertTo-Json
$token = Assert-Ok 'login' (Invoke-RestMethod -Uri "$base/user/login" -Method Post `
        -ContentType 'application/json' -Body $loginBody)
$headers = @{ Authorization = [string]$token }

try {
    if ($Phase -eq 'create') {
        $handlers = @(Job-Api '/monitor/job/handlers')
        if ($handlers -notcontains 'system.noop') { throw 'system.noop is not registered.' }
        $payload = @{
            jobName = "JOB02 smoke $([guid]::NewGuid().ToString('N').Substring(0, 8))"
            jobGroup = 'DEFAULT'
            handlerKey = 'system.noop'
            handlerParams = '{}'
            cronExpression = '*/2 * * * * ?'
            timeZone = 'Asia/Shanghai'
            misfirePolicy = '2'
            concurrent = '1'
            status = '0'
            remark = 'JOB-02 isolated smoke; delete after verification'
        }
        $created = Job-Api '/monitor/job' 'Post' $payload
        Write-Output "JOB_ID=$created"
    } else {
        if ($JobId -le 0) { throw 'JobId is required for this phase.' }
        if ($Phase -eq 'pause' -or $Phase -eq 'resume') {
            $status = if ($Phase -eq 'pause') { '1' } else { '0' }
            Job-Api "/monitor/job/$JobId/status" 'Put' @{ status = $status } | Out-Null
            Write-Output "status=$status"
        } elseif ($Phase -eq 'manual') {
            $fireId = Job-Api "/monitor/job/$JobId/run" 'Post'
            $completed = $false
            for ($i = 0; $i -lt 15; $i++) {
                $fires = @(Job-Api "/monitor/job/$JobId/fires")
                if (@($fires | Where-Object { $_.fireId -eq [string]$fireId -and $_.status -eq 'SUCCEEDED' }).Count -eq 1) {
                    $completed = $true
                    break
                }
                Start-Sleep -Milliseconds 300
            }
            if (-not $completed) { throw 'Manual fire did not succeed.' }
            Write-Output "MANUAL_FIRE_ID=$fireId"
        } elseif ($Phase -eq 'manual-paused') {
            $response = Invoke-RestMethod -Uri "$base/monitor/job/$JobId/run" -Method Post -Headers $headers
            if ($response.code -eq 200) { throw 'Paused task unexpectedly ran.' }
            Write-Output 'paused_manual_denied=true'
        } elseif ($Phase -eq 'verify-paused') {
            $before = @(Job-Api "/monitor/job/$JobId/fires").Count
            Start-Sleep -Seconds 5
            $after = @(Job-Api "/monitor/job/$JobId/fires").Count
            if ($before -ne $after) { throw "Paused task gained fires: before=$before after=$after" }
            Write-Output "paused_fire_count=$after"
        } elseif ($Phase -eq 'verify') {
            $fires = @(Job-Api "/monitor/job/$JobId/fires")
            $scheduled = @($fires | Where-Object { $_.triggerType -eq 'SCHEDULED' -and $_.status -eq 'SUCCEEDED' })
            if ($scheduled.Count -eq 0) { throw 'No successful scheduled fire yet.' }
            $unique = @($scheduled | Select-Object -ExpandProperty scheduledFireTime -Unique)
            if ($unique.Count -ne $scheduled.Count) { throw 'Duplicate scheduled instant detected.' }
            Write-Output "scheduled_success=$($scheduled.Count); total_fires=$($fires.Count)"
        } elseif ($Phase -eq 'cleanup') {
            Job-Api "/monitor/job/$JobId" 'Delete' | Out-Null
            Write-Output 'job_deleted=true'
        }
    }
} finally {
    Assert-Ok 'logout' (Invoke-RestMethod -Uri "$base/user/logout" -Method Post -Headers $headers) | Out-Null
}
