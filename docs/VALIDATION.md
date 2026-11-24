# Проверки проекта

## 24 ноября 2026 — подключение известного устройства без поиска

Добавлены getKnownDevices(), KnownBleDevice/KnownDevicesResult и UI выбора из сопряжённых BLE/dual/unknown и текущих системных GATT-подключений. Чтение вынесено на IO, дедупликация по адресу, подключённые первыми; признаки bond/GATT показаны раздельно. Обновление при входе и по кнопке не запускает scan и не сбрасывает клиент. Для списка/direct connect/autoreconnect используются connectPermissions; SCAN/location запрашиваются отдельно для поиска. RSSI для известного адреса null.

Sample: versionName 1.1-prototype, versionCode 2. После исправления импорта Compose финальный общий прогон завершился BUILD SUCCESSFUL: APK и AAR собраны, 29 существующих тестов RPC/framing/model прошли, Lint sample 0/0, flipper 0 ошибок/3 прежних GradleDependency. git diff --check прошёл. Это регрессионная проверка, не тест системного Bluetooth-списка на реальном телефоне.

Следующий физический сценарий: при уже подключённом/сопряжённом Flipper открыть приложение, выбрать его из «Уже известных устройств» без поиска, дождаться RPC ACTIVE и проверить Ping/DeviceInfo. Проверить также CONNECT без SCAN, Bluetooth off, отказ/отзыв CONNECT и активное другое приложение. Параллельная чужая RPC-сессия не считается пригодной для перенятия нашим клиентом. Прогон на устройстве пока не выполнен; подробности — PROTOTYPE.md.

## 24 ноября 2026 — сквозной пробный вариант

В рабочий FlipperZeroKit добавлены PowerInfo, Ping с ожиданием ответа, Storage List/Stat/Read/Write/Md5sum, SAF-браузер/передача, прогресс/отмена/результат и reconnect последнего успешного устройства при входе Activity. Mock-каталог удалён. Код/протокол — это статус реализации; физическая приёмка пока открыта.

| Проверка | Результат |
| --- | --- |
| :flipper:testDebugUnitTest | 29 тестов, 0 failures/errors: прежние 17 + 12 Storage/PowerInfo |
| Новые сценарии RPC | PowerInfo/неизвестные поля, List в несколько сообщений/пустой/ошибка/unsigned size, неверное содержимое/имена, бинарный и пустой Read, отмена/поздние ответы, Write 512/512/1/0 с одним ID, пустой Write, преждевременный/отсутствующий ACK, remote/send error, ограниченный буфер, Stat/MD5 |
| :sample:assembleDebug | APK собран |
| :flipper:assembleDebug | AAR собран |
| :sample:lintDebug | 0 ошибок, 0 предупреждений |
| :flipper:lintDebug | 0 ошибок, 3 прежних GradleDependency по Hilt |
| Kotlin compiler | 0 предупреждений/ошибок |
| ADB | Список устройств пуст; установки/прогона на телефоне не было |

Проверки выполнялись в самом C:/Users/RED.DOT/StudioProjects/FlipperZeroKit, с --offline. Временные ошибки имён generated protobuf API исправлены до успешного прогона. Новые Lint Recycle/UseKtx/UnusedResources устранены через use внутри IO, KTX edit и удаление старых ресурсов; проверки не подавлялись.

Итоговый общий прогон всех пяти задач завершился BUILD SUCCESSFUL (configuration cache reused). В incremental kaptDebugKotlin один раз появился processor warning о нераспознанных Hilt/kapt options; это отдельное предупреждение процессора, не Kotlin source/Lint. Оно не подавлялось и не считается устранённым.

Локальный APK: sample/build/outputs/apk/debug/sample-debug.apk. Прогон/ограничения — [PROTOTYPE.md](PROTOTYPE.md). Вручную ещё нужно подтвердить реальные значения питания, файловое содержимое/MD5, таймауты/обрывы/SAF и lifecycle reconnect на Android 16. Фон/resume не реализованы как гарантированный сценарий.

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
