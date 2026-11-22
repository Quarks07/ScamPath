param(
    [string] $Adb = 'adb',
    [string] $Serial,
    [string] $ModelDir = (Join-Path $PSScriptRoot 'model')
)
$ErrorActionPreference = 'Stop'
$repository = 'https://huggingface.co/experimentalmachines/Llama-3.2-1B-Instruct-ExecuTorch'
$revision = '08c5ee382572104634ed2e7c4d51e057c7184dd8'
$package = 'org.scampath.app'
$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }

function Invoke-Device {
    & $Adb @adbArgs @args
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed ($LASTEXITCODE)." }
}

function Get-VerifiedFile($Name, $RemoteName, $Sha256) {
    $file = Join-Path $ModelDir $Name
    if ((Test-Path -LiteralPath $file) -and (Get-FileHash -LiteralPath $file).Hash -eq $Sha256) {
        return (Resolve-Path -LiteralPath $file).Path
    }
    $temporary = "$file.part"
    & curl.exe -fLsS --retry 2 --max-time 600 -o $temporary "$repository/resolve/$revision/$RemoteName"
    if ($LASTEXITCODE -ne 0) { throw "Download failed for $Name." }
    if ((Get-FileHash -LiteralPath $temporary).Hash -ne $Sha256) { throw "SHA-256 mismatch for $Name." }
    Move-Item -LiteralPath $temporary -Destination $file -Force
    return (Resolve-Path -LiteralPath $file).Path
}

Invoke-Device get-state
Invoke-Device shell run-as $package mkdir -p files
New-Item -ItemType Directory -Path $ModelDir -Force | Out-Null

$artifacts = @(
    @{
        Name = 'tokenizer.json'
        Remote = 'tokenizer.json'
        Sha = '79E3E522635F3171300913BB421464A87DE6222182A0570B9B2CCBA2A964B2B4'
    },
    @{
        Name = 'model.pte'
        Remote = 'xnnpack/Llama-3.2-1B-Instruct-8da4w-gptq-2k.pte'
        Sha = '0D02FF73799BDA072EA48717909108CBDD3F416B73C5B0B7BA9FC3644510EEA2'
    }
)
foreach ($artifact in $artifacts) {
    $file = Get-VerifiedFile $artifact.Name $artifact.Remote $artifact.Sha
    $transfer = '/data/local/tmp/scampath-' + [guid]::NewGuid().ToString('N')
    $destination = 'files/' + $artifact.Name
    try {
        Invoke-Device push $file $transfer
        Invoke-Device shell "cat '$transfer' | run-as '$package' tee '$destination.part' >/dev/null"
        $actual = (Invoke-Device shell run-as $package sha256sum "$destination.part") -split '\s+'
        if ($actual[0] -ne $artifact.Sha) { throw "Device SHA-256 mismatch for $($artifact.Name)." }
        Invoke-Device shell run-as $package mv "$destination.part" $destination
    } finally {
        Invoke-Device shell rm -f $transfer
    }
}
Write-Output 'Model and tokenizer verified in the ScamPath app directory.'
