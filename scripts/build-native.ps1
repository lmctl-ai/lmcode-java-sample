$ErrorActionPreference = "Stop"
$classpath = "target/lmcode-java-sample.jar"
$utf8Manifest = (Resolve-Path (Join-Path $PSScriptRoot "windows-utf8.manifest")).Path
$argsNative = @("-H:+UnlockExperimentalVMOptions", "-J-Xmx4g", "-H:NumberOfThreads=2", "--no-fallback", "-march=compatibility", "-H:+ReportExceptionStackTraces", "-H:IncludeResources=sample\.dsl\.json", "-H:IncludeResources=sample-version\.txt", "-H:NativeLinkerOption=/MANIFEST:EMBED", "-H:NativeLinkerOption=/MANIFESTINPUT:$utf8Manifest", "-cp", $classpath, "io.lmctl.sample.Main", "build/lmcode-java-sample")
$nativeImage = Join-Path $env:JAVA_HOME "bin/native-image.cmd"
if (!(Test-Path $nativeImage)) { throw "GraalVM native-image.cmd is required" }
& $nativeImage @argsNative
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
