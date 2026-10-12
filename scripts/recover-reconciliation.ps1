[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateRange(1, [long]::MaxValue)][long]$ExecutionId,
    [Parameter(Mandatory = $true)][string]$Namespace,
    [Parameter(Mandatory = $true)][string]$Deployment,
    [Parameter(Mandatory = $true)][string]$Database,
    [Parameter(Mandatory = $true)][string]$MysqlLoginPath,
    [switch]$AllOtherLaunchersStopped
)
$ErrorActionPreference = 'Stop'
if (-not $AllOtherLaunchersStopped) {
    throw 'Explicit -AllOtherLaunchersStopped acknowledgement is required: stop every scheduler, manual launcher and external controller for the maintenance window.'
}
$deploymentJson = & kubectl get deployment $Deployment --namespace $Namespace --output json
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify deployment state.' }
$deploymentState = $deploymentJson | ConvertFrom-Json
if ($deploymentState.spec.replicas -ne 0) { throw 'Deployment must be scaled to zero before recovery.' }
if ($deploymentState.spec.selector.matchExpressions.Count -gt 0) {
    throw 'This recovery helper requires a matchLabels-only deployment selector; verify recovery manually for other selectors.'
}
$selector = ($deploymentState.spec.selector.matchLabels.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','
if ([string]::IsNullOrWhiteSpace($selector)) { throw 'Cannot verify workers without a deployment selector.' }
$podsJson = & kubectl get pods --namespace $Namespace --selector $selector --output json
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify stopped pods.' }
if (@(($podsJson | ConvertFrom-Json).items).Count -ne 0) { throw 'Pods still exist, including terminating pods. Wait for every worker to exit.' }
$hpaJson = & kubectl get hpa --namespace $Namespace --output json
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify autoscalers.' }
$activeAutoscalers = @(($hpaJson | ConvertFrom-Json).items | Where-Object { $_.spec.scaleTargetRef.name -eq $Deployment })
if ($activeAutoscalers.Count -gt 0) { throw 'Remove/suspend the deployment HPA for this maintenance window before recovery.' }
# No credentials in arguments/output: mysql_config_editor creates the named login path in advance.
$recoverySql = "SET @execution_id = $ExecutionId;" + [Environment]::NewLine +
    (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'recover-reconciliation.sql') -Raw)
$result = $recoverySql | & mysql "--login-path=$MysqlLoginPath" "--database=$Database" --batch --skip-column-names
if ($LASTEXITCODE -ne 0) { throw 'Recovery transaction failed; inspect the restricted database session.' }
if (($result | Select-Object -Last 1).Trim() -ne '1') {
    throw 'No active reconciliation execution matched. Nothing was recovered.'
}
Write-Output "Execution $ExecutionId marked FAILED. Preserve evidence, restore workers and let the scheduler retry the current schedule date."
