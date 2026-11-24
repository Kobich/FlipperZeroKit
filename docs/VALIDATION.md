# Проверка — 23 ноября 2026

## DeviceInfo — текущая проверка

После добавления getDeviceInfo выполнено :flipper:testDebugUnitTest :sample:assembleDebug --offline --no-configuration-cache: BUILD SUCCESSFUL.
17 unit-тестов, 0 ошибок/падений: 12 сценариев RpcClient, 2 проверки ключей/отсутствующих полей DeviceInfo и 3 существующих теста framing.
Проверены мгновенный ответ, has_next, посторонние ID, параллельные запросы, ошибки статуса/типа/отправки, timeout (включая зависшую отправку), caller cancellation, закрытие сессии, поздние ответы и повторный запрос. Отдельно проверено, что быстрый RPC-ответ не отменяет ожидаемый GATT write callback.
Собран sample APK с реальным запросом, состояниями RPC-ready/loading/error и просмотром исходных полей. Demo DeviceInfo и заряд удалены.
Пользователь указал Android 16; firmware неизвестна. Физический запрос ещё не проверен. Чек-лист — DEVICE_INFO.md.

## Предыдущая проверка структуры

После переименования модуля в :flipper и перехода на API dev.flipper.api.Flipper повторно выполнены :flipper:testDebugUnitTest и :sample:assembleDebug: BUILD SUCCESSFUL.
Проверены src/main/kotlin и src/test/kotlin, генерация proto и подключение sample через implementation(project(":flipper")). Три теста framing прошли без ошибок.

Функциональная база — RPC-код origin/protobuf-framing.

## Успешно

- :flipper:generateDebugProto — сгенерированы Java lite-модели из восьми схем.
- :flipper:compileDebugKotlin и :flipper:compileDebugJavaWithJavac — библиотека с framing и RPC скомпилирована.
- :flipper:testDebugUnitTest — 3 теста, 0 ошибок/падений: разрезанный varint/тело, несколько кадров в одном фрагменте, превышение/переполнение длины, незаконченный кадр.
- :sample:assembleDebug — собран debug APK с нашей библиотекой, Hilt и кнопкой ping.
- Проверены границы: в библиотеке нет Hilt, javax.inject, Compose, sample и старого пакета dev.filpper.companion.
- Рабочая копия коллеги проверена по SHA-256 и git status: наших изменений нет. Его ветка осталась main; существовавшее изменение wrapper properties сохранено.

Команда для повторения основной проверки:

```text
.\gradlew.bat :flipper:testDebugUnitTest :sample:assembleDebug
```

Сборка sample также проверяет подключение библиотеки как Gradle-модуля. Она не заменяет отдельную упаковку AAR.

## Не завершено: AAR и Lint

На предыдущем этапе запускались упаковка AAR и Lint. После структурного рефакторинга эти задачи повторно не запускались.
В offline-кэше не хватило com.android.tools.external.com-intellij:intellij-core:31.13.0 и kotlin-compiler:31.13.0.
Сетевая загрузка двух крупных JAR из Google Maven шла медленно и была остановлена.
Полное прохождение этих двух задач не заявляем. Для завершения повторить:

```text
.\gradlew.bat :flipper:assembleDebug :flipper:lintDebug
```

Protoc и зависимости Hilt, которых первоначально тоже не было в offline-кэше, впоследствии загрузились: они больше не блокируют проверенные задачи.

## Окружение и предупреждения

JBR 21.0.11, Gradle 8.13, Kotlin 2.2.20, AGP 8.13.0, Android SDK 37.0.
AGP предупреждает, что проверен до compileSdk 36.1; SDK 37 здесь за пределами проверенной комбинации.
Есть предупреждения устаревшего kotlinOptions.jvmTarget, совместимых GATT API и старого импорта hiltViewModel в sample; они не помешали проверенным задачам.
Версии инструментов в рамках знакомства не обновлялись.

## Физическое устройство

Нами не проверялись: поиск/сопряжение на телефоне, RPC-ready, ping, DeviceInfo/PowerInfo и файлы.
Ping найден и перенесён из protobuf-framing; по проверке пользователя он работает. В sample сценарий пока проверяет отправку, а не ожидает подтверждённый PingResponse.
Успешные тесты framing и сборка APK не означают, что BLE/RPC на устройстве уже перепроверены.
Чек-лист следующего прогона — в ROADMAP.md.
