param([switch]$SelfTest)
$ErrorActionPreference = 'Stop'

function Assert-Inventory($Required, $Services, $Native, $Scoped, $Sources) {
    $selected = @($Required) + @($Services)
    $classified = $selected + @($Native)
    if (@($classified | Group-Object | Where-Object Count -ne 1).Count) { throw 'Duplicate or overlapping test classification' }
    foreach ($name in $classified) {
        if (!$Sources.ContainsKey($name) -or $Sources[$name].Count -ne 1) { throw "Missing or ambiguous test source: $name" }
    }
    foreach ($name in $Scoped) {
        if ($name -notin $classified) { throw "AI test must be classified as required, service, or native: $name" }
    }
    foreach ($name in $selected) {
        if ($Sources[$name][0] -match '@(?:Disabled|EnabledIf\w*|DisabledIf\w*)\b|\b(?:assumeTrue|assumeFalse|assumingThat)\s*\(') {
            throw "Required CI test may not conditionally skip: $name"
        }
    }
}

if ($SelfTest) {
    $sources = @{ Required = @('@Test void executes() {}'); Service = @('@Test void executes() {}'); Native = @('@EnabledIfEnvironmentVariable') }
    Assert-Inventory @('Required') @('Service') @('Native') @('Required','Service','Native') $sources
    $cases = @(
        @{ Required = @('Required'); Services = @('Service'); Native = @('Required','Native'); Scoped = @('Required') },
        @{ Required = @('Missing'); Services = @('Service'); Native = @('Native'); Scoped = @('Missing') },
        @{ Required = @('Required'); Services = @('Service'); Native = @('Native'); Scoped = @('Unclassified') },
        @{ Required = @('Native'); Services = @('Service'); Native = @(); Scoped = @('Native') }
    )
    foreach ($case in $cases) {
        $rejected = $false
        try { Assert-Inventory $case.Required $case.Services $case.Native $case.Scoped $sources } catch { $rejected = $true }
        if (!$rejected) { throw 'Inventory accepted an invalid classification' }
    }
    Write-Output 'AI inventory rejects overlap, absent sources, unclassified tests, and conditional skips'
    exit 0
}

$required = @(Get-Content scripts/quality/required-tests.txt | Where-Object { $_ })
$services = @(Get-Content scripts/quality/required-service-tests.txt | Where-Object { $_ })
$native = @(Get-Content scripts/quality/native-ai-tests.txt | Where-Object { $_ })
$sources = @{}
foreach ($module in Get-ChildItem -Directory) {
    $testRoot = Join-Path $module.FullName 'src/test/java'
    if (!(Test-Path -LiteralPath $testRoot)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $testRoot -Recurse -File -Filter '*.java') {
        if ($file.BaseName -notmatch '(Test|IT)$') { continue }
        if (!$sources.ContainsKey($file.BaseName)) { $sources[$file.BaseName] = @() }
        $sources[$file.BaseName] += Get-Content -LiteralPath $file.FullName -Raw
    }
}
$scoped = @(Get-ChildItem system-admin/src/test/java/com/xinghuiTec/ai -Recurse -File -Filter '*.java' | Where-Object BaseName -Match '(Test|IT)$' | ForEach-Object BaseName)
$scoped += @(Get-ChildItem system-admin/src/test/java/com/xinghuiTec/security -File -Filter 'Ai*Test.java' | ForEach-Object BaseName)
Assert-Inventory $required $services $native $scoped $sources
Write-Output "AI inventory verified: scoped=$($scoped.Count), requiredTotal=$($required.Count), services=$($services.Count), nativeExcluded=$($native.Count)"
