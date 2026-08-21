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

    # 'Auto' sniffs the header row for , ; tab or | . Pass an explicit
    # character to override, e.g. -Delimiter ';'
    [string]$Delimiter = 'Auto',

    [int]$Depth = 10,

    # Text inserted where a stray line break is removed. Default '' (glue the
    # fragments together). Use ' ' if the break replaced a space.
    [string]$JoinWith = '',

    # Optional: write the repaired CSV here so you can inspect what was fixed.
    [string]$SaveNormalizedCsv,

    # Overwrite existing .json files instead of skipping them.
    [switch]$Force
)

begin {
    Set-StrictMode -Version Latest
    $ErrorActionPreference = 'Stop'

    $script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

    # Quote-aware scan of a partial CSV record. Returns how many fields it
    # holds so far and whether it ends inside an open quoted field.
    function Get-CsvState {
        param([string]$Text, [char]$Separator)

        $inQuotes = $false
        $fields   = 1
        $i        = 0

        while ($i -lt $Text.Length) {
            $c = $Text[$i]
            if ($inQuotes) {
                if ($c -eq '"') {
                    if (($i + 1) -lt $Text.Length -and $Text[$i + 1] -eq '"') { $i++ }  # escaped ""
                    else { $inQuotes = $false }
                }
            }
            else {
                if     ($c -eq '"')        { $inQuotes = $true }
                elseif ($c -eq $Separator) { $fields++ }
            }
            $i++
        }

        return [pscustomobject]@{ Fields = $fields; InQuotes = $inQuotes }
    }

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

    $rawLines = @($text -split "\r?\n")
    while ($rawLines.Count -gt 0 -and $rawLines[-1].Trim().Length -eq 0) {
        $rawLines = @($rawLines[0..($rawLines.Count - 2)])
    }
    if ($rawLines.Count -lt 2) {
        throw "'$csvPath' has fewer than two non-empty lines - nothing to convert."
    }

    $header = $rawLines[0]

    if ($Delimiter -eq 'Auto') {
        $candidates = @(
            [pscustomobject]@{ Char = ',';   Count = ([regex]::Matches($header, ',')).Count }
            [pscustomobject]@{ Char = ';';   Count = ([regex]::Matches($header, ';')).Count }
            [pscustomobject]@{ Char = "`t";  Count = ([regex]::Matches($header, "`t")).Count }
            [pscustomobject]@{ Char = '|';   Count = ([regex]::Matches($header, '\|')).Count }
        )
        $best = $candidates | Sort-Object Count -Descending | Select-Object -First 1
        if ($best.Count -eq 0) {
            throw "Could not detect a delimiter in the header line of '$csvPath'.`nHeader was: $header`nPass one explicitly, e.g. -Delimiter ';'"
        }
        $sep = [char]$best.Char
        Write-Verbose ("Auto-detected delimiter: '{0}'" -f $sep)
    }
    else {
        if ($Delimiter.Length -ne 1) {
            throw "-Delimiter must be a single character (or 'Auto'). Got: '$Delimiter'"
        }
        $sep = [char]$Delimiter
    }

    # --- normalize: repair records broken across physical lines ---------------
    $expectedFields = (Get-CsvState -Text $header -Separator $sep).Fields
    if ($expectedFields -lt 2) {
        throw "'$csvPath' header parsed as a single column using delimiter '$sep'.`nHeader line was: $header"
    }

    $lines   = New-Object System.Collections.Generic.List[string]
    $repairs = 0
    $buffer  = $null

    foreach ($line in $rawLines) {
        if ($null -eq $buffer) {
            if ($line.Trim().Length -eq 0) { continue }   # blank line between records
            $buffer = $line
        }
        else {
            $state = Get-CsvState -Text $buffer -Separator $sep
            # A newline inside a quoted field is legitimate CSV content - keep it.
            # A newline in an unterminated record is Confluence damage - remove it.
            $buffer = if ($state.InQuotes) { $buffer + "`n" + $line } else { $buffer + $JoinWith + $line }
            $repairs++
        }

        $state = Get-CsvState -Text $buffer -Separator $sep
        if (-not $state.InQuotes -and $state.Fields -ge $expectedFields) {
            if ($state.Fields -gt $expectedFields) {
                Write-Warning ("Record starting '{0}' has {1} fields, expected {2}." -f `
                    $buffer.Substring(0, [Math]::Min(40, $buffer.Length)), $state.Fields, $expectedFields)
            }
            $lines.Add($buffer)
            $buffer = $null
        }
    }

    if ($null -ne $buffer) {
        Write-Warning "Last record is incomplete (fewer fields than the header). Keeping it as-is."
        $lines.Add($buffer)
    }

    if ($repairs -gt 0) {
        Write-Verbose "Repaired $repairs stray line break(s)."
    }

    if ($PSBoundParameters.ContainsKey('SaveNormalizedCsv')) {
        [System.IO.File]::WriteAllText($SaveNormalizedCsv, ($lines -join "`r`n"), $script:Utf8NoBom)
        Write-Verbose "Wrote normalized CSV to $SaveNormalizedCsv"
    }

    $rows = @(ConvertFrom-Csv -InputObject $lines.ToArray() -Delimiter $sep)

    if ($rows.Count -eq 0) {
        throw "'$csvPath' contains a header but no data rows."
    }

    $columns = @($rows[0].PSObject.Properties.Name)
    if ($columns.Count -lt 2) {
        throw "'$csvPath' parsed as a single column using delimiter '$sep'.`nHeader line was: $header"
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
