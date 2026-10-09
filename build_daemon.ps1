$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } elseif (Test-Path 'C:\Program Files\Android\Android Studio\jbr') { 'C:\Program Files\Android\Android Studio\jbr' } else { exit 1 }
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"
$javac = "$javaHome\bin\javac.exe"

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$buildTools = Get-ChildItem "$sdk\build-tools" | Sort-Object Name -Descending | Select-Object -First 1
$d8 = "$($buildTools.FullName)\d8.bat"
$platforms = Get-ChildItem "$sdk\platforms" | Sort-Object Name -Descending | Select-Object -First 1
$jar = "$($platforms.FullName)\android.jar"

if (Test-Path 'shellserver\build') {
    Remove-Item -Recurse -Force 'shellserver\build'
}
New-Item -ItemType Directory -Path 'shellserver\build' -Force | Out-Null

Write-Host "Compiling Java sources..."
& $javac -source 17 -target 17 -encoding UTF-8 -cp $jar -d 'shellserver\build' (Get-ChildItem 'shellserver\src\com\aicall\shell\*.java' | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Dexing classes with d8..."
$classes = (Get-ChildItem 'shellserver\build\com\aicall\shell\*.class' | ForEach-Object { $_.FullName })
& $d8 --output 'shellserver\build' $classes
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Creating jar archive..."
if (Test-Path 'shellserver\aicall-shellserver.jar') {
    Remove-Item -Force 'shellserver\aicall-shellserver.jar'
}
Compress-Archive -Path 'shellserver\build\classes.dex' -DestinationPath 'shellserver\aicall-shellserver.zip' -Force
Move-Item -Path 'shellserver\aicall-shellserver.zip' -Destination 'shellserver\aicall-shellserver.jar' -Force
Write-Host "Done: shellserver\aicall-shellserver.jar"
