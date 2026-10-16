param(
    [string]$BaseUrl = 'http://127.0.0.1:7799',
    [string]$AdminPhone = $(if ($env:BULK_ADMIN_PHONE) { $env:BULK_ADMIN_PHONE } else { '13800000000' })
)

$ErrorActionPreference = 'Stop'
if ($env:DB_NAME -ne 'xinghuiadmintest') {
    throw 'DB_NAME must be xinghuiadmintest before running this write-and-cleanup smoke test.'
}
$databaseTargetIsExpected = ($env:DB_HOST -eq '127.0.0.1') -and
    ($env:DB_PORT -eq '13307') -and
    ($env:DB_USERNAME -eq 'xinghuiadmintest')
if (-not $databaseTargetIsExpected) {
    throw 'DB_HOST, DB_PORT, and DB_USERNAME must match the isolated xinghuiadmintest MySQL 8.4 container at 127.0.0.1:13307.'
}
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw 'Set DB_PASSWORD in the current process for the isolated test service.'
}
if ([string]::IsNullOrWhiteSpace($env:BULK_ADMIN_PASSWORD)) {
    throw 'Set BULK_ADMIN_PASSWORD in the current process; the script never reads or prints a password from disk.'
}
$baseUri = [Uri]$BaseUrl
if ($baseUri.Scheme -ne 'http' -or $baseUri.Host -ne '127.0.0.1' -or $baseUri.Port -ne 7799) {
    throw 'The smoke test only accepts the local xinghuiadmintest service on port 7799.'
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$client = [System.Net.Http.HttpClient]::new()
$base = $BaseUrl.TrimEnd('/')
$nonce = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$username = "bulk_$nonce"
$nicknameFormula = '=SUM(1+1)'
$testDirectory = Join-Path $env:TEMP "bulk-01-$nonce"
$adminHeaders = $null
$userHeaders = $null
$userId = $null
$tenantId = $null
$cleanupFailure = $null

function Add-ApiHeaders($request, $headers) {
    if ($null -ne $headers -and -not [string]::IsNullOrWhiteSpace($headers.Authorization)) {
        [void]$request.Headers.TryAddWithoutValidation('Authorization', [string]$headers.Authorization)
    }
}

function Send-ApiRequest([string]$method, [string]$uri, $headers, $payload = $null) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method), $uri)
    Add-ApiHeaders $request $headers
    if ($null -ne $payload) {
        $json = ConvertTo-Json -InputObject $payload -Depth 6 -Compress
        $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
    }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $jsonBody = $null
        if ($response.Content.Headers.ContentType.MediaType -eq 'application/json') {
            try { $jsonBody = ConvertFrom-Json -InputObject $body } catch { $jsonBody = $null }
        }
        return [PSCustomObject]@{
            StatusCode = [int]$response.StatusCode
            ContentType = [string]$response.Content.Headers.ContentType.MediaType
            Body = $body
            Json = $jsonBody
        }
    } finally {
        $request.Dispose()
    }
}

function Send-Workbook([string]$uri, $headers, [string]$path) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    Add-ApiHeaders $request $headers
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    $stream = [IO.File]::OpenRead($path)
    $fileContent = [System.Net.Http.StreamContent]::new($stream)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse(
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
    $form.Add($fileContent, 'file', [IO.Path]::GetFileName($path))
    $request.Content = $form
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "Workbook upload failed with HTTP $([int]$response.StatusCode)." }
        try { return ConvertFrom-Json -InputObject $body } catch { throw 'Workbook upload returned an invalid response.' }
    } finally {
        $request.Dispose()
    }
}

function Save-ApiFile([string]$uri, $headers, [string]$path) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $uri)
    Add-ApiHeaders $request $headers
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "File request failed with HTTP $([int]$response.StatusCode)." }
        [IO.File]::WriteAllBytes($path, $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
        $bytes = [IO.File]::ReadAllBytes($path)
        if ($bytes.Length -lt 4 -or $bytes[0] -ne 0x50 -or $bytes[1] -ne 0x4b) {
            throw 'The response was not an XLSX file.'
        }
    } finally {
        $request.Dispose()
    }
}

function Login([string]$phone, [string]$password) {
    $response = Send-ApiRequest 'POST' "$base/user/login" $null @{ phone = $phone; password = $password }
    if ($response.StatusCode -ne 200 -or $null -eq $response.Json -or $response.Json.code -ne 200) {
        throw 'Login verification failed.'
    }
    return @{ Authorization = [string]$response.Json.data }
}

function New-Mobile() {
    return '139' + [string](Get-Random -Minimum 10000000 -Maximum 99999999)
}

function Escape-Xml([string]$value) {
    return [Security.SecurityElement]::Escape($value)
}

function Get-ColumnName([int]$number) {
    $name = ''
    while ($number -gt 0) {
        $number--
        $name = [char](65 + ($number % 26)) + $name
        $number = [Math]::Floor($number / 26)
    }
    return $name
}

function Write-Xlsx([string]$path, [string[]]$headers, [object[][]]$dataRows) {
    $allRows = [Collections.Generic.List[object[]]]::new()
    $allRows.Add([object[]]$headers)
    foreach ($dataRow in $dataRows) { $allRows.Add([object[]]$dataRow) }

    $sheetRows = [Text.StringBuilder]::new()
    for ($rowIndex = 0; $rowIndex -lt $allRows.Count; $rowIndex++) {
        [void]$sheetRows.Append('<row r="')
        [void]$sheetRows.Append($rowIndex + 1)
        [void]$sheetRows.Append('">')
        for ($columnIndex = 0; $columnIndex -lt $headers.Length; $columnIndex++) {
            $reference = (Get-ColumnName ($columnIndex + 1)) + ($rowIndex + 1)
            $value = if ($columnIndex -lt $allRows[$rowIndex].Length) { [string]$allRows[$rowIndex][$columnIndex] } else { '' }
            [void]$sheetRows.Append('<c r="')
            [void]$sheetRows.Append($reference)
            [void]$sheetRows.Append('" t="inlineStr"><is><t xml:space="preserve">')
            [void]$sheetRows.Append((Escape-Xml $value))
            [void]$sheetRows.Append('</t></is></c>')
        }
        [void]$sheetRows.Append('</row>')
    }
    $entries = [ordered]@{
        '[Content_Types].xml' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>'
        '_rels/.rels' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'
        'xl/workbook.xml' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="用户导入" sheetId="1" r:id="rId1"/></sheets></workbook>'
        'xl/_rels/workbook.xml.rels' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'
        'xl/worksheets/sheet1.xml' = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' + $sheetRows.ToString() + '</sheetData></worksheet>'
    }

    $fileStream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite)
    $archive = [System.IO.Compression.ZipArchive]::new(
        $fileStream, [System.IO.Compression.ZipArchiveMode]::Create, $false)
    try {
        foreach ($entryName in $entries.Keys) {
            $entry = $archive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            try {
                $bytes = [Text.UTF8Encoding]::new($false).GetBytes([string]$entries[$entryName])
                $entryStream.Write($bytes, 0, $bytes.Length)
            } finally { $entryStream.Dispose() }
        }
    } finally {
        $archive.Dispose()
        $fileStream.Dispose()
    }
}

function Read-XlsxXml([string]$path) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($path)
    try {
        $parts = [Collections.Generic.List[string]]::new()
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.StartsWith('xl/', [StringComparison]::Ordinal)) {
                $reader = [IO.StreamReader]::new($entry.Open())
                try { $parts.Add($reader.ReadToEnd()) } finally { $reader.Dispose() }
            }
        }
        return [string]::Join("`n", $parts)
    } finally { $archive.Dispose() }
}

try {
    New-Item -ItemType Directory -Path $testDirectory | Out-Null
    $null = Send-ApiRequest 'GET' "$base/captcha" $null
    $adminHeaders = Login $AdminPhone $env:BULK_ADMIN_PASSWORD

    $headers = @('用户账号', '用户昵称', '邮箱', '手机号码', '角色标识')
    $validPhone = New-Mobile
    $rows = @(
        ,@($username, $nicknameFormula, "bulk.$nonce@example.invalid", $validPhone, '')
        ,@("${username}_badmobile", '手机号错误', '', '123', '')
        ,@($username, '重复账号', '', (New-Mobile), '')
        ,@("${username}_badrole", '未知角色', '', (New-Mobile), "bulk_missing_$nonce")
        ,@('', '缺少账号', '', (New-Mobile), '')
    )
    $workbookPath = Join-Path $testDirectory 'mixed-rows.xlsx'
    Write-Xlsx $workbookPath $headers $rows

    $templatePath = Join-Path $testDirectory 'template.xlsx'
    Save-ApiFile "$base/system/user/importTemplate" $adminHeaders $templatePath
    $templateXml = Read-XlsxXml $templatePath
    foreach ($header in $headers) {
        if (-not $templateXml.Contains($header)) { throw "The import template is missing header: $header" }
    }
    if ($templateXml.Contains('用户状态')) { throw 'The import template still exposes an enabled/disabled state.' }
    $import = Send-Workbook "$base/system/user/import" $adminHeaders $workbookPath
    $importIsExpected = ($import.code -eq 200) -and
        ($import.data.totalRows -eq 5) -and
        ($import.data.successCount -eq 1) -and
        ($import.data.failureCount -eq 4) -and
        ($import.data.skippedCount -eq 0)
    if (-not $importIsExpected) {
        throw 'Import did not return the expected one-success/four-failure partial result.'
    }
    $reportText = ConvertTo-Json -InputObject $import.data -Depth 8 -Compress
    if ($reportText.Contains($username) -or $reportText.Contains($nicknameFormula)) {
        throw 'The import response included source row contents.'
    }
    $successfulRow = @($import.data.rows | Where-Object { $_.status -eq 'success' }) | Select-Object -First 1
    if ($null -eq $successfulRow -or -not ([string]$successfulRow.reason).Contains('待激活')) {
        throw 'The successful row did not explain that the account needs activation.'
    }

    $encodedUsername = [Uri]::EscapeDataString($username)
    $list = Send-ApiRequest 'GET' "$base/system/user/list?username=$encodedUsername&pageNum=1&pageSize=10" $adminHeaders
    if ($list.StatusCode -ne 200 -or $list.Json.code -ne 200) { throw 'Could not reload the imported user.' }
    $matches = @($list.Json.data.records | Where-Object { $_.username -eq $username })
    if ($matches.Count -ne 1) { throw 'The imported account was not visible in the current user list.' }
    $userId = [string]$matches[0].userId
    $tenantId = [string]$matches[0].tenantId
    if ([string]::IsNullOrWhiteSpace($userId) -or [string]::IsNullOrWhiteSpace($tenantId)) {
        throw 'The imported account is missing its database ID or tenant assignment.'
    }
    if ([int]$matches[0].status -ne 0) {
        throw 'The imported account was not created disabled for pending activation.'
    }

    $exportPath = Join-Path $testDirectory 'filtered-export.xlsx'
    Save-ApiFile "$base/system/user/export?username=$encodedUsername" $adminHeaders $exportPath
    $exportXml = Read-XlsxXml $exportPath
    if (-not $exportXml.Contains("'=$($nicknameFormula.Substring(1))")) {
        throw 'The exported formula-like nickname was not escaped with a leading apostrophe.'
    }
    if (-not $exportXml.Contains('停用')) { throw 'The pending account was not exported as disabled.' }
    if ($exportXml -match '<f(?:\s|>)') { throw 'The export contains a spreadsheet formula element.' }

    $emptyExportPath = Join-Path $testDirectory 'empty-export.xlsx'
    Save-ApiFile "$base/system/user/export?username=${encodedUsername}_absent" $adminHeaders $emptyExportPath
    $emptyXml = Read-XlsxXml $emptyExportPath
    if (-not $emptyXml.Contains('用户账号')) { throw 'The empty export did not retain its column headers.' }

    $firstReset = Send-ApiRequest 'POST' "$base/system/user/resetPwd?userId=$userId" $adminHeaders
    $firstTemporaryPassword = [string]$firstReset.Json.data
    $firstResetIsExpected = ($firstReset.StatusCode -eq 200) -and
        ($firstReset.Json.code -eq 200) -and
        ($firstTemporaryPassword.Length -eq 20)
    if (-not $firstResetIsExpected) {
        throw 'Reset did not return a generated temporary password.'
    }
    $disabledLoginRejected = $false
    try { $null = Login $validPhone $firstTemporaryPassword } catch { $disabledLoginRejected = $true }
    if (-not $disabledLoginRejected) { throw 'A pending disabled account could log in before activation.' }

    $details = Send-ApiRequest 'GET' "$base/system/user/$userId" $adminHeaders
    if ($details.StatusCode -ne 200 -or $details.Json.code -ne 200) {
        throw 'Could not load the account for activation.'
    }
    $user = $details.Json.data.user
    $editPayload = @{
        userId = [long]$userId
        username = [string]$user.username
        nickname = [string]$user.nickname
        mobile = [string]$user.mobile
        avatar = [string]$user.avatar
        status = 1
        roleIds = @($details.Json.data.roleIds)
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$user.email)) {
        $editPayload.email = [string]$user.email
    }
    $activated = Send-ApiRequest 'POST' "$base/system/user/edit" $adminHeaders $editPayload
    if ($activated.StatusCode -ne 200 -or $activated.Json.code -ne 200) {
        throw 'The account could not be enabled after password issuance.'
    }
    $firstUserSession = Login $validPhone $firstTemporaryPassword

    $secondReset = Send-ApiRequest 'POST' "$base/system/user/resetPwd?userId=$userId" $adminHeaders
    $secondTemporaryPassword = [string]$secondReset.Json.data
    $secondResetIsExpected = ($secondReset.StatusCode -eq 200) -and
        ($secondReset.Json.code -eq 200) -and
        ($secondTemporaryPassword.Length -eq 20) -and
        ($secondTemporaryPassword -ne $firstTemporaryPassword)
    if (-not $secondResetIsExpected) {
        throw 'A second reset did not return an independent temporary password.'
    }
    $oldSession = Send-ApiRequest 'GET' "$base/user/info" $firstUserSession
    if ($oldSession.Json.code -eq 200) { throw 'The previous session remained valid after reset.' }

    $userHeaders = Login $validPhone $secondTemporaryPassword
    $userInfo = Send-ApiRequest 'GET' "$base/user/info" $userHeaders
    if ($userInfo.StatusCode -ne 200 -or $userInfo.Json.code -ne 200) {
        throw 'The imported account could not log in with the current temporary password.'
    }

    $unauthorizedExport = Send-ApiRequest 'GET' "$base/system/user/export?username=$encodedUsername" $userHeaders
    $exportWasAllowed = $unauthorizedExport.StatusCode -eq 200 -and
            ($unauthorizedExport.ContentType -ne 'application/json' -or $unauthorizedExport.Json.code -eq 200)
    if ($exportWasAllowed -or $unauthorizedExport.Body.Contains($username)) {
        throw 'A user without list permission could export or read the test account.'
    }

} finally {
    if ($null -ne $adminHeaders) {
        if ($null -eq $userId) {
            try {
                $encodedUsername = [Uri]::EscapeDataString($username)
                $list = Send-ApiRequest 'GET' "$base/system/user/list?username=$encodedUsername&pageNum=1&pageSize=10" $adminHeaders
                $match = @($list.Json.data.records | Where-Object { $_.username -eq $username }) | Select-Object -First 1
                if ($null -ne $match) { $userId = [string]$match.userId }
            } catch { $cleanupFailure = 'Could not locate the imported account for cleanup.' }
        }
        if (-not [string]::IsNullOrWhiteSpace($userId)) {
            try {
                $removeResult = Send-ApiRequest 'POST' "$base/system/user/remove?userId=$userId" $adminHeaders
                if ($removeResult.StatusCode -ne 200 -or $removeResult.Json.code -ne 200) {
                    throw 'User removal was rejected.'
                }
                $verifyRemoval = Send-ApiRequest 'GET' "$base/system/user/list?username=$encodedUsername&pageNum=1&pageSize=10" $adminHeaders
                $remaining = @($verifyRemoval.Json.data.records | Where-Object { $_.username -eq $username })
                if ($verifyRemoval.StatusCode -ne 200 -or $verifyRemoval.Json.code -ne 200 -or $remaining.Count -gt 0) {
                    throw 'User removal could not be verified.'
                }
            } catch { $cleanupFailure = "Could not verify cleanup for userId=$userId." }
        }
        try { $null = Send-ApiRequest 'POST' "$base/user/logout" $adminHeaders } catch { }
    }
    if ($null -ne $userHeaders) {
        try { $null = Send-ApiRequest 'POST' "$base/user/logout" $userHeaders } catch { }
    }
    if (Test-Path -LiteralPath $testDirectory) {
        $resolved = (Resolve-Path -LiteralPath $testDirectory).Path
        $tempRoot = (Resolve-Path -LiteralPath $env:TEMP).Path.TrimEnd('\')
        if ($resolved.StartsWith($tempRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    $firstTemporaryPassword = $null
    $secondTemporaryPassword = $null
    $adminHeaders = $null
    $userHeaders = $null
    $client.Dispose()
}

if ($null -ne $cleanupFailure) {
    throw $cleanupFailure
}
Write-Output "BULK-01 real acceptance passed; userId=$userId tenantId=$tenantId; cleanup verified."
