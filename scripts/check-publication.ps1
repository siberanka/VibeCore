[CmdletBinding()]
param(
    [switch]$Tracked,
    [string]$DenyListPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function ConvertTo-NativeArgument {
    param([AllowEmptyString()][string]$Value)

    $escaped = [regex]::Replace($Value, '(\\*)("|$)', {
        param($match)
        $slashes = $match.Groups[1].Value
        if ($match.Groups[2].Value -eq '"') {
            return $slashes + $slashes + '\"'
        }
        return $slashes + $slashes
    })
    return '"' + $escaped + '"'
}

function Invoke-LocalGit {
    param([string[]]$GitArguments)

    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $script:GitExecutable
    $start.Arguments = (($GitArguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' ')
    $start.WorkingDirectory = $script:RepositoryRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $start.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'Git could not be started.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill()
            throw 'A local Git inspection exceeded 30 seconds.'
        }
        $outputText = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw ('Git inspection failed: ' + $errorText.Trim())
        }
        return $outputText
    }
    finally {
        $process.Dispose()
    }
}

try {
    $script:RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
    $script:GitExecutable = (Get-Command git -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Source
    if ([string]::IsNullOrWhiteSpace($DenyListPath)) {
        $DenyListPath = Join-Path $script:RepositoryRoot 'ai/publication-denylist.txt'
    }
    if (-not (Test-Path -LiteralPath $DenyListPath -PathType Leaf)) {
        throw 'A local publication deny list is required. Supply -DenyListPath with a UTF-8 text file.'
    }
    $terms = @(Get-Content -LiteralPath $DenyListPath -Encoding UTF8 |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -and -not $_.StartsWith('#') } |
        Select-Object -Unique)
    if ($terms.Count -eq 0) { throw 'The publication deny list must contain at least one term.' }
    if (@($terms | Where-Object { $_.Length -lt 3 }).Count -gt 0) {
        throw 'Every deny-list term must contain at least three characters.'
    }
    $actualRoot = (Invoke-LocalGit @('rev-parse', '--show-toplevel')).Trim()
    if (-not [string]::Equals([IO.Path]::GetFullPath($actualRoot), $script:RepositoryRoot,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Run this check from its own repository, not an enclosing repository.'
    }

    $arguments = if ($Tracked) {
        @('ls-files', '--cached', '-z')
    }
    else {
        @('diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z')
    }
    $paths = @((Invoke-LocalGit $arguments).Split([char]0) | Where-Object { $_ })
    if ($paths.Count -eq 0) {
        throw 'No candidate files were found. Stage the intended files, or use -Tracked for a full index check.'
    }

    $blockedPaths = '(?i)(?:(^|/)(?:ai|\.resources|\.reources|\.resource|graphify-out|target|build|run|servers?|backups)(?:/|$)|^world[^/]*/)'
    $secretPaths = '(?i)(^|/)(?:\.env(?:\..*)?|[^/]*(?:credentials|service-account)[^/]*\.json|[^/]+\.(?:pem|key|p12|pfx|jks|keystore))$'
    $generatedPaths = '(?i)\.(?:class|jar|war|log|bak)$'
    $pipelinePaths = '(?i)(?:^\.github/workflows/|(?:^|/)\.gitlab-ci(?:\.[^/]+)?\.ya?ml$)'
    $contentRules = @{
        'private-key material' = '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'
        'cloud access-key identifier' = '\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'
        'hosting access token' = '\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|glpat-[A-Za-z0-9_-]{20,})\b'
        'API secret token' = '\bsk-(?:proj-)?[A-Za-z0-9_-]{24,}\b'
        'credential assignment' = '(?im)^\s*(?:password|passwd|api[_-]?key|access[_-]?token|secret)\s*[:=]\s*["'']?[^\s"'']{8,}'
        'private working marker' = '(?i)\b(?:INTERNAL[ -]ONLY|DO[ -]NOT[ -]PUBLISH|PRIVATE[ -]RESEARCH)\b'
        'unapproved attribution' = '(?i)\b(?:Chat(?:GPT)|Open(?:AI)|Anthro(?:pic)|AI[- ](?:assisted|generated)|Co-authored-by:\s*(?:Codex|Copilot))\b'
    }
    $failures = New-Object 'System.Collections.Generic.List[string]'
    [long]$totalBytes = 0
    foreach ($path in $paths) {
        $normalized = $path.Replace('\', '/')
        if ($normalized -match $blockedPaths -or $normalized -match $secretPaths -or
                $normalized -match $generatedPaths -or $normalized -match $pipelinePaths) {
            $failures.Add("Blocked path: $normalized")
            continue
        }
        $entry = (Invoke-LocalGit @('ls-files', '--stage', '--', $path)).Trim()
        if ($entry -notmatch '^100(?:644|755) [a-f0-9]+ 0\s') {
            $failures.Add("Unsupported index entry (symlink, submodule or conflict): $normalized")
            continue
        }
        $objectName = ':' + $path
        [long]$size = (Invoke-LocalGit @('cat-file', '-s', $objectName)).Trim()
        $totalBytes += $size
        if ($size -gt 2097152 -or $totalBytes -gt 16777216) {
            $failures.Add("Content exceeds the bounded review size: $normalized")
            continue
        }
        $content = Invoke-LocalGit @('cat-file', 'blob', $objectName)
        foreach ($term in $terms) {
            if ($normalized.IndexOf($term, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                    $content.IndexOf($term, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $failures.Add("Publication deny-list match: $normalized")
                break
            }
        }
        foreach ($rule in $contentRules.GetEnumerator()) {
            if ($content -match $rule.Value) {
                $failures.Add("$($rule.Key): $normalized")
            }
        }
    }
    if ($failures.Count -gt 0) {
        foreach ($failure in $failures) { Write-Host $failure -ForegroundColor Red }
        throw "Publication review failed with $($failures.Count) blocked observation(s)."
    }
    $scope = if ($Tracked) { 'tracked index' } else { 'staged changes' }
    Write-Host "Publication check passed: $($paths.Count) files in $scope; $totalBytes bytes reviewed."
    Write-Host 'Review the actual archive and history before publishing. This check does not publish anything.'
}
catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
