param([switch]$TestsOnly,[string]$RequiredClass,[string]$RequiredList='scripts/quality/required-tests.txt')
$ErrorActionPreference='Stop'
$reports=@(Get-ChildItem -Path . -Recurse -Filter TEST-*.xml -File -ErrorAction Stop | Where-Object { $_.Directory.Name -eq 'surefire-reports' })
$classes=if($RequiredClass) { @($RequiredClass) } else { @(Get-Content $RequiredList | Where-Object { $_ }) }
foreach($class in $classes) {
  $selected=@($reports | Where-Object { $_.Name -like "*.$class.xml" })
  if($selected.Count -ne 1) { throw "Required test report missing or ambiguous: $class" }
  [xml]$required=Get-Content -LiteralPath $selected[0].FullName -Raw
  if([int]$required.testsuite.tests -lt 1 -or [int]$required.testsuite.skipped -ne 0) { throw "Required class has no executed tests: $class" }
}
if($classes.Count -lt 1) { throw 'Empty required test list' }
$total=0
foreach($report in $reports) {
  [xml]$xml=Get-Content -LiteralPath $report.FullName -Raw
  $suite=$xml.testsuite
  if([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) { throw 'Required run contains failure/error/skipped tests' }
  $total += [int]$suite.tests
}
if($total -lt 1) { throw 'No required tests executed' }
if(!$TestsOnly) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $jar=[System.IO.Compression.ZipFile]::OpenRead((Join-Path $PWD 'system-admin/target/system-admin.jar'))
  try {
    if(!($jar.Entries | Where-Object { $_.FullName.StartsWith('BOOT-INF/classes/') })) { throw 'Missing Boot classes' }
    $manifest=$jar.GetEntry('META-INF/MANIFEST.MF')
    if(!$manifest) { throw 'Missing manifest' }
    $reader=[System.IO.StreamReader]::new($manifest.Open())
    try { if($reader.ReadToEnd() -notmatch 'Main-Class: org.springframework.boot.loader.launch.JarLauncher') { throw 'Not an executable Boot jar' } } finally { $reader.Dispose() }
  } finally { $jar.Dispose() }
}
Write-Output "Required tests executed=$total; verification passed"
