# ServerDoctor v1.1.2: Windows build with local Maven bootstrap and full build.log.
# Downloads official Apache Maven only when 'mvn' is not installed.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$log = Join-Path $PSScriptRoot 'build.log'
$mavenVersion = '3.9.16'
$binName = "apache-maven-$mavenVersion-bin.zip"
$toolsDirectory = Join-Path $PSScriptRoot '.build-tools'
$mavenDirectory = Join-Path $toolsDirectory "apache-maven-$mavenVersion"
$mavenCmd = Join-Path $mavenDirectory 'bin\mvn.cmd'

function Fail([string]$message) {
    Write-Host "[ERROR] $message" -ForegroundColor Red
    Add-Content -LiteralPath $log -Value "`n[BUILD SCRIPT ERROR] $message" -Encoding UTF8
    exit 1
}

try {
    "ServerDoctor v1.1.2 build - $(Get-Date -Format o)" | Set-Content -LiteralPath $log -Encoding UTF8
    Write-Host 'ServerDoctor v1.1.2 - build from official dependencies' -ForegroundColor Cyan

    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if (-not $java -or -not $javac) {
        Fail 'JDK 21+ is not on PATH. Install a JDK (not just JRE): https://adoptium.net/temurin/releases/?version=21'
    }
    # Unlike java -version, javac prints its version to stdout.
    $compilerVersion = (& $javac.Source -version 2>&1 | Out-String).Trim()
    $versionMatch = [regex]::Match($compilerVersion, '(?:javac\s+)(\d+)')
    if (-not $versionMatch.Success) {
        Fail "Cannot determine JDK version: $compilerVersion"
    }
    $major = [int]$versionMatch.Groups[1].Value
    if ($major -lt 21) {
        Fail "Found JDK $major. ServerDoctor requires JDK 21 or newer."
    }
    Write-Host "[OK] $compilerVersion" -ForegroundColor Green
    Add-Content -LiteralPath $log -Value "JDK: $compilerVersion"

    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mvn) { $mvn = Get-Command mvn -ErrorAction SilentlyContinue }
    if ($mvn) {
        $mavenCmd = $mvn.Source
        Write-Host "[OK] System Maven: $mavenCmd" -ForegroundColor Green
    } elseif (Test-Path -LiteralPath $mavenCmd) {
        Write-Host "[OK] Cached Maven $mavenVersion" -ForegroundColor Green
    } else {
        Write-Host "[INFO] Maven not found. Downloading Apache Maven $mavenVersion (one-time)..." -ForegroundColor Yellow
        New-Item -ItemType Directory -Path $toolsDirectory -Force | Out-Null
        $download = Join-Path $toolsDirectory $binName
        $base = "https://dlcdn.apache.org/maven/maven-3/$mavenVersion/binaries/"
        $checksumUrl = $base + $binName + '.sha512'
        $url = $base + $binName
        try {
            # Official Apache HTTPS distribution and SHA-512 integrity check.
            # If the active mirror is temporarily unavailable, use Apache's official archive.
            $archiveBase = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/"
            try {
                Invoke-WebRequest -Uri $url -OutFile $download -UseBasicParsing -MaximumRedirection 5
                $shaContent = (Invoke-WebRequest -Uri $checksumUrl -UseBasicParsing).Content.Trim()
            } catch {
                Write-Host '[INFO] Apache mirror unavailable, using official Apache archive.' -ForegroundColor Yellow
                Invoke-WebRequest -Uri ($archiveBase + $binName) -OutFile $download -UseBasicParsing -MaximumRedirection 5
                $shaContent = (Invoke-WebRequest -Uri ($archiveBase + $binName + '.sha512') -UseBasicParsing).Content.Trim()
            }
            $shaExpected = ([regex]::Match($shaContent, '[a-fA-F0-9]{128}')).Value.ToLowerInvariant()
            if ($shaExpected.Length -ne 128) { Fail 'Cannot parse official Maven SHA-512 file.' }
            $shaActual = (Get-FileHash -LiteralPath $download -Algorithm SHA512).Hash.ToLowerInvariant()
            if ($shaActual -ne $shaExpected) { Fail 'Maven archive SHA-512 mismatch; refusing to use it.' }
            Expand-Archive -LiteralPath $download -DestinationPath $toolsDirectory -Force
            Remove-Item -LiteralPath $download -Force
        } catch {
            Fail "Could not download/verify Apache Maven: $($_.Exception.Message). Check internet access or install Maven manually: https://maven.apache.org/download.cgi"
        }
        if (-not (Test-Path -LiteralPath $mavenCmd)) { Fail 'Maven extraction failed.' }
        Write-Host "[OK] Maven $mavenVersion downloaded and verified." -ForegroundColor Green
    }

    Write-Host '[INFO] Running Maven clean verify. Initial run requires internet for Paper API and test dependencies.' -ForegroundColor Cyan
    # Windows PowerShell 5.1 sometimes wraps Maven stderr warnings as NativeCommandError.
    # Merge stdout/stderr inside cmd.exe first, so they are plain text for Tee-Object.
    $mavenCommand = 'call "' + $mavenCmd + '" -B -ntp clean verify 2>&1'
    & $env:ComSpec /d /c $mavenCommand | Tee-Object -FilePath $log -Append
    $result = $LASTEXITCODE
    if ($result -ne 0) {
        Write-Host '[FAILED] Compilation or dependency resolution failed. Send build.log for diagnosis.' -ForegroundColor Red
        exit 1
    }
    $jar = Join-Path $PSScriptRoot 'target\ServerDoctor-v1.1.2.jar'
    if (-not (Test-Path -LiteralPath $jar)) { Fail 'Maven returned success but output JAR was not found.' }
    $size = (Get-Item -LiteralPath $jar).Length
    if ($size -lt 10000) { Fail 'Generated JAR looks unexpectedly small; do not deploy.' }
    Write-Host "[SUCCESS] $jar" -ForegroundColor Green
    exit 0
} catch {
    Fail "Unexpected build error: $($_.Exception.Message)"
}
