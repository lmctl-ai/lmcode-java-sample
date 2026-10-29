$ErrorActionPreference = "Stop"
$classpath = "target/lmcode-java-sample.jar"
$argsNative = @("-H:+UnlockExperimentalVMOptions", "-J-Xmx4g", "-H:NumberOfThreads=2", "--no-fallback", "-march=compatibility", "-H:+ReportExceptionStackTraces", "-H:IncludeResources=sample\.dsl\.json|sample-version\.txt", "-cp", $classpath, "io.lmctl.sample.Main", "build/lmcode-java-sample")
& "$env:JAVA_HOME/bin/native-image.cmd" @argsNative
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
