# Проверки проекта

## 24 ноября 2026 — предупреждения и ошибки

Исправления применены в рабочем FlipperZeroKit; итоговая проверка запускалась непосредственно из C:/Users/RED.DOT/StudioProjects/FlipperZeroKit.

```text
.\gradlew.bat --offline --warning-mode all :flipper:assembleDebug :flipper:testDebugUnitTest :sample:assembleDebug :flipper:lintDebug :sample:lintDebug
```

Результат: BUILD SUCCESSFUL; Configuration cache entry stored.

| Проверка | Результат |
| --- | --- |
| Kotlin source compiler | 0 предупреждений, 0 ошибок |
| :flipper:lintDebug | 0 ошибок, 3 предупреждения GradleDependency об общей версии Hilt |
| :sample:lintDebug | 0 ошибок, 0 предупреждений |
| Unit-тесты :flipper | 17 тестов, 0 ошибок/падений |
| :flipper:assembleDebug | AAR собран |
| :sample:assembleDebug | APK собран |
| Native stripping | Выполнен установленным NDK r28c, предупреждение Unable to strip отсутствует |
| git diff --check | Код 0, ошибок whitespace нет |

Ранее недостающие intellij-core/kotlin-compiler 31.13.0 скачаны, блокер AAR/Lint устранён.
Детали исправлений и оставшиеся версионные предупреждения: [WARNINGS.md](WARNINGS.md).

Окружение: JBR 21.0.11, Gradle 8.13, Kotlin 2.2.20, AGP 8.13.0, compileSdk/targetSdk 37, minSdk 23. Sample использует NDK 28.2.13676358 для упаковки native-зависимостей AndroidX.
Остались предупреждения AGP о compileSdk 37 и предложение обновить Hilt; согласованная миграция сборочного стека ещё не выполнена.

## 23 ноября 2026 — DeviceInfo и структура модуля

В :flipper реализован getDeviceInfo; sample использует реальный RPC-запрос вместо demo.
17 unit-тестов: 12 сценариев RpcClient, 2 проверки ключей/отсутствующих полей DeviceInfo, 3 теста framing.
Проверены быстрый ответ, has_next, чужие ID, параллельные запросы, ошибки статуса/типа/отправки, timeout, отмена, session close, поздние ответы и повторный запрос.
Отдельно проверено, что быстрый ответ не отменяет ожидаемый GATT write callback.
Ранее выполнены перенос в :flipper, разделение api/internal, генерация восьми proto-схем и сборка sample APK.

## Физическое устройство

Известен Android 16; firmware Flipper пока неизвестна.
Ping подтверждён пользователем в исходном сценарии; нами поиск/сопряжение, DeviceInfo, новая ветка GATT API 37 и файловые операции на устройстве ещё не проверялись.
Успешные тесты, Lint и сборка не означают, что BLE-поведение перепроверено физически.
Чек-лист — [DEVICE_INFO.md](DEVICE_INFO.md), общая приёмка — [TASK.md](TASK.md) и [ROADMAP.md](ROADMAP.md).
