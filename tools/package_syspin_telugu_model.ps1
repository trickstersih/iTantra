param(
    [Parameter(Mandatory = $true)]
    [string]$TtsRoot,

    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"

$TtsRoot = (Resolve-Path $TtsRoot).Path
$ProjectRoot = (Resolve-Path $ProjectRoot).Path

$sourceConfig = Join-Path $TtsRoot "models\SYSPIN_vits_Telugu_Female\config.json"
$sourceModel = Join-Path $TtsRoot "outputs\quantized\06_embedding_int4_optional.onnx"
$assetDirectory = Join-Path $ProjectRoot "app\src\main\assets\models"
$assetZip = Join-Path $assetDirectory "syspin-telugu.zip"

if (!(Test-Path -LiteralPath $sourceConfig -PathType Leaf)) {
    throw "Source config not found: $sourceConfig"
}
if (!(Test-Path -LiteralPath $sourceModel -PathType Leaf)) {
    throw "Selected Telugu candidate not found: $sourceModel"
}
if (Test-Path -LiteralPath $assetZip) {
    throw ("Refusing to overwrite existing archive: " + $assetZip +
        [Environment]::NewLine + "Inspect/delete it manually before rerunning.")
}

function Convert-NonFiniteJsonTokens([string]$Source) {
    # Replace bare Python JSON extensions only outside quoted strings.
    # Quoted text such as "Infinity" must remain unchanged.
    $output = [System.Text.StringBuilder]::new($Source.Length)
    $index = 0
    $insideString = $false
    $escaped = $false

    while ($index -lt $Source.Length) {
        $current = $Source[$index]

        if ($insideString) {
            [void]$output.Append($current)
            if ($escaped) {
                $escaped = $false
            } elseif ([int][char]$current -eq 92) {
                $escaped = $true
            } elseif ($current -eq '"') {
                $insideString = $false
            }
            $index++
            continue
        }

        if ($current -eq '"') {
            $insideString = $true
            [void]$output.Append($current)
            $index++
            continue
        }

        $token = $null
        if ($Source.IndexOf("-Infinity", $index, [StringComparison]::Ordinal) -eq $index) {
            $token = "-Infinity"
        } elseif ($Source.IndexOf("Infinity", $index, [StringComparison]::Ordinal) -eq $index) {
            $token = "Infinity"
        } elseif ($Source.IndexOf("NaN", $index, [StringComparison]::Ordinal) -eq $index) {
            $token = "NaN"
        }

        if ($null -ne $token) {
            $afterIndex = $index + $token.Length
            $before = if ($index -gt 0) { $Source[$index - 1] } else { [char]0 }
            $after = if ($afterIndex -lt $Source.Length) { $Source[$afterIndex] } else { [char]0 }
            $leftBoundary = (
                $index -eq 0 -or
                [char]::IsWhiteSpace($before) -or
                $before -eq ':' -or $before -eq ',' -or $before -eq '['
            )
            $rightBoundary = (
                $afterIndex -ge $Source.Length -or
                [char]::IsWhiteSpace($after) -or
                $after -eq ',' -or $after -eq '}' -or $after -eq ']'
            )
            if ($leftBoundary -and $rightBoundary) {
                [void]$output.Append("null")
                $index = $afterIndex
                continue
            }
        }

        [void]$output.Append($current)
        $index++
    }

    return $output.ToString()
}

$stage = Join-Path $env:TEMP ("syspin-telugu-package-" + [guid]::NewGuid().ToString("N"))
$packageRoot = Join-Path $stage "syspin-telugu"
$payload = Join-Path $packageRoot "tel"
$stagedConfig = Join-Path $payload "config.json"
$stagedModel = Join-Path $payload "model.onnx"

try {
    New-Item -ItemType Directory -Force -Path $payload | Out-Null
    New-Item -ItemType Directory -Force -Path $assetDirectory | Out-Null

    $configText = [System.IO.File]::ReadAllText($sourceConfig)
    $sanitizedConfig = Convert-NonFiniteJsonTokens $configText

    # Validate strict JSON before putting it in the archive. The original model
    # config remains untouched; only this staged copy converts bare Infinity,
    # -Infinity, and NaN values to null.
    $null = ConvertFrom-Json -InputObject $sanitizedConfig
    [System.IO.File]::WriteAllText(
        $stagedConfig,
        $sanitizedConfig,
        [System.Text.UTF8Encoding]::new($false)
    )
    Copy-Item -LiteralPath $sourceModel -Destination $stagedModel

    Compress-Archive -Path $packageRoot -DestinationPath $assetZip -CompressionLevel Optimal

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($assetZip)
    try {
        $entries = @($zip.Entries | ForEach-Object { $_.FullName })
        $expected = @(
            "syspin-telugu/tel/config.json",
            "syspin-telugu/tel/model.onnx"
        )
        foreach ($entry in $expected) {
            if ($entry -notin $entries) {
                throw "Archive is missing required entry: $entry"
            }
        }
        $configEntry = $zip.GetEntry("syspin-telugu/tel/config.json")
        $configReader = [System.IO.StreamReader]::new($configEntry.Open())
        try {
            $archivedConfig = $configReader.ReadToEnd()
            $null = ConvertFrom-Json -InputObject $archivedConfig
        } finally {
            $configReader.Dispose()
        }
        $modelEntry = $zip.GetEntry("syspin-telugu/tel/model.onnx")
        if ($modelEntry.Length -le 0) {
            throw "Archived model.onnx is empty."
        }
        $entries | ForEach-Object { Write-Host "ZIP entry: $_" }
    } finally {
        $zip.Dispose()
    }

    Write-Host ""
    Write-Host "Telugu model archive created successfully:"
    Write-Host "  $assetZip"
    Get-Item -LiteralPath $assetZip | Select-Object FullName, Length
    Write-Host ""
    Write-Host "Candidate: $sourceModel"
    Write-Host "Config source (unchanged): $sourceConfig"
    Write-Host "Only the staged config was sanitized for strict JSON."
} catch {
    if (Test-Path -LiteralPath $assetZip) {
        Remove-Item -LiteralPath $assetZip -Force
    }
    throw
} finally {
    if (Test-Path -LiteralPath $stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
