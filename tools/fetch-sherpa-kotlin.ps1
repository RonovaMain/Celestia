# Для локальной сборки в Android Studio: подтягивает Kotlin-API sherpa-onnx (в CI это делает build.yml).
# Нативные библиотеки и голоса нужны только для запуска; для компиляции хватает этих исходников.
param([string]$Tag = "v1.13.8")
$ErrorActionPreference = "Stop"
$tmp = Join-Path $env:TEMP "sherpa-src"
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
git clone --depth 1 --branch $Tag https://github.com/k2-fsa/sherpa-onnx.git $tmp
$dst = Join-Path $PSScriptRoot "..\app\src\main\java\com\k2fsa\sherpa\onnx"
New-Item -ItemType Directory -Force $dst | Out-Null
Copy-Item "$tmp\sherpa-onnx\kotlin-api\*.kt" $dst -Force
Write-Host "Готово: Kotlin-API sherpa-onnx $Tag скопирован в $dst"
