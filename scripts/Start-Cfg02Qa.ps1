param(
    [string]$QaDirectory = $env:CFG02_QA_DIRECTORY,
    [ValidateRange(1024,65535)][int]$Port = 17833,
    [string]$Java = 'java'
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Require-QaEnvironment.ps1')
$backend = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($QaDirectory)) { throw 'CFG02_QA_DIRECTORY or -QaDirectory is required.' }
$privateDir = [IO.Path]::GetFullPath($QaDirectory)
foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','SPRING_DATA_REDIS_HOST','SPRING_DATA_REDIS_PORT','SPRING_DATA_REDIS_DATABASE','SPRING_DATA_REDIS_PASSWORD','CONFIG_MASTER_KEY','MINIO_ENDPOINT','MINIO_ACCESS_KEY','MINIO_SECRET_KEY','MINIO_BUCKET')) {
    $null = Get-RequiredQaEnvironment $name
}
New-Item -ItemType Directory -Force -Path $privateDir | Out-Null
$mapped = @{
    JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    REDIS_PORT = $env:SPRING_DATA_REDIS_PORT
    REDIS_DATABASE = $env:SPRING_DATA_REDIS_DATABASE
    REDIS_PASSWORD = $env:SPRING_DATA_REDIS_PASSWORD
    OSS_ENABLED = 'true'
    OSS_ENDPOINT = $env:MINIO_ENDPOINT
    OSS_ACCESS_KEY = $env:MINIO_ACCESS_KEY
    OSS_SECRET_KEY = $env:MINIO_SECRET_KEY
    OSS_BUCKET = (Get-RequiredQaEnvironment 'MINIO_BUCKET')
}
$previous = @{}
foreach ($name in $mapped.Keys) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    # Disposable local QA signing key only; the stable configuration master key is supplied externally.
    foreach ($name in $mapped.Keys) { [Environment]::SetEnvironmentVariable($name, $mapped[$name], 'Process') }
    $argsForJava = @('-Xmx288m','-XX:MaxMetaspaceSize=160m','-XX:ActiveProcessorCount=2','-jar',"$backend/system-admin/target/system-admin.jar",'--server.address=127.0.0.1',"--server.port=$Port",'--spring.flyway.baseline-on-migrate=false','--spring.flyway.clean-disabled=true','--spring.flyway.validate-on-migrate=true','--mail.enabled=false','--logging.level.com.xinghuiTec.mapper=info','--spring.datasource.dynamic.druid.initial-size=1','--spring.datasource.dynamic.druid.min-idle=1','--spring.datasource.dynamic.druid.max-active=4','--tenant.enable=true')
    $process = Start-Process $Java -ArgumentList $argsForJava -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput "$privateDir/server.out.log" -RedirectStandardError "$privateDir/server.err.log"
    $process.Id | Set-Content -LiteralPath "$privateDir/server.pid"
    Write-Output "CFG02 Java PID $($process.Id), loopback port $Port; resources supplied by environment."
} finally {
    foreach ($name in $mapped.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
