param(
    [string]$BaseUrl = 'http://127.0.0.1:7798',
    [string]$CreatedEventCode = $env:NTF02_CREATED_EVENT_CODE,
    [string]$ReviewedEventCode = $env:NTF02_REVIEWED_EVENT_CODE
)

$ErrorActionPreference = 'Stop'
if ($env:DB_NAME -ne 'xinghuiadmintest') { throw 'DB_NAME must be xinghuiadmintest.' }
if ([string]::IsNullOrWhiteSpace($env:NTF02_TEST_PASSWORD)) {
    throw 'Set NTF02_TEST_PASSWORD in the current process.'
}
if ([string]::IsNullOrWhiteSpace($CreatedEventCode) -or [string]::IsNullOrWhiteSpace($ReviewedEventCode)) {
    throw 'Set both NTF02_CREATED_EVENT_CODE and NTF02_REVIEWED_EVENT_CODE.'
}
$base = $BaseUrl.TrimEnd('/')
$password = $env:NTF02_TEST_PASSWORD

function Login([string]$phone) {
    $body = @{ phone = $phone; password = $password } | ConvertTo-Json
    $response = Invoke-RestMethod "$base/user/login" -Method Post -ContentType 'application/json' -Body $body
    if ($response.code -ne 200) { throw "Login failed with code $($response.code)." }
    return @{ Authorization = [string]$response.data }
}

function Get-Api([string]$path, $headers) {
    return Invoke-RestMethod "$base$path" -Headers $headers
}

function Put-Api([string]$path, $headers, $data = $null) {
    $parameters = @{ Uri = "$base$path"; Method = 'Put'; Headers = $headers }
    if ($null -ne $data) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $data | ConvertTo-Json -Depth 5
    }
    return Invoke-RestMethod @parameters
}

$null = Invoke-RestMethod "$base/captcha"
$admin = Login '13800000000'
try {
    $page = Get-Api '/notifications?pageNum=1&pageSize=100' $admin
    if ($page.code -ne 200) { throw "Inbox page failed with code $($page.code)." }
    $fixtures = @($page.data.items | Where-Object { $_.eventCode -in @($CreatedEventCode, $ReviewedEventCode) })
    if ($fixtures.Count -ne 2 -or @($fixtures | Where-Object { $null -ne $_.readTime }).Count -ne 0) {
        throw 'Expected two unread notifications from the isolated event test.'
    }
    $unread = Get-Api '/notifications/unread-count' $admin
    if ($unread.data -ne 2) { throw "Expected 2 unread messages; found $($unread.data)." }

    $created = $fixtures | Where-Object eventCode -eq $CreatedEventCode | Select-Object -First 1
    $reviewed = $fixtures | Where-Object eventCode -eq $ReviewedEventCode | Select-Object -First 1
    $single = Put-Api "/notifications/$($created.id)/read" $admin
    $batch = Put-Api '/notifications/read' $admin @{ ids = @([long]$reviewed.id) }
    $repeat = Put-Api "/notifications/$($created.id)/read" $admin
    if ($single.code -ne 200 -or $batch.code -ne 200 -or $repeat.code -ne 200) {
        throw 'Single, batch, or repeated read operation failed.'
    }
    $null = Get-Api '/user/logout' $admin

    $admin = Login '13800000000'
    $persistedCount = Get-Api '/notifications/unread-count' $admin
    $persistedPage = Get-Api '/notifications?pageNum=1&pageSize=100' $admin
    $persisted = @($persistedPage.data.items | Where-Object { $_.eventCode -in @($CreatedEventCode, $ReviewedEventCode) })
    if ($persistedCount.data -ne 0 -or @($persisted | Where-Object { $null -eq $_.readTime }).Count -ne 0) {
        throw 'Read state did not persist after a new login and page refresh.'
    }
    $detail = Get-Api "/notifications/$($created.id)" $admin
    $announcements = Get-Api '/system/notice/list?pageNum=1&pageSize=5' $admin
    if ($detail.code -ne 200 -or $detail.data.title -ne '记录 中性示例 已创建' -or $announcements.code -ne 200) {
        throw 'Notification detail or legacy announcement read failed.'
    }
    $null = Get-Api '/user/logout' $admin

    $other = Login '13800000001'
    $foreign = Invoke-WebRequest "$base/notifications/$($created.id)" -Headers $other -SkipHttpErrorCheck
    $foreignResult = $foreign.Content | ConvertFrom-Json
    if ($foreignResult.code -eq 200 -or $foreignResult.message -ne '通知不存在') {
        throw 'Another user could read this notification.'
    }
    $null = Get-Api '/user/logout' $other
    Write-Output "NTF-02 API verified: two event types, single/batch/repeated read, new-login persistence, cross-user denial (code $($foreignResult.code)), announcement list ($($announcements.code))."
} finally {
    if ($admin) { try { $null = Get-Api '/user/logout' $admin } catch {} }
}
