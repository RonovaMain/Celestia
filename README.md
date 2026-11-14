# Celestia — смена голоса офлайн (RVC v2) для Android

Пакет `dev.ronova.celestia`. Kotlin + Compose (Material 3 / Material You), ONNX Runtime только на CPU, arm64-v8a.
В манифесте нет разрешения INTERNET: приложение физически не может выйти в сеть.

## Как пользоваться
Голоса → «Импортировать .pth» (+ по желанию «Прикрепить .index») → Конвертация → выбрать голос и аудио → «Конвертировать».
Настройки: Pitch ±24, Index Rate (сходство с голосом), Protect, RMS Mix, Температура; RMVPE/FCPE; микшер с минусом.

## Модели (скачиваются в CI)
- ContentVec vec-768-layer-12 — NaruseMioShirakana/MoeSS-SUBModel (в репозитории указана лицензия GPL-3.0)
- RMVPE — Scicom-intl/rmvpe-onnx (MIT; веса RVC-Project)
- FCPE — Scicom-intl/fcpe-onnx (MIT; CNChTu/FCPE)
