# Dot-source this file and assign Read-ConfigMasterKey to a process environment variable.
# The protected file is outside all Git worktrees. Never print the returned value.
function Read-ConfigMasterKey {
    param([Parameter(Mandatory)][string]$Path)
    Add-Type -AssemblyName System.Security
    $protected = [IO.File]::ReadAllBytes($Path)
    $plain = [Security.Cryptography.ProtectedData]::Unprotect($protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try {
        if ($plain.Length -ne 32) { throw 'Configuration master key is invalid.' }
        [Convert]::ToBase64String($plain)
    } finally { [Array]::Clear($plain, 0, $plain.Length) }
}
