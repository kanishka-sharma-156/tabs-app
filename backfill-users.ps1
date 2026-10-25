# ============================================================================
#  backfill-users.ps1
#
#  Guarantees that existing accounts are searchable.
#
#  An account is only findable if BOTH of these exist:
#
#      emailLookup/{email}  +  users/{uid}   <- profile
#      usernames/{name}     +  users/{uid}   <- profile
#
#  A search resolves the lookup, then the friend request is written beside the
#  recipient's profile. If the profile is missing, the person can be found by
#  search but can never receive a request.
#
#  The app repairs its own profile on every login, so the simplest fix for any
#  single account is: open TABS and log in once.
#
#  This script does that for a whole list at once. Because profiles may only be
#  written by their owner (that is enforced by the security rules, correctly), the
#  script has to sign in as each person - which is why it needs their password.
#
#  USAGE
#    1. Create a CSV of the affected accounts, with a header row:
#
#         Email,Password,Username
#         kanu1@gmail.com,theirpassword,kanu1
#         kanu2@gmail.com,theirpassword,kanu2
#
#       Username is optional; leave it blank to keep whatever is already claimed.
#       If you do not know a password, use the Firebase console:
#       Authentication -> Users -> that account -> Reset password, then put the
#       new one here.
#
#    2. Run:
#
#         cd C:\Users\Admin\AndroidStudioProjects\TABABLE
#         powershell -ExecutionPolicy Bypass -File .\backfill-users.ps1 -Csv .\users.csv
#
#    3. Delete the CSV afterwards - it contains passwords.
# ============================================================================

param(
    [Parameter(Mandatory = $true)][string]$Csv,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$projectId = 'tabs-eb7c5e5e'
$apiKey    = 'AIzaSyDhmoooqznL92hhv4v3JGnRJy2nGuRZjrk'
$base      = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

if (-not (Test-Path $Csv)) { throw "CSV not found: $Csv" }
$rows = Import-Csv $Csv
if (-not $rows) { throw "CSV has no rows: $Csv" }

function Invoke-Json($method, $uri, $body, $token) {
    $p = @{ Method = $method; Uri = $uri; ContentType = 'application/json'; TimeoutSec = 30 }
    if ($body)  { $p.Body = ($body | ConvertTo-Json -Depth 20 -Compress) }
    if ($token) { $p.Headers = @{ Authorization = "Bearer $token" } }
    try { return @{ ok = $true; status = 200; data = (Invoke-RestMethod @p) } }
    catch {
        $s = 0; $t = $_.Exception.Message; $r = $_.Exception.Response
        if ($r) { $s = [int]$r.StatusCode
            try { $sr = New-Object System.IO.StreamReader($r.GetResponseStream()); $t = $sr.ReadToEnd() } catch {} }
        return @{ ok = $false; status = $s; data = $t }
    }
}
function FB-S($v) { return @{ stringValue = [string]$v } }
function Norm($v) { if ($null -eq $v) { return '' } return $v.ToString().Trim().ToLower() }

$fixed = 0; $already = 0; $failed = 0

Write-Host ""
Write-Host "TABS user backfill" -ForegroundColor White
Write-Host "project: $projectId"
if ($WhatIf) { Write-Host "MODE: dry run - nothing will be written" -ForegroundColor Yellow }
Write-Host ""

foreach ($row in $rows) {

    $email = Norm $row.Email
    if (-not $email) { continue }
    $password = $row.Password
    $username = Norm $row.Username

    Write-Host "$email"

    if (-not $password) {
        Write-Host "   no password supplied - skip (log in with the app once instead)" -ForegroundColor Yellow
        $failed++
        continue
    }

    # Sign in as this person. This is what authorises writing their profile.
    $signin = Invoke-Json 'POST' `
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$apiKey" `
        @{ email = $email; password = $password; returnSecureToken = $true } $null

    if (-not $signin.ok) {
        $msg = if ($signin.data -match 'INVALID_LOGIN_CREDENTIALS') { 'wrong password' } else { $signin.data }
        Write-Host "   could not sign in: $msg" -ForegroundColor Red
        $failed++
        continue
    }

    $uid   = $signin.data.localId
    $token = $signin.data.idToken

    $profile = Invoke-Json 'GET' "$base/users/$uid" $null $token
    $hadProfile = $profile.ok

    if ($hadProfile) {
        Write-Host "   profile already present (uid $uid)" -ForegroundColor DarkGray
        $already++
    } else {
        Write-Host "   profile MISSING - creating it" -ForegroundColor Yellow
    }

    if ($WhatIf) { continue }

    # Write the profile, exactly like the app's own self-repair: one atomic batch
    # covering the profile and both lookup keys.
    $writes = @()
    $name = if ($username) { $username } else { ($email -split '@')[0] }

    $fields = @{ uid = (FB-S $uid); email = (FB-S $email); emailLower = (FB-S $email); displayName = (FB-S $name) }
    if ($username) { $fields.username = (FB-S $username) }
    $writes += @{ update = @{
        name   = "projects/$projectId/databases/(default)/documents/users/$uid"
        fields = $fields } }

    $writes += @{ update = @{
        name   = "projects/$projectId/databases/(default)/documents/emailLookup/$email"
        fields = @{ uid = (FB-S $uid); email = (FB-S $email) } } }

    if ($username) {
        $writes += @{ update = @{
            name   = "projects/$projectId/databases/(default)/documents/usernames/$username"
            fields = @{ uid = (FB-S $uid); username = (FB-S $username) } } }
    }

    $commit = Invoke-Json 'POST' `
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents:commit" `
        @{ writes = $writes } $token

    if (-not $commit.ok) {
        Write-Host "   write failed (HTTP $($commit.status)): $($commit.data)" -ForegroundColor Red
        $failed++
        continue
    }

    # Confirm it actually landed.
    $check = Invoke-Json 'GET' "$base/users/$uid" $null $token
    if ($check.ok) {
        if ($hadProfile) { $already++ } else { $fixed++ }
        Write-Host "   OK - this account is now searchable and can receive requests" -ForegroundColor Green
    } else {
        Write-Host "   wrote, but the profile still reads back missing" -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
Write-Host "created: $fixed   already fine: $already   failed: $failed"
Write-Host ""
Write-Host "Now delete the CSV - it contains passwords:" -ForegroundColor Yellow
Write-Host "    Remove-Item '$Csv'"
Write-Host ""
Write-Host "Then confirm with: powershell -ExecutionPolicy Bypass -File .\verify-firebase.ps1"

if ($failed -gt 0) { exit 1 }
exit 0
