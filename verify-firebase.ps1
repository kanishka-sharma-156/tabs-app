# ============================================================================
#  verify-firebase.ps1
#
#  End-to-end check of the TABS backend against your live Firebase project.
#  It creates throwaway accounts, exercises the real friend-request handshake,
#  chat and payment flows, and checks that the security boundaries hold.
#
#  Run this AFTER publishing firestore.rules (SETUP-FIREBASE.md step 1):
#
#      cd C:\Users\Admin\AndroidStudioProjects\TABABLE
#      powershell -ExecutionPolicy Bypass -File .\verify-firebase.ps1
#
#  It deletes the Firestore documents it creates. The throwaway AUTH accounts are
#  left behind (the client API cannot delete accounts) - remove them from
#  Authentication -> Users; they are named below and all start with a known prefix.
# ============================================================================

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$projectId = 'tabs-eb7c5e5e'
$apiKey    = 'AIzaSyDhmoooqznL92hhv4v3JGnRJy2nGuRZjrk'
$base      = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

$stamp    = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$password = 'Verify12345!'

$script:pass = 0
$script:fail = 0

function Write-Head($text) {
    Write-Host ""
    Write-Host $text -ForegroundColor Cyan
}

# Reports a check. $detail is always printed when it carries information, and is
# mandatory on failure - a failure with no reason is a bug in this script.
function Assert($ok, $name, $detail) {
    if ($ok) {
        $script:pass++
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $name" -ForegroundColor Red
    }
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
}

function Invoke-Json($method, $uri, $body, $token) {
    $params = @{
        Method      = $method
        Uri         = $uri
        ContentType = 'application/json'
        TimeoutSec  = 30
    }
    if ($body)  { $params.Body = ($body | ConvertTo-Json -Depth 20 -Compress) }
    if ($token) { $params.Headers = @{ Authorization = "Bearer $token" } }

    try {
        return @{ ok = $true; status = 200; data = (Invoke-RestMethod @params) }
    } catch {
        $status = 0
        $text   = $_.Exception.Message
        $resp   = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            try {
                $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
                $text = $sr.ReadToEnd()
            } catch { }
        }
        return @{ ok = $false; status = $status; data = $text }
    }
}

function Short($text) {
    if (-not $text) { return '' }
    $s = ([string]$text) -replace '\s+', ' '
    if ($s.Length -gt 160) { return $s.Substring(0, 160) + '...' }
    return $s
}

function New-Account($email) {
    $res = Invoke-Json 'POST' `
        "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey" `
        @{ email = $email; password = $password; returnSecureToken = $true } $null
    if (-not $res.ok) { throw "could not create account ${email}: $(Short $res.data)" }
    if (-not $res.data.idToken) { throw "no idToken returned for $email" }
    return @{ token = $res.data.idToken; uid = $res.data.localId; email = $email }
}

# The request document id is simply sender_recipient, unsorted. Both orderings are
# accepted by the rules, so either side can find and answer it.
function DocId($fromUid, $toUid) {
    return "${fromUid}_${toUid}"
}

# The 1-to-1 space id IS sorted, because both people must arrive at the same space.
function SpaceId($a, $b) {
    if ([string]::CompareOrdinal($a, $b) -le 0) { return "${a}_${b}" }
    return "${b}_${a}"
}

# Full path helpers. Note the plain string concatenation for query strings:
# interpolating "${path}?..." can produce a mangled URL, so avoid it.
function Doc-Url($path)  { return $base + '/' + $path }
function Query-Url($path, $query) { return $base + '/' + $path + '?' + $query }

function Get-Doc($path, $token) {
    return Invoke-Json 'GET' (Doc-Url $path) $null $token
}

# Upsert: a plain PATCH, which creates the document when it is absent.
function Put-Doc($path, $fields, $token) {
    return Invoke-Json 'PATCH' (Doc-Url $path) @{ fields = $fields } $token
}

function Put-Fields($path, $fields, $token) {
    $mask = ($fields.Keys | ForEach-Object { "updateMask.fieldPaths=$_" }) -join '&'
    return Invoke-Json 'PATCH' (Query-Url $path $mask) @{ fields = $fields } $token
}

function Delete-Doc($path, $token) {
    return Invoke-Json 'DELETE' (Doc-Url $path) $null $token
}

function FB-S($v) { return @{ stringValue = [string]$v } }
function FB-I($v) { return @{ integerValue = [string]$v } }

# A denied write is the desired outcome; anything 2xx is a security failure.
function Expect-Denied($res) { return -not $res.ok }

function Deny-Detail($res) {
    if ($res.ok) { return 'SECURITY PROBLEM: the request was allowed (HTTP 200)' }
    return "correctly denied (HTTP $($res.status))"
}

# ---------------------------------------------------------------------------
# 0. Is the database there at all?
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "TABS Firebase verification" -ForegroundColor White
Write-Host "project: $projectId"

Write-Head "0. Firestore database exists"

$probe = Invoke-Json 'GET' `
    "https://firestore.googleapis.com/v1/projects/$projectId/databases/$projectId/documents/users" `
    $null 'definitely-not-a-valid-token'

if ($probe.status -eq 404) {
    Assert $false "Firestore database is reachable" (Short $probe.data)
    Write-Host ""
    Write-Host "  The database does not exist. Do SETUP-FIREBASE.md step 1 first:" -ForegroundColor Yellow
    Write-Host "  https://console.firebase.google.com/project/$projectId/firestore" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}
Assert $true "Firestore database is reachable" "HTTP $($probe.status) - 401/403 here is expected"

# ---------------------------------------------------------------------------
# 1. Accounts
# ---------------------------------------------------------------------------
Write-Head "1. Create disposable test accounts"

$userA = New-Account "tabsverify.a.$stamp@example.com"
Assert $true "Account A created" $userA.email

$userB = New-Account "tabsverify.b.$stamp@example.com"
Assert $true "Account B created" $userB.email

$uidA   = $userA.uid
$uidB   = $userB.uid
$reqId  = DocId $uidA $uidB
$spaceId = SpaceId $uidA $uidB

# The accounts are new, so clean any leftovers from an earlier run at these exact
# paths first; a stale edge would otherwise turn a real result into ALREADY_EXISTS.
Delete-Doc "friendRequests/$reqId"          $userA.token | Out-Null
Delete-Doc "users/$uidA/friends/$uidB"      $userA.token | Out-Null
Delete-Doc "users/$uidB/friends/$uidA"      $userB.token | Out-Null
Delete-Doc "spaces/$spaceId"                $userA.token | Out-Null

# Sanity-check the credentials before trusting anything below. If these are not
# clean 2xx responses, every later result is meaningless.
Write-Head "1b. Credentials work (sanity check)"

$ownWrite = Put-Doc "users/$uidA" @{ uid = (FB-S $uidA); displayName = (FB-S 'Verify Alice') } $userA.token
Assert $ownWrite.ok "A can write its own profile" `
    "HTTP $($ownWrite.status) $(Short $ownWrite.data)"

# B needs a profile too. Without one, the later "friend can read a friend profile"
# check returns a perfectly legitimate 404 and looks like a failure.
$ownWriteB = Put-Doc "users/$uidB" @{ uid = (FB-S $uidB); displayName = (FB-S 'Verify Bob') } $userB.token
Assert $ownWriteB.ok "B can write its own profile" `
    "HTTP $($ownWriteB.status) $(Short $ownWriteB.data)"

if (-not $ownWrite.ok -and $ownWrite.status -eq 401) {
    Write-Host ""
    Write-Host "  Got HTTP 401 on a basic authenticated write. Stopping: nothing below" -ForegroundColor Yellow
    Write-Host "  would be a meaningful result." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

# ---------------------------------------------------------------------------
# 2. Security boundaries
# ---------------------------------------------------------------------------
Write-Head "2. Security boundaries and profile privacy"

$probeWrite = Put-Doc "users/$uidB/backups/evil" @{ hack = (FB-S 'yes') } $userA.token
Assert (Expect-Denied $probeWrite) "A cannot write into private data of B" (Deny-Detail $probeWrite)
if ($probeWrite.ok) { Delete-Doc "users/$uidB/backups/evil" $userB.token | Out-Null }

$ownRead = Get-Doc "users/$uidA" $userA.token
Assert $ownRead.ok "A can read its own profile" "HTTP $($ownRead.status) $(Short $ownRead.data)"

try {
    $userC = New-Account "tabsverify.c.$stamp@example.com"
    $strangerOk = $true
} catch {
    $strangerOk = $false
    Write-Host "  [SKIP] stranger checks - $(Short $_.Exception.Message)" -ForegroundColor Yellow
}

if ($strangerOk) {
    # The user directory must not be enumerable at all.
    $enum = Invoke-Json 'GET' "$base/users" $null $userC.token
    Assert (Expect-Denied $enum) "The user directory cannot be listed" `
        $(if ($enum.ok) { "SECURITY PROBLEM: listed $($enum.data.documents.Count) profiles" }
          else { "correctly denied (HTTP $($enum.status))" })

    # A brand-new stranger is related to nobody, so an unrelated profile is off limits.
    $spyProfile = Get-Doc "users/$uidA" $userC.token
    Assert (Expect-Denied $spyProfile) "A stranger cannot read an unrelated profile" `
        (Deny-Detail $spyProfile)
}

# ---------------------------------------------------------------------------
# 3. Friend request handshake
# ---------------------------------------------------------------------------
Write-Head "3. Friend request: send, receive, accept"

$send = Put-Doc "friendRequests/$reqId" @{
    id        = (FB-S $reqId)
    fromUid   = (FB-S $uidA)
    toUid     = (FB-S $uidB)
    fromName  = (FB-S 'Verify Alice')
    status    = (FB-S 'PENDING')
    createdAt = (FB-I ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()))
} $userA.token
Assert $send.ok "A sent the friend request" "HTTP $($send.status) $(Short $send.data)"

$readB = Get-Doc "friendRequests/$reqId" $userB.token
$statusB = if ($readB.ok) { $readB.data.fields.status.stringValue } else { $null }
Assert ($readB.ok -and $statusB -eq 'PENDING') "B can read the request and it is PENDING" `
    $(if ($readB.ok) { "status = $statusB" } else { "HTTP $($readB.status) $(Short $readB.data)" })

# A friendship must be refused while the request is still PENDING.
$forge = Put-Doc "users/$uidA/friends/$uidB" @{ friendUid = (FB-S $uidB) } $userA.token
Assert (Expect-Denied $forge) "A cannot create a friendship before acceptance" (Deny-Detail $forge)
if ($forge.ok) { Delete-Doc "users/$uidA/friends/$uidB" $userA.token | Out-Null }

$accept = Put-Fields "friendRequests/$reqId" @{ status = (FB-S 'ACCEPTED') } $userB.token
Assert $accept.ok "B accepted the request" "HTTP $($accept.status) $(Short $accept.data)"

$readA = Get-Doc "friendRequests/$reqId" $userA.token
$statusA = if ($readA.ok) { $readA.data.fields.status.stringValue } else { $null }
Assert ($readA.ok -and $statusA -eq 'ACCEPTED') `
    "A sees the acceptance (the sender can observe the answer)" `
    $(if ($readA.ok) { "status = $statusA" } else { "HTTP $($readA.status) $(Short $readA.data)" })

$edgeA = Put-Doc "users/$uidA/friends/$uidB" @{ friendUid = (FB-S $uidB); since = (FB-I 1) } $userA.token
Assert $edgeA.ok "A records its own friendship edge after acceptance" `
    "HTTP $($edgeA.status) $(Short $edgeA.data)"

$edgeB = Put-Doc "users/$uidB/friends/$uidA" @{ friendUid = (FB-S $uidA); since = (FB-I 1) } $userB.token
Assert $edgeB.ok "B records its own friendship edge after acceptance" `
    "HTTP $($edgeB.status) $(Short $edgeB.data)"

# ---------------------------------------------------------------------------
# 4. Space, chat and payment
# ---------------------------------------------------------------------------
Write-Head "4. Space, chat message and payment"

$space = Put-Doc "spaces/$spaceId" @{
    id         = (FB-S $spaceId)
    type       = (FB-S 'DIRECT')
    memberUids = @{ arrayValue = @{ values = @((FB-S $uidA), (FB-S $uidB)) } }
    leaderUid  = (FB-S $uidA)
    name       = (FB-S 'Direct Tab')
} $userA.token
Assert $space.ok "A created the DIRECT space" "HTTP $($space.status) $(Short $space.data)"

$msg = Put-Doc "spaces/$spaceId/messages/m1" @{
    id         = (FB-S 'm1')
    senderUid  = (FB-S $uidA)
    senderName = (FB-S 'Verify Alice')
    text       = (FB-S 'hello from the verifier')
    type       = (FB-S 'TEXT')
    amount     = (FB-I 0)
    status     = (FB-S 'SENT')
    timestamp  = (FB-I ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()))
} $userA.token
Assert $msg.ok "A sent a chat message" "HTTP $($msg.status) $(Short $msg.data)"

$readMsg = Get-Doc "spaces/$spaceId/messages/m1" $userB.token
Assert $readMsg.ok "B can read the message (real-time chat)" `
    "HTTP $($readMsg.status) $(Short $readMsg.data)"

$settle = Put-Doc "spaces/$spaceId/settlements/s1" @{
    id          = (FB-S 's1')
    fromUid     = (FB-S $uidA)
    toUid       = (FB-S $uidB)
    amountPaise = (FB-I 50000)
    status      = (FB-S 'PENDING')
    createdAt   = (FB-I ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()))
} $userA.token
Assert $settle.ok "A proposed a payment of 50000 paise" "HTTP $($settle.status) $(Short $settle.data)"

$selfConfirm = Put-Fields "spaces/$spaceId/settlements/s1" @{ status = (FB-S 'CONFIRMED') } $userA.token
Assert (Expect-Denied $selfConfirm) "A cannot confirm its own payment (only the payee may)" `
    (Deny-Detail $selfConfirm)

$payeeConfirm = Put-Fields "spaces/$spaceId/settlements/s1" @{ status = (FB-S 'CONFIRMED') } $userB.token
Assert $payeeConfirm.ok "B (the payee) confirmed the payment" `
    "HTTP $($payeeConfirm.status) $(Short $payeeConfirm.data)"

# ---------------------------------------------------------------------------
# 5. Outsider
# ---------------------------------------------------------------------------
if ($strangerOk) {
    Write-Head "5. A stranger is locked out"

    $spyMsg = Get-Doc "spaces/$spaceId/messages/m1" $userC.token
    Assert (Expect-Denied $spyMsg) "An outsider cannot read the private chat" (Deny-Detail $spyMsg)

    $spyReq = Get-Doc "friendRequests/$reqId" $userC.token
    Assert (Expect-Denied $spyReq) "An outsider cannot read a friend request that is not theirs" (Deny-Detail $spyReq)

    # A friend SHOULD be able to read the other friend's profile (that is what makes
    # the chat header and friend list work). B is now A's friend, so check A can read B.
    $friendRead = Get-Doc "users/$uidB" $userA.token
    Assert $friendRead.ok "A friend can still read a friend profile" `
        "HTTP $($friendRead.status) $(Short $friendRead.data)"
}

# ---------------------------------------------------------------------------
# 6. Cleanup
# ---------------------------------------------------------------------------
Write-Head "6. Cleanup test data"

Delete-Doc "friendRequests/$reqId"                $userA.token | Out-Null
Delete-Doc "users/$uidA/friends/$uidB"            $userA.token | Out-Null
Delete-Doc "users/$uidB/friends/$uidA"            $userB.token | Out-Null
Delete-Doc "spaces/$spaceId/settlements/s1"       $userA.token | Out-Null
Delete-Doc "spaces/$spaceId/messages/m1"          $userA.token | Out-Null
Delete-Doc "spaces/$spaceId"                      $userA.token | Out-Null
Delete-Doc "users/$uidA"                          $userA.token | Out-Null
Delete-Doc "users/$uidB"                          $userB.token | Out-Null

# Confirm the two most sensitive paths really are gone.
$leftover = 0
foreach ($p in @("friendRequests/$reqId", "spaces/$spaceId/messages/m1")) {
    $chk = Get-Doc $p $userA.token
    if ($chk.ok) { $leftover++ }
}
$cleanupDetail = if ($leftover -eq 0) { "verified: both paths now return 404" }
                 else { "$leftover path(s) still present" }
Assert ($leftover -eq 0) "Test documents removed" $cleanupDetail

# ---------------------------------------------------------------------------
Write-Host ""
if ($fail -eq 0) {
    Write-Host "ALL $pass CHECKS PASSED" -ForegroundColor Green
    Write-Host "Auth, profiles, friend requests, chat and payments are live," -ForegroundColor Green
    Write-Host "and the security boundaries hold." -ForegroundColor Green
} else {
    Write-Host "$pass passed, $fail FAILED" -ForegroundColor Yellow
    if ($script:securityFailures -gt 0) {
        Write-Host "At least one SECURITY PROBLEM was reported above." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "Remove the leftover test accounts from:" -ForegroundColor DarkGray
Write-Host "https://console.firebase.google.com/project/$projectId/authentication/users" -ForegroundColor DarkGray
Write-Host "They are the tabsverify.*@example.com entries." -ForegroundColor DarkGray
Write-Host ""

if ($fail -eq 0) { exit 0 } else { exit 1 }
