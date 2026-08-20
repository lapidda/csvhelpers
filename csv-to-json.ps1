<#
.SYNOPSIS
    Converts a transposed CSV into one JSON file per data column.

.DESCRIPTION
    Expects a CSV laid out like this:

        Name,Server01,Server02
        Hostname,srv01,srv02
        IP,10.0.0.1,10.0.0.2
        Role,Web,DB

    Column 1 holds the attribute names, every further column is one record.
    Each record becomes its own JSON file, named after that column's header
    (e.g. Server01.json, Server02.json).

.EXAMPLE
    .\ConvertTo-JsonFiles.ps1 -Path .\data.csv -OutputDirectory .\out

.EXAMPLE
    .\ConvertTo-JsonFiles.ps1 -Path .\data.csv -Delimiter ';' -Force
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0, ValueFromPipeline, ValueFromPipelineByPropertyName)]
    [Alias('FullName')]
    [string]$Path,

    [Parameter(Position = 1)]
    [string]$OutputDirectory = '.',

    [char]$Delimiter = ',',

    [int]$Depth = 10,

    # Overwrite existing .json files instead of skipping them.
    [switch]$Force
)

begin {
    Set-StrictMode -Version Latest
    $ErrorActionPreference = 'Stop'

    $script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

    function ConvertTo-SafeFileName {
        param([string]$Name)
        $invalid = [System.IO.Path]::GetInvalidFileNameChars() -join ''
        $pattern = '[{0}]' -f [regex]::Escape($invalid)
        $safe = ($Name -replace $pattern, '_').Trim()
        if ([string]::IsNullOrWhiteSpace($safe)) { $safe = 'unnamed' }
        return $safe
    }

    # Reads the file with FileShare.ReadWrite so it works even while the CSV
    # is still open in Excel (which normally causes a sharing violation).
    function Read-CsvText {
        param([string]$LiteralPath)
        $stream = [System.IO.File]::Open(
            $LiteralPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite)
        try {
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8, $true)
            try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
        finally { $stream.Dispose() }
    }
}

process {
    # --- resolve input ------------------------------------------------------
    $resolved = Resolve-Path -Path $Path -ErrorAction SilentlyContinue
    if (-not $resolved) {
        throw "CSV not found: '$Path'. Current directory is '$($PWD.ProviderPath)'. Use a full path, e.g. C:\data\input.csv"
    }
    if (@($resolved).Count -gt 1) {
        throw "'$Path' matched multiple files. Pass a single file."
    }
    $csvPath = $resolved.ProviderPath

    if (Test-Path -LiteralPath $csvPath -PathType Container) {
        throw "'$csvPath' is a folder, not a CSV file."
    }

    # --- resolve output -----------------------------------------------------
    if (-not (Test-Path -LiteralPath $OutputDirectory)) {
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    }
    $outDir = (Resolve-Path -LiteralPath $OutputDirectory).ProviderPath

    # --- parse --------------------------------------------------------------
    try {
        $text = Read-CsvText -LiteralPath $csvPath
    }
    catch [System.UnauthorizedAccessException] {
        throw "No permission to read '$csvPath'. Check the file's permissions or run as a user that can read it."
    }
    catch {
        throw "Could not open '$csvPath': $($_.Exception.Message)"
    }

    $lines = $text -split "\r?\n"
    $rows  = @(ConvertFrom-Csv -InputObject $lines -Delimiter $Delimiter)

    if ($rows.Count -eq 0) {
        throw "'$csvPath' contains a header but no data rows."
    }

    $columns = @($rows[0].PSObject.Properties.Name)
    if ($columns.Count -lt 2) {
        throw "'$csvPath' parsed as a single column. If it uses semicolons, re-run with -Delimiter ';'"
    }

    $attributeColumn = $columns[0]
    $recordColumns   = $columns[1..($columns.Count - 1)]

    # --- emit ---------------------------------------------------------------
    foreach ($record in $recordColumns) {
        $data = [ordered]@{}

        foreach ($row in $rows) {
            $attribute = [string]$row.$attributeColumn
            if ([string]::IsNullOrWhiteSpace($attribute)) { continue }   # skip blank/spacer rows
            $attribute = $attribute.Trim()

            if ($data.Contains($attribute)) {
                Write-Warning "Duplicate attribute '$attribute' in '$record' - keeping the last value."
            }
            $data[$attribute] = $row.$record
        }

        $fileName = (ConvertTo-SafeFileName -Name $record) + '.json'
        $filePath = Join-Path $outDir $fileName

        if ((Test-Path -LiteralPath $filePath) -and -not $Force) {
            Write-Warning "Skipping '$fileName' - it already exists. Use -Force to overwrite."
            continue
        }

        $json = $data | ConvertTo-Json -Depth $Depth
        [System.IO.File]::WriteAllText($filePath, $json, $script:Utf8NoBom)

        Write-Verbose "Wrote $filePath"
        [pscustomobject]@{
            Record     = $record
            Path       = $filePath
            Attributes = $data.Count
        }
    }
}
