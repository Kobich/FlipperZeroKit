# FlipperZeroKit

Наш отдельный Android-проект для BLE-сопряжения и общения с Flipper Zero.
Основан на наработках коллеги; исходный репозиторий не используется для разработки.

**Фундамент проекта:** [основная задача](docs/TASK.md) и [поэтапный план](docs/ROADMAP.md). Цель — приложение-передатчик с подключением/восстановлением связи, сведениями и зарядом, просмотром файлов, скачиванием и отправкой с прогрессом.

## Модули

- `:flipper` — Android Library: сканирование, сопряжение, GATT, RX/TX, protobuf-схемы и RPC-framing. Без Compose и Hilt.
- `:sample` — приложение для ручной проверки. Compose и Hilt остаются здесь. Есть кнопка ping и реальные сведения DeviceInfo; файлы пока демонстрационные.

Актуальная основа — ветка protobuf-framing (5d4be58), где найдены framing и ping. ble-transport уже влита в main.
В библиотеке доступны getDeviceInfo, sendMessage, messages и nextCommandId. DeviceInfo собирается через внутренний RpcClient; PowerInfo и реальная работа с файлами ещё не реализованы.
Ping перенесён в sample; по проверке пользователя работает, наш физический прогон ещё предстоит.

Структура библиотеки и пример использования: [flipper/README.md](flipper/README.md). Публичный API находится в api, реализация — в internal.

## Подключение и lifecycle

В этом проекте: `implementation(project(":flipper"))`.
Для другого Gradle-проекта можно подключить каталог `flipper` как модуль; его build script использует корневой version catalog, который нужно также предоставить или заменить зависимостями хоста. Публикация Maven-артефакта ещё не настроена.

```kotlin
import dev.flipper.api.FlipperPermissions
import dev.flipper.api.Flipper

val client = Flipper.create(applicationContext)
val permissions = FlipperPermissions.requiredPermissions
// Хост запрашивает runtime-разрешения и включает Bluetooth через системный UI.
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
Сканер пока показывает все именованные BLE-устройства, а не только Flipper.
Успех sendMessage означает завершённые GATT write; ответ RPC нужно отдельно дождаться и проверить. Для ручного API messages не завершается при disconnect и может пропускать старые сообщения у медленных наблюдателей. getDeviceInfo обрабатывает timeout/отмену/потерю сессии внутри модуля.

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
