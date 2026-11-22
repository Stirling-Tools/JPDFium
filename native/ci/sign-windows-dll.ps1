param(
  [Parameter(Mandatory = $true, Position = 0)][string]$Path
)

$ErrorActionPreference = "Stop"

function Test-Signed([string]$file) {
  try { (Get-AuthenticodeSignature -LiteralPath $file).Status -eq "Valid" } catch { $false }
}

$alias = $env:SM_KEYPAIR_ALIAS
if (-not $alias) { throw "SM_KEYPAIR_ALIAS is not set" }

$work = Join-Path ([IO.Path]::GetTempPath()) ("jpdfium-sign-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $work | Out-Null
try {
  $temp = Join-Path $work ("library" + [IO.Path]::GetExtension($Path))
  Copy-Item -LiteralPath $Path -Destination $temp -Force
  Write-Host "Signing $Path"
  & smctl sign --keypair-alias $alias --input $temp --verbose
  if ($LASTEXITCODE -ne 0) { throw "smctl sign exited $LASTEXITCODE for $Path" }
  if (-not (Test-Signed $temp)) { throw "smctl reported success but $Path is not signed" }
  Copy-Item -LiteralPath $temp -Destination $Path -Force
  if (-not (Test-Signed $Path)) { throw "Signature lost copying back $Path" }
} finally {
  Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
