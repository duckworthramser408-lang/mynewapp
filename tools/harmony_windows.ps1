# SDK diagnostics by default. -Build also installs project dependencies and builds a HAP.
# Uses installed DevEco tools; SDK installation and device deployment are outside this script.
[CmdletBinding()]
param(
    [string]$SdkPath = 'E:\DevEco Studio\sdk\default',
    [string]$DevEcoPath = 'E:\DevEco Studio',
    [switch]$Build
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$project = Join-Path $repo 'harmony'
$output = Join-Path $repo '.build/harmony-windows'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$reportPath = Join-Path $output 'environment.txt'
$symbolsPath = Join-Path $output 'sdk-face-api.txt'
$report = New-Object 'System.Collections.Generic.List[string]'
$report.Add('G23 HarmonyOS local environment report')
$report.Add('Time: ' + (Get-Date -Format o))
$report.Add('PowerShell: ' + $PSVersionTable.PSVersion.ToString())
$report.Add('No model inference, device validation or deployment is performed. Builds use existing project signing settings.')
$code = 0

function Existing-File([string[]]$Candidates) {
    foreach ($candidate in $Candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return $null
}

function Invoke-BuildTool([string]$Executable, [string[]]$Arguments, [string]$Log) {
    # Windows PowerShell 5 treats stderr as ErrorRecords: preserve the native exit code.
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments 2>&1 | Tee-Object -FilePath $Log | Out-Host
        $nativeCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($nativeCode -ne 0) { throw "Tool failed with exit code $nativeCode. Log: $Log" }
}

try {
    $SdkPath = (Resolve-Path -LiteralPath $SdkPath).Path
    $metadataFile = Join-Path $SdkPath 'sdk-pkg.json'
    if (!(Test-Path -LiteralPath $metadataFile)) {
        throw "SDK root must contain sdk-pkg.json: $SdkPath"
    }
    $metadata = Get-Content -LiteralPath $metadataFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $sdk = $metadata
    if ($metadata.PSObject.Properties.Name -contains 'data') { $sdk = $metadata.data }
    $report.Add('SDK: ' + $SdkPath)
    $report.Add('SDK metadata: ' + ($sdk | ConvertTo-Json -Compress))
    if ([string]$sdk.apiVersion -ne '26') { throw 'This project targets API 26; installed SDK metadata differs.' }

    $profile = Get-Content -LiteralPath (Join-Path $project 'build-profile.json5') -Raw -Encoding UTF8 | ConvertFrom-Json
    $product = @($profile.app.products | Where-Object { $_.name -eq 'default' })[0]
    $report.Add('Project default product: ' + ($product | Select-Object name, compileSdkVersion, targetSdkVersion, compatibleSdkVersion | ConvertTo-Json -Compress))
    if ([int]$product.compileSdkVersion -ne 26 -or [int]$product.targetSdkVersion -ne 26) {
        throw 'Download the current project: default compile/target SDK must be 26.'
    }
    $node = Existing-File @((Join-Path $DevEcoPath 'tools/node/node.exe'), (Join-Path $DevEcoPath 'tools/nodejs/node.exe'))
    $hvigor = Existing-File @((Join-Path $DevEcoPath 'tools/hvigor/bin/hvigorw.js'))
    $ohpm = Existing-File @((Join-Path $DevEcoPath 'tools/ohpm/bin/ohpm.bat'))
    $java = Existing-File @((Join-Path $DevEcoPath 'jbr/bin/java.exe'), (Join-Path $DevEcoPath 'jre/bin/java.exe'))
    foreach ($entry in @(@('Node', $node), @('Hvigor', $hvigor), @('OHPM', $ohpm), @('Java', $java))) {
        $report.Add($entry[0] + ': ' + $(if ($entry[1]) { $entry[1] } else { 'NOT FOUND in this DevEco installation' }))
    }
    $report.Add('SDK root folders: ' + (@(Get-ChildItem -LiteralPath $SdkPath -Directory | ForEach-Object { $_.Name }) -join ', '))
    $report.Add('SDK modules:')
    foreach ($file in Get-ChildItem -LiteralPath $SdkPath -Filter sdk-pkg.json -Recurse -File | Sort-Object FullName) {
        $report.Add($file.FullName + ': ' + ((Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json) | ConvertTo-Json -Compress -Depth 6))
    }

    # Read only declaration names and relevant excerpts from the user's installed SDK.
    # This is API evidence, not evidence that inference or a model conversion works.
    $symbols = New-Object 'System.Collections.Generic.List[string]'
    $symbols.Add('SDK declarations only; capability and landmark precision are NOT validated.')
    $candidates = @(Get-ChildItem -LiteralPath $SdkPath -Filter '*.d.ts' -Recurse -File |
        Where-Object { $_.Name -match '(?i)face|vision|mindspore|segment|multimedia\.camera' } |
        Sort-Object FullName | Select-Object -First 40)
    foreach ($file in $candidates) {
        $symbols.Add('FILE: ' + $file.FullName)
        $matches = @(Select-String -LiteralPath $file.FullName -Encoding UTF8 -Pattern '(?i)face|landmark|keypoint|pose|segment|mindspore|interface .*Point' -Context 2, 4 | Select-Object -First 60)
        foreach ($match in $matches) {
            $symbols.Add('Line ' + $match.LineNumber + ':')
            $symbols.Add(($match.Context.PreContext -join "`n"))
            $symbols.Add($match.Line)
            $symbols.Add(($match.Context.PostContext -join "`n"))
        }
    }
    if ($candidates.Count -eq 0) { $symbols.Add('No matching .d.ts filenames found. Do not infer platform capability from this absence.') }
    $symbols | Set-Content -LiteralPath $symbolsPath -Encoding UTF8
    $report.Add('Relevant SDK declaration files: ' + $candidates.Count)
    $report.Add('Declaration excerpts: ' + $symbolsPath)
    $report.Add('Preflight: SDK and project metadata matched API 26; tool paths listed above.')

    if ($Build) {
        if ($env:OS -ne 'Windows_NT') { throw '-Build requires the actual Windows DevEco installation.' }
        if (!$node -or !$hvigor -or !$ohpm -or !$java) { throw 'DevEco build tools are missing. Open this report before changing SDK or installing other versions.' }
        $savedPath = $env:PATH
        $savedJava = $env:JAVA_HOME
        $savedSdk = $env:DEVECO_SDK_HOME
        try {
            # DEVECO_SDK_HOME points at the directory containing the bundled default SDK.
            $env:DEVECO_SDK_HOME = Split-Path $SdkPath -Parent
            $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
            $env:PATH = (Split-Path $node -Parent) + ';' + (Split-Path $ohpm -Parent) + ';' + (Split-Path $java -Parent) + ';' + $savedPath
            Push-Location $project
            try {
                Invoke-BuildTool -Executable $ohpm -Arguments @('install') -Log (Join-Path $output 'ohpm.log')
                $started = Get-Date
                Invoke-BuildTool -Executable $node -Arguments @($hvigor, '--mode', 'module', '-p', 'product=default', '-p', 'module=entry@default', '-p', 'buildMode=debug', 'assembleHap', '--no-daemon') -Log (Join-Path $output 'hvigor.log')
                $haps = @(Get-ChildItem -LiteralPath (Join-Path $project 'entry/build') -Filter '*.hap' -Recurse -File | Where-Object { $_.LastWriteTime -ge $started })
                if ($haps.Count -eq 0) { throw 'No newly built HAP found; a successful tool exit alone is insufficient.' }
                foreach ($hap in $haps) {
                    $report.Add('New HAP: ' + $hap.FullName)
                    $report.Add('SHA256: ' + (Get-FileHash -LiteralPath $hap.FullName -Algorithm SHA256).Hash)
                }
                $report.Add('Build: HAP produced. Installability, signatures, inference and device behavior remain unverified.')
            } finally { Pop-Location }
        } finally {
            $env:PATH = $savedPath
            $env:JAVA_HOME = $savedJava
            $env:DEVECO_SDK_HOME = $savedSdk
        }
    } else { $report.Add('Build: NOT RUN. Add -Build to invoke the installed OHPM / Hvigor tools.') }
} catch {
    $code = 1
    $report.Add('FAILED: ' + $_.Exception.Message)
} finally {
    $report | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $report | ForEach-Object { Write-Output $_ }
    Write-Output ('Report saved: ' + $reportPath)
}
exit $code
