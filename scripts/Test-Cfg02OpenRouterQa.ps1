param([string]$Maven='mvn',[switch]$RepairRetry)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Require-QaEnvironment.ps1')
$null=Get-RequiredQaEnvironment 'CONFIG_MASTER_KEY'
$null=Get-RequiredQaEnvironment 'CFG02_DB_PASSWORD'
$null=Get-RequiredQaEnvironment 'CFG02_REDIS_PASSWORD'
$null=Get-RequiredQaEnvironment 'CFG02_QA_DIRECTORY'
$names=@('CFG02_REAL_ENABLED','CFG02_REAL_ATTEMPT')
$previous=@{}
foreach($name in $names) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $env:CFG02_REAL_ENABLED='true'
    $env:CFG02_REAL_ATTEMPT=if($RepairRetry) {'retry'} else {'first'}
    & $Maven -o -pl system-admin -am test '-Dtest=OpenRouterNativeQaTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx192m -XX:ActiveProcessorCount=2'
    if($LASTEXITCODE -ne 0) { throw 'QA failed; inspect private reports. Do not retry a real provider automatically.' }
} finally {
    Pop-Location
    foreach($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
