param(
    [string]$BaseUrl = 'http://127.0.0.1:7799'
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$phone = $env:FND05_LOGIN_PHONE
$password = $env:FND05_LOGIN_PASSWORD
if ([string]::IsNullOrWhiteSpace($phone) -or [string]::IsNullOrWhiteSpace($password)) {
    throw 'Set FND05_LOGIN_PHONE and FND05_LOGIN_PASSWORD in the current process.'
}

function Assert-Result($name, $result) {
    if ($result.code -ne 200) {
        throw "$name failed: code=$($result.code), message=$($result.message)"
    }
    Write-Output "$name code=200"
}

$captcha = Invoke-RestMethod -Uri "$base/captcha"
Assert-Result 'captcha' $captcha

$body = @{ phone = $phone; password = $password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$base/user/login" -Method Post -ContentType 'application/json' -Body $body
Assert-Result 'user/login' $login
if ([string]::IsNullOrWhiteSpace($login.data)) { throw 'Login returned no token.' }

$headers = @{ Authorization = [string]$login.data }
try {
    $info = Invoke-RestMethod -Uri "$base/user/info" -Headers $headers
    Assert-Result 'user/info' $info
    $router = Invoke-RestMethod -Uri "$base/user/router" -Headers $headers
    Assert-Result 'user/router' $router
    $users = Invoke-RestMethod -Uri "$base/system/user/list?pageNum=1&pageSize=10" -Headers $headers
    Assert-Result 'system/user/list' $users
    Write-Output "router roots=$(@($router.data).Count); users total=$($users.data.total)"
} finally {
    $logout = Invoke-RestMethod -Uri "$base/user/logout" -Method Post -Headers $headers
    Assert-Result 'user/logout' $logout
}
