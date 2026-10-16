param(
    [Parameter(Mandatory)][Security.SecureString]$Authorization,
    [ValidateSet('http://127.0.0.1:17823','http://127.0.0.1:17833')][string]$BaseUri='http://127.0.0.1:17833',
    [Parameter(Mandatory)][string]$KeyFile
)
# Invoke with an in-memory token converted to SecureString; never pass a literal token in shell arguments.
# Both allowed URLs are local authorized QA/review applications. This script never tests a model.
$ErrorActionPreference='Stop'
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($Authorization)
try {
    $token=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $headers=@{Authorization=$token}
    $apiKey=[IO.File]::ReadAllText($KeyFile).Trim()
    if($apiKey.Length -eq 0 -or $apiKey.Length -gt 8192 -or $apiKey -match '[\x00-\x1f\x7f]') { throw 'Key file content is invalid.' }
    foreach($code in @('poolside/laguna-s-2.1:free','nvidia/nemotron-3.5-lightning:free')) {
        $query=[Uri]::EscapeDataString($code)
        $page=Invoke-RestMethod "$BaseUri/system/ai/models?providerCode=openrouter&modelCode=$query&pageNum=1&pageSize=10" -Headers $headers -TimeoutSec 15
        if($page.code -ne 200 -or $page.data.total -gt 1) { throw 'Model lookup failed.' }
        if($page.data.total -eq 0) {
            $body=@{providerCode='openrouter';modelCode=$code;endpointUrl='https://openrouter.ai/api/v1';timeoutSeconds=60;enabled=$true}|ConvertTo-Json -Compress
            $created=Invoke-RestMethod "$BaseUri/system/ai/models" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15
            if($created.code -ne 200) { throw 'Model creation failed.' }
            $id=$created.data
        } else { $id=$page.data.records[0].id }
        $current=Invoke-RestMethod "$BaseUri/system/ai/models/$id" -Headers $headers -TimeoutSec 15
        if($current.code -ne 200 -or $current.data.endpointUrl -ne 'https://openrouter.ai/api/v1' -or !$current.data.enabled) { throw 'Existing model must be enabled and use the fixed official endpoint before importing.' }
        $body=@{mode='DB';apiKey=$apiKey;clearKey=$false;revision=$current.data.revision}|ConvertTo-Json -Compress
        $saved=Invoke-RestMethod "$BaseUri/system/ai/models/$id/credential" -Method Put -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15
        if($saved.code -ne 200 -or $saved.data.credentialMode -ne 'DB' -or !$saved.data.credentialConfigured) { throw 'Encrypted credential import failed.' }
        Write-Output "Imported ${code}: encrypted database credential configured; provider requests=0"
    }
} catch {
    throw 'Secure API import failed. Check local application permissions/configuration without printing headers or request bodies.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $token=$null;$apiKey=$null;$body=$null;$headers=$null
}
