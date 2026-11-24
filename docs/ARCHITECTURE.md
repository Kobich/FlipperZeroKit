# Архитектура

## Модули

- :flipper — единая Android Library фичи, namespace dev.flipper: api/model, internal BLE/RPC/transport и ui/ с Compose-экраном FlipperScreen.
- :sample — демонстрационный хост: Hilt, тема/DataStore, внешний NavHost/нижний бар и владелец Flipper.

По последнему уточнению пользователя отдельного Gradle-модуля UI нет. Внутреннее разделение — пакетами; библиотека не требует Hilt, не создаёт Activity и глобальную навигацию.

## Публичная поверхность

Вход — dev.flipper.api.Flipper.create(context). Он сохраняет applicationContext и возвращает контракт Flipper.
В api/model находятся BleDevice, BleConnectionState, BleFailure, RpcStatus, DeviceInfo и FlipperRpcException.
FlipperPermissions предоставляет runtime-разрешения, которые запрашивает хост.
StateFlow описывает connection/rpcStatus/discoveredDevices/isScanning, SharedFlow messages публикует входящие PB.Main.
Доступны getDeviceInfo(timeoutMillis), nextCommandId(), sendMessage(), поиск, connect/disconnect и окончательный close().

Потребитель зависит от api и api/model. Ссылка на внутреннюю реализацию есть только внутри фабрики Flipper.create; тип DefaultFlipper наружу не возвращается.
Прежние BleConnectionRepository и отдельная фабрика FlipperZeroClient удалены из нашего API.
Сгенерированные protobuf-типы пока экспортируются вместе с protobuf-javalite: это существующий низкоуровневый RPC-контракт, есть типизированные операции DeviceInfo, PowerInfo, Ping и Storage (List/Stat/Read/Write/Md5sum).

## Реализация

Код библиотеки находится в src/main/kotlin/dev/flipper:

- internal/DefaultFlipper — владелец клиента, состояний и GATT-сессии.
- internal/ble — поиск, pairing, профиль и подписки GATT.
- internal/transport — передача RX с MTU/flow control и приём TX.
- internal/rpc — varint-delimited RpcMessageFramer и RpcClient с регистрацией ожиданий до send, маршрутизацией command_id и сборкой DeviceInfo.
- internal/permissions — проверки разрешений и готовности Bluetooth.
- internal/util — внутренние расширения байтов.

Классы реализации имеют internal visibility. Прикладные слои data/domain/repository в библиотеке убраны.
В src/main/proto находятся восемь схем RPC; генерация выполняется только в :flipper.
Тесты framing находятся в src/test/kotlin/dev/flipper/internal/rpc.
consumer-rules.pro подключён через consumerProguardFiles; приложение не должно получать настройки собственного application-модуля из библиотеки.

## Lifecycle и протокол

Хост запрашивает разрешения, показывает системный UI и определяет срок жизни клиента.
close останавливает поиск/ожидание bond, закрывает GATT/receiver, отменяет RPC-приём и CoroutineScope; после него нужен новый экземпляр.
Очередь GATT-подписок принадлежит экземпляру; для API 23–25 есть доступная версия connectGatt, для API 23–27 — проверка геолокации. Callbacks передаются на main.
Connected означает завершённые GATT-подписки; RPC_STATUS=ACTIVE проверяется отдельно.
Успешный sendMessage означает завершённые GATT write. messages без replay не завершается при disconnect и использует буфер 64 с DROP_OLDEST. Внутренний RpcClient получает ответы до публикации в messages, поэтому медленный внешний наблюдатель не задерживает DeviceInfo.
getDeviceInfo проверяет readiness, отправляет запрос в исходную GATT-сессию и ожидает полную серию с timeout. Disconnect/RPC NOT_ACTIVE завершает ожидающие запросы ошибкой; caller cancellation сохраняется как отмена.

BLE/framing и схемы перенесены из protobuf-framing проекта коллеги. Состояние и ограничения DeviceInfo описаны в BASELINE.md.
Реализован пробный сквозной сценарий DeviceInfo/PowerInfo/Ping/Storage. Storage сериализован; URI/потоками телефона управляет блок ui внутри :flipper через application ContentResolver, клиент связан с GATT-сессией. ConnectionViewModel внутри ui/internal/connection сохраняет последнее успешно подключённое устройство и делает одну попытку при входе/возврате во внешнее destination фичи; пользовательский controller может заменить политику. Физический прогон и ограничения — в PROTOTYPE.md; сведения — в DEVICE_INFO.md.

## Встраиваемая UI-фича

Одна внешняя точка FlipperScreen, две внутренние вкладки Device/Files. ViewModelStoreOwner — внешний NavBackStackEntry, поэтому внутреннее переключение не уничтожает передачу. FlipperConnectionController — необязательное переопределение connect/disconnect и lifecycle entry/leave с учётом конфигурации. Без него UI использует встроенную ConnectionViewModel с сохранением последнего устройства и одной попыткой reconnect. Пример и контракт — flipper/README.md.

Тема, глобальная навигация и lifetime Flipper принадлежат хосту. В едином flipper используется Compose, но BLE/RPC-пакеты не зависят от UI. Hilt модулю не нужен. Ресурсы UI имеют префикс flipper_ для слияния с хостом. ViewModel не хранит Activity/Context.
