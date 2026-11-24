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
DeviceInfo и FlipperRpcException входят в публичные модели. Внутренний RpcClient обслуживает серию ответов DeviceInfo.
Тесты находятся в src/test/kotlin. consumer-rules.pro подключён к Android Library для правил R8 потребителя.
UI, Hilt, Activity, тема и демонстрационные данные находятся в соседнем sample и не входят в модуль.

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
Для getDeviceInfo подписки снаружи не нужны: модуль сам проверяет ответы, завершение серии, timeout и потерю сессии.
При ручной работе подписка на messages должна появиться до sendMessage; успех отправки не подтверждает выполнение RPC. messages имеет буфер 64 с DROP_OLDEST для медленных наблюдателей.
close окончательно освобождает экземпляр. disconnect оставляет возможность повторного подключения.
Библиотека использует корневой version catalog проекта; при переносе каталога в другое приложение нужно предоставить его зависимости.
