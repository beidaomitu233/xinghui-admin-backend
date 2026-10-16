param([string]$BaseUrl = 'http://127.0.0.1:7811')

$ErrorActionPreference = 'Stop'
if ($env:DB_NAME -ne 'xinghuiadmintest') { throw 'DB_NAME must be xinghuiadmintest.' }
if ([string]::IsNullOrWhiteSpace($env:JOB02_LOGIN_PASSWORD)) {
    throw 'Set JOB02_LOGIN_PASSWORD in this process.'
}
$base = $BaseUrl.TrimEnd('/')
$created = @()

function Response($path, $method = 'Get', $body = $null) {
    $args = @{ Uri = "$base$path"; Method = $method; Headers = $headers }
    if ($null -ne $body) {
        $args.ContentType = 'application/json'
        $args.Body = $body | ConvertTo-Json -Depth 8
    }
    return Invoke-RestMethod @args
}

function Ok($path, $method = 'Get', $body = $null) {
    $response = Response $path $method $body
    if ($response.code -ne 200) { throw "$path returned code=$($response.code)" }
    return $response.data
}

function Rejected($path, $method = 'Get') {
    $response = Response $path $method
    if ($response.code -eq 200) { throw "$path unexpectedly succeeded" }
}

$captcha = Invoke-RestMethod -Uri "$base/captcha"
if ($captcha.code -ne 200) { throw 'captcha failed' }
$loginBody = @{ phone = '13800000000'; password = $env:JOB02_LOGIN_PASSWORD } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$base/user/login" -Method Post -ContentType 'application/json' -Body $loginBody
if ($login.code -ne 200) { throw "login failed: $($login.code)" }
$headers = @{ Authorization = [string]$login.data }

try {
    $handlers = @(Ok '/monitor/job/handlers')
    if ($handlers -notcontains 'system.noop' -or $handlers -notcontains 'system.diagnostic-fail') {
        throw 'Required safe test handlers are missing.'
    }
    foreach ($handler in @('system.noop', 'system.diagnostic-fail')) {
        $payload = @{
            jobName = "JOB03 $handler $([guid]::NewGuid().ToString('N').Substring(0, 6))"
            jobGroup = 'DEFAULT'; handlerKey = $handler; handlerParams = '{}'
            cronExpression = '0 0 2 * * ?'; timeZone = 'Asia/Shanghai'
            misfirePolicy = '3'; concurrent = '1'; status = '0'; remark = 'JOB-03 isolated smoke'
        }
        $id = [string](Ok '/monitor/job' 'Post' $payload)
        $created += $id
        $fireId = [string](Ok "/monitor/job/$id/run" 'Post')
        $expected = if ($handler -eq 'system.noop') { 'SUCCEEDED' } else { 'FAILED' }
        $fire = $null
        for ($i = 0; $i -lt 30; $i++) {
            $page = Ok "/monitor/jobLog/list?jobId=$id&status=$expected&triggerType=MANUAL&pageNum=1&pageSize=10"
            $fire = @($page.records | Where-Object { $_.fireId -eq $fireId } | Select-Object -First 1)
            if ($fire.Count -eq 1) { break }
            Start-Sleep -Milliseconds 300
        }
        if ($fire.Count -ne 1) { throw "Expected $expected fire for job $id" }
        $detail = Ok "/monitor/jobLog/$fireId"
        if ($detail.status -ne $expected -or $detail.jobId -ne $id -or !$detail.traceId -or
                $detail.jobName -ne $payload.jobName) { throw "Bad detail for $fireId" }
        $raw = $detail | ConvertTo-Json -Depth 8
        if ($raw -match 'invokeTarget|exceptionInfo|claimToken|fireKey|handlerParams') {
            throw 'Unsafe field returned by detail API.'
        }
        Rejected "/monitor/jobLog/$fireId" 'Delete'
        Write-Output "job=$id fire=$fireId status=$expected trace=$($detail.traceId) recent_delete_denied=true"
    }
    Rejected '/monitor/jobLog/list?pageSize=101'
    Rejected '/monitor/jobLog/list?status=INVALID'
    Rejected '/monitor/jobLog/999999999999999999'
    Write-Output 'invalid_page_filter_and_missing_detail_rejected=true'
} finally {
    foreach ($id in $created) {
        try { Ok "/monitor/job/$id" 'Delete' | Out-Null } catch { Write-Warning "Could not remove test job $id" }
    }
    try { Ok '/user/logout' 'Post' | Out-Null } catch { Write-Warning 'Logout failed' }
}
