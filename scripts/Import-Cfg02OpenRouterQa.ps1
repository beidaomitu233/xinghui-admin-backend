param([string]$Maven='mvn')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Require-QaEnvironment.ps1')
$null=Get-RequiredQaEnvironment 'CONFIG_MASTER_KEY'
$null=Get-RequiredQaEnvironment 'CFG02_DB_PASSWORD'
$keyFile=Get-RequiredQaEnvironment 'CFG02_PROVIDER_KEY_FILE'
if (!(Test-Path -LiteralPath $keyFile -PathType Leaf)) { throw 'CFG02_PROVIDER_KEY_FILE must identify an existing private key file.' }
$names=@('CFG02_NATIVE_ENABLED')
$previous=@{}
foreach($name in $names) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $env:CFG02_NATIVE_ENABLED='true'
    & $Maven -o -pl system-admin -am test '-Dtest=BusinessConfigNativeQaTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx192m -XX:ActiveProcessorCount=2'
    if($LASTEXITCODE -ne 0) { throw 'QA failed; inspect private reports. Do not retry a real provider automatically.' }
} finally {
    Pop-Location
    foreach($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
