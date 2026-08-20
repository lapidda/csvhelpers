
Cloud





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
 
    [System.Text.Encoding]$Encoding = [System.Text.UTF8Encoding]::new($false),
 
    [int]$Depth = 10,
 
    # Overwrite existing .json files instead of failing.
    [switch]$Force
)
 
begin {
    Set-StrictMode -Version Latest
    $ErrorActionPreference = 'Stop'
 
    function ConvertTo-SafeFileName {
        param([string]$Name)
        $invalid = [System.IO.Path]::GetInvalidFileNameChars() -join ''
        $pattern = '[{0}]' -f [regex]::Escape($invalid)
        $safe = ($Name -replace $pattern, '_').Trim()
        if ([string]::IsNullOrWhiteSpace($safe)) { $safe = 'unnamed' }
        return $safe
    }
}
 
process {
    $csvPath = (Resolve-Path -LiteralPath $Path).ProviderPath
 
    if (-not (Test-Path -LiteralPath $OutputDirectory)) {
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    }
    $outDir = (Resolve-Path -LiteralPath $OutputDirectory).ProviderPath
 
    $rows = @(Import-Csv -LiteralPath $csvPath -Delimiter $Delimiter)
    if ($rows.Count -eq 0) {
        throw "CSV '$csvPath' contains a header but no data rows."
    }
 
    # Header names, in file order.
    $columns = @($rows[0].PSObject.Properties.Name)
    if ($columns.Count -lt 2) {
        throw "CSV '$csvPath' needs at least two columns (attribute names + one record)."
    }
 
    $attributeColumn = $columns[0]
    $recordColumns   = $columns[1..($columns.Count - 1)]
 
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
        [System.IO.File]::WriteAllText($filePath, $json, $Encoding)
 
        Write-Verbose "Wrote $filePath"
        [pscustomobject]@{
            Record     = $record
            Path       = $filePath
            Attributes = $data.Count
        }
    }
}
 


Unable to open file.
