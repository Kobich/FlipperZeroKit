# FlipperZeroKit

Наш отдельный Android-проект для BLE-сопряжения и общения с Flipper Zero.
Основан на наработках коллеги; исходный репозиторий не используется для разработки.

**Фундамент проекта:** [основная задача](docs/TASK.md) и [поэтапный план](docs/ROADMAP.md). Цель — приложение-передатчик с подключением/восстановлением связи, сведениями и зарядом, просмотром файлов, скачиванием и отправкой с прогрессом.

## Модули

- `:flipper` — единая Android Library фичи: BLE/GATT/RPC, файловый обмен и Compose UI в пакете ui. Публичные входы: dev.flipper.api.Flipper и dev.flipper.ui.FlipperScreen. Без Hilt, Activity и глобальной навигации.
- `:sample` — демонстрационный хост с одним пунктом Flipper на нижнем баре. Здесь Hilt, тема и владелец клиента; он встраивает FlipperScreen как destination. Стандартное сохранение устройства/reconnect находится внутри фичи.

Актуальная основа — ветка protobuf-framing (5d4be58), где найдены framing и ping. ble-transport уже влита в main.
В библиотеке доступны getDeviceInfo, getPowerInfo, ping, listFiles, downloadFile, uploadFile и raw RPC. Внутренний RpcClient маршрутизирует ответы по command_id; файловые серии передаются потоком. В версии 1.4 добавлены автонумерация имён, очистка частичных файлов, ограниченный reconnect, foreground-передача и общий экран с блоками. [Таблица принятых решений](docs/TASK.md#7-решения-пользователя-и-выбранная-реализация--24112026). Физическая приёмка новых правил ещё предстоит.
Пользователь сообщил о первом успешном прогоне на Android 16 и отдельно подтвердил скачивание. Отправку пока не проверял; autoreconnect и ошибки требуют отдельного прогона. Статус и ближайшая работа — [ROADMAP.md](docs/ROADMAP.md), доказательства — [VALIDATION.md](docs/VALIDATION.md).

**Как сразу проверить:** [скачать APK 1.11](artifacts/FlipperZeroKit-debug.apk?raw=true) и открыть [46 тестов с шагами, крайними случаями и ограничениями](docs/PROTOTYPE.md). Контрольные файлы — test-data/manual, архив — artifacts/flipper-test-files.zip.

API и встраивание UI: [flipper/README.md](flipper/README.md). Приложение-хост подключает одну библиотеку :flipper и владеет клиентом/внешним нижним баром.

## Подключение и lifecycle

В этом проекте: `implementation(project(":flipper"))`.
Для другого Gradle-проекта можно подключить каталог `flipper` как модуль; его build script использует корневой version catalog, который нужно также предоставить или заменить зависимостями хоста. Публикация Maven-артефакта ещё не настроена.

```kotlin
import dev.flipper.api.FlipperPermissions
import dev.flipper.api.Flipper

val client = Flipper.create(applicationContext)
val permissions = FlipperPermissions.requiredPermissions // для поиска
// FlipperPermissions.connectPermissions — только список известных/прямое подключение
// Хост запрашивает runtime-разрешения и включает Bluetooth через системный UI.
// Для известного устройства можно обойтись без сканирования:
// val known = client.getKnownDevices() // KnownDevicesResult: сопряжённые + системный GATT
// Ниже — вызовы из coroutine после получения разрешений:
client.startScan()
// client.discoveredDevices: StateFlow<List<BleDevice>>
// client.connect(selectedDevice)
// client.connection и client.rpcStatus — отдельные состояния.
// После Connected + RPC ACTIVE:
// val info = client.getDeviceInfo() // версия, железо, UID и вся карта полей
// Для ручного sendMessage подписаться на messages нужно ДО отправки.
client.stopScan()
client.disconnect() // Можно подключиться снова.
client.close()      // Окончательное освобождение; после него нужен новый клиент.
```

Владелец клиента — хост. Один экземпляр на одну управляемую сессию; не создавать клиент при каждой рекомпозиции.
В sample он живёт в Hilt SingletonComponent до завершения процесса.
`Connected` означает завершение GATT-подписок; готовность RPC проверяется отдельно по `rpcStatus`.
На экране есть отдельный список уже известных устройств: сопряжённые BLE и текущие GATT-подключения Android. Их можно выбирать без общего поиска; существующее сопряжение переиспользуется. Системный статус подключения отделён от RPC-готовности приложения. Сканер новых устройств пока показывает все именованные BLE-устройства, а не только Flipper.
Успех sendMessage означает завершённые GATT write; ответ RPC нужно отдельно дождаться и проверить. Для ручного API messages не завершается при disconnect и может пропускать старые сообщения у медленных наблюдателей. Типизированные операции обрабатывают timeout/отмену/потерю сессии внутри модуля. Upload старается отмениться на границе кадра и удалить незавершённый файл без закрытия GATT. Для Read/ошибки транспорта сессия сбрасывается и восстанавливается; неудавшаяся очистка хранится до подключения. Передача имеет foreground service с уведомлением и отменой; общий лимит длительности удалён.

## Сборка

Стек сохранён из исходной базы: AGP 8.13.0, Gradle 8.13, Kotlin 2.2.20, compileSdk/targetSdk 37, minSdk 23. Нужен JDK 17+; проверка выполняется на JDK 21.
В Android Studio откройте корень проекта, выберите Gradle JDK и укажите установленный Android SDK. Для упаковки native-зависимостей sample закреплён NDK 28.2.13676358 (r28c); он должен быть установлен через SDK Manager.

```text
.\gradlew.bat :flipper:testDebugUnitTest :sample:assembleDebug
```

## Записи о проекте

- [Основная задача, исходный снимок и критерии приёмки](docs/TASK.md)

- [Исходная база и история найденных ограничений](docs/BASELINE.md)
- [Границы библиотеки и изменения при переносе](docs/ARCHITECTURE.md)
- [Очередность дальнейших работ и критерии готовности](docs/ROADMAP.md)
- [DeviceInfo: API, ошибки и проверка на Android 16](docs/DEVICE_INFO.md)
- [Результаты проверки](docs/VALIDATION.md)
- [Исправленные и оставшиеся предупреждения](docs/WARNINGS.md)
- [Происхождение кода](SOURCE_PROVENANCE.md)
