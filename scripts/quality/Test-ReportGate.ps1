$ErrorActionPreference = 'Stop'
$verifier = Join-Path $PSScriptRoot 'Verify-Build.ps1'
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('oss04-report-gate-' + [guid]::NewGuid())
$reports = Join-Path $fixtureRoot 'target/surefire-reports'
New-Item -ItemType Directory -Path $reports -Force | Out-Null
$report = Join-Path $reports 'TEST-fixture.RequiredFixture.xml'
$shell = (Get-Process -Id $PID).Path
try {
    Push-Location $fixtureRoot
    foreach ($case in @('success', 'missing', 'zero', 'failed', 'error', 'skipped', 'duplicate')) {
        if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report }
        $duplicate = Join-Path $reports 'TEST-other.RequiredFixture.xml'
        if (Test-Path -LiteralPath $duplicate) { Remove-Item -LiteralPath $duplicate }
        if ($case -ne 'missing') {
            $tests = if ($case -eq 'zero') { 0 } else { 1 }
            $failures = if ($case -eq 'failed') { 1 } else { 0 }
            $errors = if ($case -eq 'error') { 1 } else { 0 }
            $skipped = if ($case -eq 'skipped') { 1 } else { 0 }
            Set-Content -LiteralPath $report -Value "<testsuite tests='$tests' failures='$failures' errors='$errors' skipped='$skipped'/>"
            if ($case -eq 'duplicate') { Copy-Item -LiteralPath $report -Destination $duplicate }
        }
        & $shell -NoProfile -File $verifier -TestsOnly -RequiredClass RequiredFixture *> $null
        $status = $LASTEXITCODE
        if ($case -eq 'success' -and $status -ne 0) { throw 'Valid fixture was rejected' }
        if ($case -ne 'success' -and $status -eq 0) { throw "Invalid fixture was accepted: $case" }
        Write-Output "Report gate $case verified (exit=$status)"
    }
    # The services job requires both independently executed classes.
    Remove-Item -LiteralPath $duplicate -ErrorAction SilentlyContinue
    $requiredList = Join-Path $fixtureRoot 'required-services.txt'
    Set-Content -LiteralPath $requiredList -Value @('RequiredFixture','SecondFixture')
    Set-Content -LiteralPath $report -Value "<testsuite tests='1' failures='0' errors='0' skipped='0'/>"
    & $shell -NoProfile -File $verifier -TestsOnly -RequiredList $requiredList *> $null
    if ($LASTEXITCODE -eq 0) { throw 'Missing service report was accepted' }
    $second = Join-Path $reports 'TEST-fixture.SecondFixture.xml'
    Copy-Item -LiteralPath $report -Destination $second
    & $shell -NoProfile -File $verifier -TestsOnly -RequiredList $requiredList *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Valid complete service reports were rejected' }
    Write-Output 'Service list requires both reports'
} finally {
    Pop-Location
    # Remove only the seven explicitly generated fixture files/directory levels.
    foreach ($name in @('TEST-fixture.RequiredFixture.xml', 'TEST-other.RequiredFixture.xml', 'TEST-fixture.SecondFixture.xml')) {
        $path = Join-Path $reports $name
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
    Remove-Item -LiteralPath $reports
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'target')
    if ($requiredList -and (Test-Path -LiteralPath $requiredList)) { Remove-Item -LiteralPath $requiredList }
    Remove-Item -LiteralPath $fixtureRoot
}
