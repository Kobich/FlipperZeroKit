# Архитектура

## Модули

- :flipper — единая Android Library фичи, namespace dev.flipper: api/model, internal BLE/RPC/transport и ui/ с Compose-экраном FlipperScreen.
- :sample — демонстрационный хост: Hilt, тема/DataStore, внешний NavHost/нижний бар и владелец Flipper.

По последнему уточнению пользователя отдельного Gradle-модуля UI нет. Внутреннее разделение — пакетами; библиотека не требует Hilt, не создаёт Activity и глобальную навигацию.

## Публичная поверхность

Вход — dev.flipper.api.Flipper.create(context). Он сохраняет applicationContext и возвращает контракт Flipper.
В api/model находятся BleDevice, BleConnectionState, BleFailure, RpcStatus, DeviceInfo и FlipperRpcException.
FlipperPermissions предоставляет runtime-разрешения, которые запрашивает хост.
StateFlow описывает connection/rpcStatus/discoveredDevices/isScanning/isRecovering/pendingFileCleanup, SharedFlow messages публикует входящие PB.Main.
Доступны getDeviceInfo(timeoutMillis), nextCommandId(), sendMessage(), поиск, connect/disconnect и окончательный close().

Потребитель зависит от api и api/model. Ссылка на внутреннюю реализацию есть только внутри фабрики Flipper.create; тип DefaultFlipper наружу не возвращается.
Прежние BleConnectionRepository и отдельная фабрика FlipperZeroClient удалены из нашего API.
Сгенерированные protobuf-типы пока экспортируются вместе с protobuf-javalite: это существующий низкоуровневый RPC-контракт, есть типизированные операции DeviceInfo, PowerInfo, Ping и Storage (List/Stat/Read/Write/Md5sum/Delete/Rename).

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
Для сохранённой пары клиент использует объект Android из списка сопряжённых, если он доступен, и открывает GATT с `autoConnect=true`. Подготовка GATT ограничена 30 с. Сразу после нового сопряжения используется прямое подключение с таймаутом 25 с. Ручное отключение или истечение таймаута закрывает текущий GATT.
После установки BLE-связи клиент запрашивает MTU 512 и использует согласованный размер из callback. Обнаружение сервисов продолжается и при отказе, ошибке или отсутствии callback за 3 с.
Connected означает завершённые GATT-подписки. Клиент читает `RPC_STATUS` и `FLOW_CONTROL`, затем отправляет Ping в эту GATT-сессию. Публичный `rpcStatus` становится `ACTIVE` после валидного `PingResponse`.
Успешный sendMessage означает завершённые GATT write. messages без replay не завершается при disconnect и использует буфер 64 с DROP_OLDEST. Внутренний RpcClient получает ответы до публикации в messages, поэтому медленный внешний наблюдатель не задерживает DeviceInfo.
getDeviceInfo проверяет readiness, отправляет запрос в исходную GATT-сессию и ожидает полную серию с timeout. Disconnect/RPC NOT_ACTIVE завершает ожидающие запросы ошибкой; caller cancellation сохраняется как отмена.

BLE/framing и схемы перенесены из protobuf-framing проекта коллеги. Состояние и ограничения DeviceInfo описаны в BASELINE.md.
Реализован пробный сквозной сценарий DeviceInfo/PowerInfo/Ping/Storage. Storage сериализован; URI/потоками телефона управляет блок ui внутри :flipper через application ContentResolver, клиент связан с GATT-сессией. ConnectionViewModel внутри ui/internal/connection сразу сохраняет ручной выбор и выполняет до трёх попыток при входе или обрыве видимой фичи. Для автоматической попытки проверяется сохранённое сопряжение. Хост может заменить политику своим controller. Физический прогон и ограничения — в PROTOTYPE.md; сведения — в DEVICE_INFO.md.

## Встраиваемая UI-фича

Одна внешняя точка FlipperScreen — общий LazyColumn с блоками устройства/передачи и строками файлов. ViewModelStoreOwner — внешний NavBackStackEntry; job передачи принадлежит TransferManager/TransferService и переживает уход с destination в живом процессе. FlipperConnectionController — необязательное переопределение connect/disconnect и lifecycle entry/leave с учётом конфигурации. Без него UI использует встроенную ConnectionViewModel с сохранением последнего устройства и серией reconnect 0/2/6 с, cooldown 60 с после провала. Пример и контракт — flipper/README.md.

Тема, глобальная навигация и lifetime Flipper принадлежат хосту. В едином flipper используется Compose, но BLE/RPC-пакеты не зависят от UI. Hilt модулю не нужен. Ресурсы UI имеют префикс flipper_ для слияния с хостом. ViewModel не хранит Activity/Context.

## Передача, отмена и очистка

TransferManager использует application ContentResolver и отдельный SupervisorJob; UI ViewModel наблюдает StateFlow. connectedDevice foreground service стартует только из видимого пользовательского действия, показывает уведомление/отмену и удерживает CPU lease только во время операции. Клиент Flipper остаётся у хоста и должен жить до окончания операции; close/disconnect имеют приоритет. После force stop/смерти процесса данные автоматически не передаются заново.

Общий лимит Read/Write снят (timeoutMillis=0); потоковый RPC проверяет отсутствие активности 60 с, GATT/metadata сохраняют технические таймауты. MD5 и Rename могут длиться дольше: отдельного лимита нет, отмена и потеря сессии завершают ожидание. RPC-ready ограничен 15 с после CCCD.

RemoteUpload подбирает свободное имя, записывает UUID .part, проверяет MD5, повторно подбирает окончательное имя и выполняет Rename с последующей проверкой конечной копии. Firmware может реализовывать Rename как copy/delete, поэтому публикация требует дополнительного места и не атомарна относительно других клиентов. Storage-вызовы нашего клиента сериализованы.

Durable журнал PendingCleanupStore записывает адрес устройства и только пути текущего upload до их создания; очистка повторяется на RPC-ready. Отмена upload защищает целый protobuf-кадр, затем Delete сбрасывает continuous write. Для Read/неисправной сессии выполняется ограниченное reconnect с проверкой актуального выбора пользователя. Локальный LocalDownloadCleanup хранит только URI созданных нами SAF-документов и добавленные нами persisted grants; удаление/освобождение чужих разрешений не выполняется. При недоступном устройстве/провайдере очистка остаётся ожидающей и видна в UI.

Политики и границы приёмки — TASK.md §7; тесты — PROTOTYPE.md A/B/U/P/F.
