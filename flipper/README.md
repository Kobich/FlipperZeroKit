# flipper

Android Library для BLE-сопряжения и RPC-обмена с Flipper Zero.

## Структура

```text
flipper/
  build.gradle.kts
  consumer-rules.pro
  src/main/
    AndroidManifest.xml
    kotlin/dev/flipper/
      api/
        Flipper.kt
        FlipperPermissions.kt
        model/
      internal/
        DefaultFlipper.kt
        ble/
        transport/
        rpc/
        permissions/
        util/
    proto/
  src/test/kotlin/dev/flipper/internal/rpc/
```

api — контракт, разрешения для хоста и публичные модели.
internal — реализация клиента, GATT, транспорт, framing и утилиты; классы скрыты Kotlin internal.
proto — схемы RPC; сгенерированные PB.Main-типы пока являются частью низкоуровневого публичного контракта.
Публичные модели: DeviceInfo, PowerInfo, StorageEntry, TransferProgress, TransferResult, FlipperRpcException. RpcClient маршрутизирует сведения, Ping и Storage по command_id с ограниченным буфером, timeout, проверкой статуса/содержимого и завершения серии.
Тесты находятся в src/test/kotlin. consumer-rules.pro подключён к Android Library для правил R8 потребителя.
UI находится в отдельной Compose-библиотеке flipper-ui; Hilt, Activity, тема и внешняя навигация — в sample/целевом хосте. В core-модуль они не входят.

## Подключение

```kotlin
implementation(project(":flipper"))
```

```kotlin
import dev.flipper.api.Flipper
import dev.flipper.api.FlipperPermissions

val flipper = Flipper.create(applicationContext)
val permissions = FlipperPermissions.requiredPermissions
// Хост запрашивает permissions и управляет жизненным циклом.
// Suspend-вызовы выполняются из coroutine после получения разрешений:
flipper.startScan()
// flipper.connect(selectedDevice)
// После GATT Connected и RPC ACTIVE:
// val info = flipper.getDeviceInfo()
// При завершении владельца:
flipper.close()
```

Состояния доступны через StateFlow; RPC-сообщения — через SharedFlow без replay.
Для типизированных операций подписки снаружи не нужны: модуль сам проверяет ответы, завершение серии, timeout и потерю сессии.
При ручной работе подписка на messages должна появиться до sendMessage; успех отправки не подтверждает выполнение RPC. messages имеет буфер 64 с DROP_OLDEST для медленных наблюдателей.
close окончательно освобождает экземпляр. disconnect оставляет возможность повторного подключения.
Библиотека использует корневой version catalog проекта; при переносе каталога в другое приложение нужно предоставить его зависимости.

## Экспериментальные файловые операции

После Connected + RPC ACTIVE: getPowerInfo(), ping(), listFiles("/ext").
Для downloadFile(path, destination) и uploadFile(path, source, sizeBytes) поток открывает и закрывает хост. sizeBytes может быть null; прогресс считается по содержимому, а успешный TransferResult возвращается после завершающего RPC и сверки MD5. Пустой файл поддерживается.
Все Storage-вызовы сериализованы. Upload предварительно отклоняет существующий путь; атомарное отсутствие перезаписи протокол не гарантирует. Пути абсолютные, ASCII. При ошибке/отмене начавшейся передачи сессия закрывается; частичный файл может остаться. Хост определяет очистку и повтор.

Сценарий проверки, таймауты и ограничения: [PROTOTYPE.md](../docs/PROTOTYPE.md).

## Известные устройства без поиска

getKnownDevices() возвращает KnownDevicesResult.Available(devices) либо Failed(BleFailure). KnownBleDevice содержит BleDevice, isBonded и isConnectedToSystem. Данные объединены по адресу из сохранённых BLE-устройств и системного GATT-профиля. Чтение не меняет текущую сессию, не сканирует и не удаляет bond; connect(device) затем создаёт собственную сессию и переиспользует BOND_BONDED.
Хост запрашивает FlipperPermissions.connectPermissions; requiredPermissions по-прежнему предназначен для поиска. RSSI теперь Int? с null по умолчанию, поскольку для сохранённого адреса измерения нет. Для уже сопряжённого устройства не требуется SCAN/location при прямом подключении. Подключённый системный профиль не гарантирует RPC или право перенять клиент другого приложения.
