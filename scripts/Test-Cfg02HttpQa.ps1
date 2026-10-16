param(
    [ValidateSet('Create','VerifyAfterRestart')][string]$Phase = 'Create',
    [string]$QaDirectory = $env:CFG02_QA_DIRECTORY,
    [string]$BaseUri = $env:CFG02_QA_BASE_URI
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Require-QaEnvironment.ps1')
if ([string]::IsNullOrWhiteSpace($QaDirectory) -or [string]::IsNullOrWhiteSpace($BaseUri)) { throw 'CFG02_QA_DIRECTORY and CFG02_QA_BASE_URI are required.' }
$address = [Uri]$BaseUri
if (!$address.IsLoopback -or $address.Scheme -notin @('http','https')) { throw 'QA base URI must be loopback HTTP(S).' }
$privateDir = [IO.Path]::GetFullPath($QaDirectory)
$base = $BaseUri.TrimEnd('/')
$loginBody = @{phone=(Get-RequiredQaEnvironment 'CFG02_QA_PHONE');password=(Get-RequiredQaEnvironment 'CFG02_QA_PASSWORD')} | ConvertTo-Json -Compress
$null=Get-RequiredQaEnvironment 'MINIO_ACCESS_KEY'
$null=Get-RequiredQaEnvironment 'MINIO_SECRET_KEY'
New-Item -ItemType Directory -Force -Path $privateDir | Out-Null
$login=Invoke-RestMethod "$base/user/login" -Method Post -ContentType 'application/json' -Body $loginBody
if($login.code -ne 200) { throw 'Assigned QA seed administrator login failed.' }
$headers=@{Authorization=$login.data}
function Call-Cfg($method,$path,$data=$null) {
    $args=@{Uri="$base$path";Method=$method;Headers=$headers;ContentType='application/json';TimeoutSec=15;SkipHttpErrorCheck=$true}
    if($null -ne $data) { $args.Body=ConvertTo-Json -InputObject $data -Depth 8 -Compress }
    $response=Invoke-WebRequest @args
    $result=$response.Content|ConvertFrom-Json
    if($response.StatusCode -ge 400 -or $result.code -ne 200) { throw "QA request failed: $method $path HTTP $($response.StatusCode), code $($result.code)" }
    return $result.data
}
function Download-Check($id,$expected) {
    $response=Invoke-WebRequest "$base/system/oss/download/$id" -Headers $headers -TimeoutSec 15
    $actual=if($response.Content -is [byte[]]) {[Text.Encoding]::UTF8.GetString($response.Content)} else {[string]$response.Content}
    if($actual -ne $expected) { throw 'Actual OSS downloaded bytes differ.' }
}
if($Phase -eq 'VerifyAfterRestart') {
    $fixture=Get-Content "$privateDir/http-oss-fixture.json" -Raw|ConvertFrom-Json
    Download-Check $fixture.id $fixture.content
    $removed=Call-Cfg 'POST' '/system/oss/remove' @($fixture.id)
    $page=Call-Cfg 'GET' '/system/oss/list?pageNum=1&pageSize=100'
    $ownedLeftovers=@($page.records | Where-Object { $_.originalName -eq 'cfg02-http-fixture.txt' -and $_.platform -like 'business-oss-*' })
    foreach($item in $ownedLeftovers) { $removed=Call-Cfg 'POST' '/system/oss/remove' @($item.ossId) }
    Write-Output 'CFG02_HTTP restarted historical OSS download/delete PASS; uploaded object removed.'
    return
}
$all=Call-Cfg 'GET' '/system/businessConfig'
if(@($all).Count -ne 3) { throw 'Expected three independently readable sections.' }
$oss=Call-Cfg 'GET' '/system/businessConfig/oss'
$secret=Get-RequiredQaEnvironment 'MINIO_SECRET_KEY'
$parameters=@{provider='minio';endpoint='http://127.0.0.1:19000';bucket='xinghui-admin';basePath='cfg02/first'}
$saved=Call-Cfg 'PUT' '/system/businessConfig/oss' @{enabled=$true;parameters=$parameters;secrets=@{accessKey=(Get-RequiredQaEnvironment 'MINIO_ACCESS_KEY');secretKey=$secret};clearSecrets=@();revision=$oss.revision}
if(($saved|ConvertTo-Json -Depth 8).Contains($secret)) { throw 'Masked response leaked a credential.' }
$filePath="$privateDir/cfg02-http-fixture.txt"
$content='CFG02 native OSS round trip 20261012'
[IO.File]::WriteAllText($filePath,$content,[Text.UTF8Encoding]::new($false))
$uploaded=Invoke-RestMethod "$base/system/oss/upload" -Method Post -Headers $headers -Form @{file=Get-Item -LiteralPath $filePath} -TimeoutSec 15
if($uploaded.code -ne 200 -or !$uploaded.data.ossId) { throw 'Actual upload failed.' }
$id=$uploaded.data.ossId
@{id=$id;content=$content;platform=$uploaded.data.platform}|ConvertTo-Json|Set-Content "$privateDir/http-oss-fixture.json"
if($uploaded.data.platform -ne "business-oss-$($saved.revision)") { throw 'Actual upload did not select the saved business configuration.' }
Download-Check $id $content
$parameters.basePath='cfg02/second'
$changed=Call-Cfg 'PUT' '/system/businessConfig/oss' @{enabled=$true;parameters=$parameters;secrets=@{accessKey='';secretKey=''};clearSecrets=@();revision=$saved.revision}
Download-Check $id $content
$stale=Invoke-WebRequest "$base/system/businessConfig/oss" -Method Put -Headers $headers -ContentType 'application/json' -Body (@{enabled=$false;parameters=$parameters;revision=$saved.revision}|ConvertTo-Json -Compress) -SkipHttpErrorCheck
if($stale.StatusCode -ne 409) { throw 'Revision conflict not rejected.' }
$parameters.endpoint='http://127.0.0.1:19009'
$failed=Invoke-WebRequest "$base/system/businessConfig/oss" -Method Put -Headers $headers -ContentType 'application/json' -Body (@{enabled=$true;parameters=$parameters;revision=$changed.revision}|ConvertTo-Json -Compress) -SkipHttpErrorCheck
if($failed.StatusCode -ne 400) { throw 'Failed storage replacement not rejected.' }
if((Call-Cfg 'GET' '/system/businessConfig/oss').revision -ne $changed.revision) { throw 'Failed probe changed revision.' }
Download-Check $id $content
Write-Output 'CFG02_HTTP sections=3; encrypted/masked config; actual OSS upload/download; blank-key retention; changed-prefix historical download; conflict and failed-probe preservation PASS. Restart verification pending.'
