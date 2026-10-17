[CmdletBinding()]
param(
    [string]$Uri = 'http://127.0.0.1:3000',
    [string]$Database = 'soupkitzhot',
    [Parameter(Mandatory = $true)] [string]$TargetUuid,
    [string]$ServerId = 'soupkitz-01',
    [string]$WorldId = 'soupkitz'
)

$ErrorActionPreference = 'Stop'
$base = "$($Uri.TrimEnd('/'))/v1/database/$Database"
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$key = "fabricdbug-$stamp-$([guid]::NewGuid().ToString('N'))"

function Invoke-Hot([string]$Method, [string]$Path, $Body, [string]$ContentType) {
    $params = @{ Method = $Method; Uri = "$base$Path"; TimeoutSec = 10 }
    if ($null -ne $Body) {
        $params.Body = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 12 }
        $params.ContentType = $ContentType
    }
    Invoke-WebRequest @params
}

try {
    $health = Invoke-WebRequest -Method Get -Uri "$($Uri.TrimEnd('/'))/v1/health" -TimeoutSec 10
    if ($health.StatusCode -ge 300) { throw "Hot health returned HTTP $($health.StatusCode)" }

    $sql = Invoke-Hot 'Post' '/sql' 'SELECT id, target_uuid, intent_type, payload FROM command_intent' 'text/plain'
    if ($sql.StatusCode -ge 300) { throw "Hot SQL returned HTTP $($sql.StatusCode)" }

    # Custom intent 11 is the FabricDBug control lane. The client consumes it
    # through SQL and executes ATTACK/USE locally on the Minecraft tick thread.
    $args = @(
        $key, [UInt64]1, [UInt64]0, '', [UInt64]1, $ServerId, $WorldId,
        'fabricdbug-probe', [byte]11, [UInt64]0, $TargetUuid, [Int64]0, [Int64]0,
        '{"action":"run_script","steps":[{"ticks":1,"keys":["ATTACK"]},{"ticks":1,"keys":["USE"]}]}', [UInt64]1
    )
    $call = Invoke-Hot 'Post' '/call/command_intent_execute' $args 'application/json'
    if ($call.StatusCode -ge 300) { throw "command_intent_execute returned HTTP $($call.StatusCode): $($call.Content)" }

    [pscustomobject]@{ status = 'passed'; database = $Database; target_uuid = $TargetUuid; reducer = 'command_intent_execute'; actions = 'ATTACK,USE'; key = $key; utc = $stamp } | ConvertTo-Json -Compress
    exit 0
} catch {
    [pscustomobject]@{ status = 'blocked'; database = $Database; target_uuid = $TargetUuid; reason = $_.Exception.Message; utc = $stamp } | ConvertTo-Json -Compress
    exit 2
}
