# Shared by opt-in local QA helpers. Values remain in memory and must never be printed.
function Get-RequiredQaEnvironment {
    param([Parameter(Mandatory)][string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Required QA environment variable is missing: $Name" }
    return $value
}
