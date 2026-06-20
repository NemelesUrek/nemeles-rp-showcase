# Construye el resource pack COMBINADO (telefono NemelesPhone + texturas de items NemelesRP) en un solo .zip,
# lo deja en la carpeta web del servidor y devuelve su SHA-1. ASCII puro, usa APIs .NET (sin Remove-Item de literales).
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$rp       = "$PSScriptRoot"
$phoneZip = Join-Path $rp "NemelesPhone.zip"
$myAssets = Join-Path $rp "NemelesRP\assets"
$stage    = Join-Path $rp "NemelesRP_Combined"
$webDir   = "$env:NEMELES_SERVER\webpack"
$outZip   = Join-Path $webDir "NemelesRP.zip"

# 1) Recrear staging limpio (Directory.Delete recursivo via .NET, no Remove-Item)
if ([System.IO.Directory]::Exists($stage)) { [System.IO.Directory]::Delete($stage, $true) }
[void][System.IO.Directory]::CreateDirectory($stage)

# 2) Extraer el pack del telefono dentro del staging
[System.IO.Compression.ZipFile]::ExtractToDirectory($phoneZip, $stage)
Write-Output "Extraido NemelesPhone.zip en staging"

# 3) Fusionar MIS assets (items/armas/drogas) sobre el staging (sin colisiones: subcarpetas distintas)
$dstAssets = Join-Path $stage "assets"
[void][System.IO.Directory]::CreateDirectory($dstAssets)
$prefixLen = $myAssets.Length
$copied = 0
Get-ChildItem -Path $myAssets -Recurse -File | ForEach-Object {
  $rel = $_.FullName.Substring($prefixLen)            # p.ej. \minecraft\items\paper.json
  $dst = $dstAssets + $rel
  $dstDir = [System.IO.Path]::GetDirectoryName($dst)
  [void][System.IO.Directory]::CreateDirectory($dstDir)
  [System.IO.File]::Copy($_.FullName, $dst, $true)
  $copied++
}
Write-Output "Fusionados $copied archivos de NemelesRP\assets"

# 3.5) Fusionar el resource pack de BetterModel (build.zip) -> assets\bettermodel (namespace propio: NO colisiona con minecraft).
#      Aplanamos el overlay 'bettermodel_modern' (formato 46-99, el activo en 1.21.11) sobre los 6 assets base; ignoramos 'bettermodel_legacy'.
#      Sin esto, los modelos 3D (deagle, etc.) NO se ven en clientes JAVA (BetterModel no autosirve su pack en 2.2.0).
$bmBuildZip = "$env:NEMELES_SERVER\plugins\BetterModel\build.zip"
if ([System.IO.File]::Exists($bmBuildZip)) {
  $bmz = [System.IO.Compression.ZipFile]::OpenRead($bmBuildZip)
  $bmBase = 0; $bmModern = 0
  # Pasada 1: assets/ base (6 texturas raiz)
  foreach ($en in $bmz.Entries) {
    $n = $en.FullName
    if ($n.EndsWith("/")) { continue }
    if ($n.StartsWith("assets/")) {
      $dst = $dstAssets + "\" + $n.Substring(7).Replace("/", "\")
      [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($dst))
      $st = $en.Open(); $out = [System.IO.File]::Create($dst); $st.CopyTo($out); $out.Dispose(); $st.Dispose()
      $bmBase++
    }
  }
  # Pasada 2: bettermodel_modern/assets/ (gana sobre el base; es el overlay activo en 1.21.11)
  foreach ($en in $bmz.Entries) {
    $n = $en.FullName
    if ($n.EndsWith("/")) { continue }
    if ($n.StartsWith("bettermodel_modern/assets/")) {
      $dst = $dstAssets + "\" + $n.Substring(26).Replace("/", "\")
      [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($dst))
      $st = $en.Open(); $out = [System.IO.File]::Create($dst); $st.CopyTo($out); $out.Dispose(); $st.Dispose()
      $bmModern++
    }
  }
  $bmz.Dispose()
  Write-Output "Fusionado BetterModel build.zip: $bmBase base + $bmModern modern -> assets\bettermodel"
} else {
  Write-Output "AVISO: no existe build.zip de BetterModel -> el pack saldra SIN modelos 3D para Java. Arranca el server 1 vez para generarlo."
}

# 4) pack.mcmeta combinado (formato 1.21.11 con rango amplio de compatibilidad)
$mcmeta = '{' + "`n" +
  '  "pack": {' + "`n" +
  '    "pack_format": 75,' + "`n" +
  '    "supported_formats": { "min_inclusive": 46, "max_inclusive": 99 },' + "`n" +
  '    "description": "NemelesRP - telefono + texturas (armas, drogas, DNI, llave, botiquin). Crossplay Java."' + "`n" +
  '  }' + "`n" + '}' + "`n"
[System.IO.File]::WriteAllText((Join-Path $stage "pack.mcmeta"), $mcmeta, (New-Object System.Text.UTF8Encoding($false)))

# 5) Empaquetar staging -> outZip con ENTRADAS DE BARRA NORMAL (/). OJO: ZipFile.CreateFromDirectory en
#    .NET Framework (PowerShell 5.1) guarda las rutas con '\' y Minecraft NO las lee. Por eso creamos el
#    zip a mano forzando '/'.
[void][System.IO.Directory]::CreateDirectory($webDir)
if ([System.IO.File]::Exists($outZip)) { [System.IO.File]::Delete($outZip) }
$fs = [System.IO.File]::Open($outZip, [System.IO.FileMode]::CreateNew)
$arch = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
$stageLen = $stage.Length + 1
$entries = 0
Get-ChildItem -Path $stage -Recurse -File | ForEach-Object {
  $rel = $_.FullName.Substring($stageLen).Replace('\','/')   # FORWARD slashes, siempre
  $entry = $arch.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
  $es = $entry.Open()
  $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
  $es.Write($bytes, 0, $bytes.Length)
  $es.Dispose()
  $entries++
}
$arch.Dispose()
$fs.Dispose()
$size = (Get-Item $outZip).Length
Write-Output "Empaquetado: $outZip ($entries entradas con '/', $size bytes)"

# 6) SHA-1 (lo necesita server.properties)
$sha = (Get-FileHash $outZip -Algorithm SHA1).Hash.ToLower()
[System.IO.File]::WriteAllText((Join-Path $webDir "sha1.txt"), $sha, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "SHA1=$sha"

# 7) Sincronizar resource-pack-sha1 en server.properties (DEBE coincidir con el zip servido o el cliente rechaza el pack).
#    OJO: server.properties se lee al ARRANCAR -> tras esto hay que reiniciar el server para que aplique el sha1 nuevo.
$serverProps = "$env:NEMELES_SERVER\server.properties"
if ([System.IO.File]::Exists($serverProps)) {
  $lines = [System.IO.File]::ReadAllLines($serverProps)
  $found = $false
  for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -like "resource-pack-sha1=*") { $lines[$i] = "resource-pack-sha1=$sha"; $found = $true }
  }
  if ($found) {
    [System.IO.File]::WriteAllLines($serverProps, $lines)
    Write-Output "server.properties: resource-pack-sha1 -> $sha (REINICIAR server para aplicar)"
  } else {
    Write-Output "AVISO: no se encontro 'resource-pack-sha1=' en server.properties"
  }
}
Write-Output "OK"
