# Архитектура

## Модули

- :flipper — Android Library: BLE-сопряжение, GATT, RX/TX, protobuf и RPC-framing. Namespace dev.flipper.
- :sample — отдельное диагностическое приложение: UI, Hilt, тема/DataStore, кнопка ping и демонстрационные сведения/файлы.

## Публичная поверхность

Вход — dev.flipper.api.Flipper.create(context). Он сохраняет applicationContext и возвращает контракт Flipper.
В api/model находятся BleDevice, BleConnectionState, BleFailure, RpcStatus, DeviceInfo и FlipperRpcException.
FlipperPermissions предоставляет runtime-разрешения, которые запрашивает хост.
StateFlow описывает connection/rpcStatus/discoveredDevices/isScanning, SharedFlow messages публикует входящие PB.Main.
Доступны getDeviceInfo(timeoutMillis), nextCommandId(), sendMessage(), поиск, connect/disconnect и окончательный close().

Потребитель зависит от api и api/model. Ссылка на внутреннюю реализацию есть только внутри фабрики Flipper.create; тип DefaultFlipper наружу не возвращается.
Прежние BleConnectionRepository и отдельная фабрика FlipperZeroClient удалены из нашего API.
Сгенерированные protobuf-типы пока экспортируются вместе с protobuf-javalite: это существующий низкоуровневый RPC-контракт, для DeviceInfo уже есть типизированная операция; расширение на остальные RPC ещё предстоит.

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
DeviceInfo реализован; следующий функциональный шаг после физической проверки — PowerInfo и затем Storage. Детали реализации и чек-лист — в DEVICE_INFO.md.
