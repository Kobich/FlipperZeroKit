# Предупреждения и ошибки — 24 ноября 2026

Исправления применены в рабочем C:/Users/RED.DOT/StudioProjects/FlipperZeroKit и проверены из этой папки. Репозиторий коллеги не изменялся.

## Что исправлено

| Область | Изменение |
| --- | --- |
| Kotlin Gradle DSL | Оба модуля используют kotlin.compilerOptions с JvmTarget.JVM_17 вместо deprecated kotlinOptions; целевой байткод сохранён |
| Compose/Hilt | hiltViewModel перенесён на androidx.hilt.lifecycle.viewmodel.compose и соответствующий артефакт 1.3.0, без обновления версии |
| Hilt-аннотация | В ThemeRepository явно указан @param:ApplicationContext, как и требовалось конструкторной инъекции |
| Bluetooth permissions | У отправки указан внутренний контракт RequiresPermission, публичный вход проверяет разрешение и обрабатывает SecurityException; readFlowControl в callback также защищён. Отказ в доступе возвращается как PermissionMissing |
| Закрытие GATT | Отзыв разрешения во время close больше не выпускает SecurityException из finally |
| connectGatt | Для API 37 используется settings/executor overload. Для старых версий оставлен отдельный legacy helper с локальным Suppress(DEPRECATION), поскольку заменяющий API там отсутствует |
| Legacy callbacks | Только callbacks для API 23–32 имеют локальные DEPRECATION/OVERRIDE_DEPRECATION suppressions; новые overloads продолжают получать переданное value |
| GATT-логи | Убрано чтение deprecated characteristic.value/descriptor.value из отладочного обхода профиля |
| Неиспользуемый код | Удалены FLIPPER_TX_MAX_SIZE и два лишних Compose-import; убран лишний nullable access к проверенному BluetoothAdapter |
| Ресурсы | Удалены select_ble_device, percentage и retry, на которые нет ссылок |
| Иконка | Градиент вынесен в drawable-v24; добавлены fallback-ресурсы для API 23 и launcher до API 26. Длинные пути сжаты без изменения последовательности команд и числовых координат |
| Manifest | usesPermissionFlags намеренно относится к API 31; это отмечено tools:targetApi="s" на соответствующем permission, без отключения проверки целиком |
| Native symbols | sample закреплён на NDK 28.2.13676358 (r28c), уже установленном в SDK. Предыдущий default 27.0.12077973 отсутствовал, поэтому Gradle не находил strip tool для AndroidX .so |

Автоматический обмен MTU в новом API явно выключен, чтобы перенос connectGatt не добавлял ещё одну GATT-операцию к существующей инициализации. Подключение и callbacks остаются на main.
SuppressLint(MissingPermission), глобальное отключение warning/Lint и baseline для скрытия ошибок не добавлялись.

## Результаты

| Проверка | До исправлений | После, в рабочем проекте |
| --- | --- | --- |
| Kotlin source compiler | 10 предупреждений при перекомпиляции | 0 предупреждений и 0 ошибок |
| :flipper:lintDebug | 2 ошибки MissingPermission, 4 предупреждения | 0 ошибок, 3 GradleDependency |
| :sample:lintDebug | 13 предупреждений | 0 ошибок и предупреждений |
| Native stripping | Missing strip tool / Unable to strip | Успешно, предупреждение отсутствует |
| Unit-тесты | 17 ранее проходивших | 17, 0 ошибок/падений |
| Артефакты | APK собирался; ранее AAR блокировался инструментами | APK и AAR успешно собраны |
| Configuration cache | Был включён настройкой проекта | Обычная сборка прошла, Configuration cache entry stored |

Векторные пути сокращены 995 → 739, 3838 → 2850 и 3642 → 2702 символов; команды и координаты до/после сравнены как токены и совпадают. Подавление VectorPath не использовалось.
Проверка whitespace через git diff --check завершилась с кодом 0; локальный Git отдельно предупреждает о своей политике LF/CRLF, настройки Git не менялись.

Команда итоговой проверки:

```text
.\gradlew.bat --offline --warning-mode all :flipper:assembleDebug :flipper:testDebugUnitTest :sample:assembleDebug :flipper:lintDebug :sample:lintDebug
```

Отчёты Lint после запуска находятся в flipper/build/reports/lint-results-debug.html и sample/build/reports/lint-results-debug.html.
17 тестов: 12 RpcClient, 2 DeviceInfo, 3 framing. Новые тесты для косметических изменений не добавлялись.
Физический BLE-прогон этой проверкой не заменяется; совместимость новой ветки API 37 проверена компиляцией и Lint, на устройстве ещё не запускалась.

## Что осталось осознанно

1. Три GradleDependency у общей версии Hilt 2.57.1: plugin, hilt-android и hilt-compiler; это одна группа обновления, а не три runtime-ошибки. Они относятся к sample и общему version catalog, библиотека :flipper не получила Hilt-зависимость.
2. AGP 8.13.0 предупреждает, что проверен до compileSdk 36.1, тогда как проект использует SDK 37.
3. При чтении новых SDK metadata в одном из прогонов также появлялось сообщение SDK XML 3/4; в финальном рабочем прогоне оно не повторилось.

Hilt 2.59+ требует AGP 9, поэтому замена только номера Hilt ломает текущую комбинацию. Обновление Hilt, AGP, Gradle и Kotlin нужно выполнять согласованно; версии AGP/Gradle/Kotlin/Hilt здесь не менялись. Ограничение подтверждено [release notes Dagger/Hilt 2.59](https://github.com/google/dagger/releases/tag/dagger-2.59).
Предупреждение несовместимого compileSdk не скрывалось через android.suppressUnsupportedCompileSdk, а GradleDependency не отключался.

## Справка по миграциям

- [Kotlin: compilerOptions вместо kotlinOptions](https://kotlinlang.org/docs/gradle-compiler-options.html).
- [AndroidX Hilt 1.3.0: новое расположение hiltViewModel](https://developer.android.com/jetpack/androidx/releases/hilt#1.3.0).
- [BluetoothDevice: connectGatt с settings/executor начиная с API 37](https://developer.android.com/reference/android/bluetooth/BluetoothDevice).
- [BluetoothGattConnectionSettings.Builder: настройка автоматического MTU](https://developer.android.com/reference/android/bluetooth/BluetoothGattConnectionSettings.Builder).

## После добавления прототипа 24.11.2026

sample Lint снова 0/0, flipper Lint 0 ошибок/3 GradleDependency; Kotlin source warnings отсутствуют. В одном incremental kaptDebugKotlin отмечено предупреждение процессора: The following options were not recognized by any processor (dagger.fastInit, Hilt internal options, kapt.kotlin.generated). Сборка завершилась успешно; причина не установлена, предупреждение не подавлялось. Его не смешиваем с прежними исправленными IDE-пометками Bluetooth.
