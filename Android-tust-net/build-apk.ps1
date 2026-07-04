param(
    [string]$AndroidSdk = "D:\android_studio\Sdk",
    [string]$JavaHome = "D:\android_studio\install\jbr"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppRoot = Join-Path $ProjectRoot "app"
$BuildRoot = Join-Path $ProjectRoot "manual-build"
$OutDir = Join-Path $ProjectRoot "output"
$SigningDir = Join-Path $ProjectRoot "signing"

$BuildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk "build-tools") -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
$Platform = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk "platforms") -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $BuildTools) { throw "Android build-tools not found under $AndroidSdk" }
if (-not $Platform) { throw "Android platform not found under $AndroidSdk" }

$Aapt2 = Join-Path $BuildTools.FullName "aapt2.exe"
$D8 = Join-Path $BuildTools.FullName "d8.bat"
$Zipalign = Join-Path $BuildTools.FullName "zipalign.exe"
$ApkSigner = Join-Path $BuildTools.FullName "apksigner.bat"
$AndroidJar = Join-Path $Platform.FullName "android.jar"
$Javac = Join-Path $JavaHome "bin\javac.exe"
$Jar = Join-Path $JavaHome "bin\jar.exe"
$Keytool = Join-Path $JavaHome "bin\keytool.exe"

foreach ($Tool in @($Aapt2, $D8, $Zipalign, $ApkSigner, $AndroidJar, $Javac, $Jar, $Keytool)) {
    if (-not (Test-Path -LiteralPath $Tool)) { throw "Missing required tool: $Tool" }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome "bin") + ";" + $env:PATH

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE`: $FilePath $($Arguments -join ' ')"
    }
}

if (Test-Path -LiteralPath $BuildRoot) {
    Remove-Item -LiteralPath $BuildRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BuildRoot, $OutDir | Out-Null

$ResZip = Join-Path $BuildRoot "resources.zip"
$GenDir = Join-Path $BuildRoot "gen"
$ClassesDir = Join-Path $BuildRoot "classes"
$ClassesJar = Join-Path $BuildRoot "classes.jar"
$DexDir = Join-Path $BuildRoot "dex"
$UnsignedResApk = Join-Path $BuildRoot "resources.apk"
$UnsignedApk = Join-Path $BuildRoot "campus-login-unsigned.apk"
$AlignedApk = Join-Path $BuildRoot "campus-login-aligned.apk"
$FinalApk = Join-Path $OutDir "campus-net-login.apk"
$Keystore = Join-Path $SigningDir "campus-net-login-debug.keystore"

New-Item -ItemType Directory -Force -Path $GenDir, $ClassesDir, $DexDir, $SigningDir | Out-Null

Invoke-Checked $Aapt2 @("compile", "--dir", (Join-Path $AppRoot "src\main\res"), "-o", $ResZip)
Invoke-Checked $Aapt2 @(
    "link",
    "-I", $AndroidJar,
    "--manifest", (Join-Path $AppRoot "src\main\AndroidManifest.xml"),
    "--java", $GenDir,
    "--min-sdk-version", "23",
    "--target-sdk-version", "35",
    "-o", $UnsignedResApk,
    $ResZip
)

$Sources = @()
$Sources += Get-ChildItem -LiteralPath (Join-Path $AppRoot "src\main\java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$Sources += Get-ChildItem -LiteralPath $GenDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

Invoke-Checked $Javac (@("-encoding", "UTF-8", "-source", "8", "-target", "8", "-classpath", $AndroidJar, "-d", $ClassesDir) + $Sources)
Invoke-Checked $Jar @("cf", $ClassesJar, "-C", $ClassesDir, ".")
Invoke-Checked $D8 @("--min-api", "23", "--lib", $AndroidJar, "--output", $DexDir, $ClassesJar)

Copy-Item -LiteralPath $UnsignedResApk -Destination $UnsignedApk -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::Open($UnsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $Existing = $Zip.GetEntry("classes.dex")
    if ($Existing) { $Existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $Zip,
        (Join-Path $DexDir "classes.dex"),
        "classes.dex",
        [System.IO.Compression.CompressionLevel]::Optimal
    ) | Out-Null
}
finally {
    $Zip.Dispose()
}

Invoke-Checked $Zipalign @("-f", "-p", "4", $UnsignedApk, $AlignedApk)

if (-not (Test-Path -LiteralPath $Keystore)) {
    Invoke-Checked $Keytool @(
        "-genkeypair",
        "-v",
        "-keystore", $Keystore,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Campus Net Login Debug,O=Codex,C=CN"
    )
}

Invoke-Checked $ApkSigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--out", $FinalApk,
    $AlignedApk
)

Invoke-Checked $ApkSigner @("verify", "--verbose", $FinalApk)
Get-Item -LiteralPath $FinalApk
