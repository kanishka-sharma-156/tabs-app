# ============================================================================
#  validate-rules.ps1
#
#  Compiles firestore.rules with the REAL Firebase rules compiler and reports any
#  errors with line numbers - before you publish and find out the hard way.
#
#  Why this exists: the rules language is stricter than it looks. It forbids the
#  ternary operator entirely, and refuses to concatenate booleans into strings.
#  Both of those cause a publish failure that is invisible until you try.
#
#  Usage:
#      cd C:\Users\Admin\AndroidStudioProjects\TABABLE
#      powershell -ExecutionPolicy Bypass -File .\validate-rules.ps1
#
#  The compiler is downloaded once (~61 MB) into %TEMP%\tabs-rules-validator and
#  reused afterwards. Nothing is installed system-wide.
# ============================================================================

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

$projectRoot = $PSScriptRoot
$rulesFile   = Join-Path $projectRoot 'firestore.rules'

if (-not (Test-Path $rulesFile)) { throw "firestore.rules not found at $rulesFile" }

# --- locate a JDK (Android Studio bundles one and java is not on PATH) -------
function Find-Java {
    $candidates = @(
        "$env:JAVA_HOME\bin\java.exe",
        "$env:JAVA_HOME\bin\javac.exe",
        "C:\Program Files\Android\Android Studio\jbr\bin\java.exe",
        "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe",
        "C:\Program Files\Java\*\bin\java.exe"
    )
    foreach ($c in $candidates) {
        $found = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

$java = Find-Java
if (-not $java) {
    Write-Host "No Java found. Set JAVA_HOME, or install Android Studio (it bundles a JDK)." -ForegroundColor Yellow
    exit 2
}
$javaHome = Split-Path (Split-Path $java -Parent) -Parent
$javac = Join-Path $javaHome 'bin\javac.exe'
if (-not (Test-Path $javac)) { $javac = Join-Path (Split-Path $java -Parent) 'javac.exe' }

# --- work area ---------------------------------------------------------------
$work = Join-Path $env:TEMP 'tabs-rules-validator'
New-Item -ItemType Directory -Path $work -Force | Out-Null
$jar = Join-Path $work 'fs-emulator.jar'

if (-not (Test-Path $jar)) {
    Write-Host "Downloading the rules compiler (once, ~61 MB)..." -ForegroundColor Cyan
    $url = 'https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.19.8.jar'
    Invoke-WebRequest -Uri $url -OutFile $jar -UseBasicParsing -TimeoutSec 600
    Write-Host "Downloaded." -ForegroundColor DarkGray
}

# --- the validator itself ----------------------------------------------------
$validatorJava = Join-Path $work 'RulesValidator.java'
$validatorSrc = @'
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Compiles a Firebase Rules file with the engine bundled in the Firestore emulator jar. */
public class RulesValidator {
    public static void main(String[] args) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8");
        System.out.println("Compiling: " + args[0] + "  (" + content.length() + " chars)");
        System.out.println();

        Class<?> fileClass = Class.forName("com.google.firebase.rules.v1.File");
        Object fb = fileClass.getMethod("newBuilder").invoke(null);
        fb.getClass().getMethod("setName", String.class).invoke(fb, "firestore.rules");
        fb.getClass().getMethod("setContent", String.class).invoke(fb, content);
        Object file = fb.getClass().getMethod("build").invoke(fb);

        Class<?> sourceClass = Class.forName("com.google.firebase.rules.v1.Source");
        Object sb = sourceClass.getMethod("newBuilder").invoke(null);
        sb.getClass().getMethod("addFiles", fileClass).invoke(sb, file);
        Object source = sb.getClass().getMethod("build").invoke(sb);

        Class<?> compilerClass = Class.forName("com.google.firebase.rules.lang.FirebaseRulesProtoCompiler");
        Object compiler = compilerClass.getMethod("createStandalone").invoke(null);
        Method compile = compilerClass.getMethod("compile",
                Class.forName("com.google.firebase.rules.v1.SourceOrBuilder"));
        Object result = compile.invoke(compiler, source);

        Class<?> rc = result.getClass();
        boolean hasError = (Boolean) rc.getMethod("hasCompilationError").invoke(result);
        List<?> issues = (List<?>) rc.getMethod("getIssues").invoke(result);

        System.out.println("issues reported = " + issues.size());
        for (Object issue : issues) {
            try {
                Object pos = issue.getClass().getMethod("getSourcePosition").invoke(issue);
                int line = (Integer) pos.getClass().getMethod("getLine").invoke(pos);
                int col = (Integer) pos.getClass().getMethod("getColumn").invoke(pos);
                Object sev = issue.getClass().getMethod("getSeverity").invoke(issue);
                String desc = String.valueOf(issue.getClass().getMethod("getDescription").invoke(issue));
                System.out.println(String.format("  line %d:%d  [%s]  %s", line, col, sev, desc));
            } catch (Throwable t) {
                System.out.println("  " + issue);
            }
        }
        System.out.println();
        if (hasError) {
            System.out.println("RESULT: RULESET IS INVALID - do not publish this");
            System.exit(1);
        }
        System.out.println("RESULT: RULESET COMPILES CLEANLY - safe to publish");
    }
}
'@
# Write WITHOUT a BOM: the JDK compiler rejects a leading U+FEFF as an illegal
# character. This source is pure ASCII, so no encoding marker is needed.
[System.IO.File]::WriteAllText($validatorJava, $validatorSrc, (New-Object System.Text.ASCIIEncoding))
$validatorClass = Join-Path $work 'RulesValidator.class'
if (-not (Test-Path $validatorClass) -or (Get-Item $validatorJava).LastWriteTime -gt (Get-Item $validatorClass).LastWriteTime) {
    & $javac -nowarn -d $work $validatorJava
    if ($LASTEXITCODE -ne 0) { throw "Could not compile the validator helper" }
}

# --- run ---------------------------------------------------------------------
& $java -cp "$work;$jar" RulesValidator $rulesFile
exit $LASTEXITCODE
