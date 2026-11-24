# Состояние базы — 23 ноября 2026

## Откуда взят код

Проверены доступные локально Git refs репозитория коллеги без переключения его рабочей ветки:

| Ветка | Commit | Что в ней |
| --- | --- | --- |
| main | 852ece22e0affa094157aab7dc3854253364369f | BLE-сопряжение и транспорт, mock-информация, без RPC-framing |
| origin/ble-transport | dff459e866980a2283ba55b7984ed78a36251614 | Уже влита в main; отличия от main только в документации |
| origin/protobuf-framing | 5d4be58d628b68249cd7d744c445143f4b686618 | Полные proto-файлы, framing encode/decode, поток RPC-сообщений, ping |

После уточнения пользователя наша база обновлена по protobuf-framing. Ранние выводы об отсутствии framing относились только к main и не описывают итоговый FlipperZeroKit.
Рабочая ветка коллеги осталась main; ранее существовавшее изменение wrapper properties не трогали.

## Текущее дополнение нашего проекта

В нашем модуле уже реализован getDeviceInfo: регистрация ожидания до send, command_id, проверка статуса/типа, полная серия has_next, timeout и отмена при потере сессии. Sample использует реальные ответы; demo DeviceInfo и заряд удалены. Подробности — в DEVICE_INFO.md.

Разбор ниже сохраняет состояние исходной ветки коллеги на момент знакомства. Он не означает, что наша реализация DeviceInfo всё ещё закомментирована.

## Что было готово в исходной ветке

| Функция | Состояние |
| --- | --- |
| BLE-поиск | 12 секунд, дедупликация адресов, сортировка RSSI; показывает все именованные BLE-устройства, без фильтра Flipper |
| Сопряжение | createBond, ожидание BOND_BONDED, таймаут 45 секунд; уже сохранённый bond используется повторно |
| GATT | Проверка сервиса/характеристик/CCCD, таймаут 25 секунд, последовательные подписки TX → FLOW_CONTROL → RPC_STATUS |
| RPC-ready | Чтение RPC_STATUS и уведомления; отдельно от состояния GATT Connected |
| Отправка | Mutex, MTU, flow-control credits, ожидание write callback и таймауты |
| Приём | Ограниченный Channel TX-фрагментов, один decoder на GATT-сессию, остановка при disconnect |
| Protobuf | Набор из восьми схем, PB.Main, varint-delimited encode/decode |
| Framing | Частичный varint/тело, несколько сообщений в одном фрагменте, предел кадра 1 MiB, проверки длины и незаконченного кадра |
| Входящие RPC | Декодирование на Dispatchers.Default, логирование, публикация в SharedFlow |
| Ping | PingUseCase и кнопка в sample найдены в protobuf-framing. Работает по проверке пользователя; мы на устройстве не запускали |
| Информация об устройстве | Попытка запроса закомментирована; метод возвращает demo-данные |
| Файлы | MockFlipperFileRepository, реального Storage.List/read/write нет |
| UI | Compose-экраны, тема/DataStore, Hilt; находятся только в sample |

## Почему не получалась информация в исходной ветке

В актуальном GetFlipperInfoUseCase весь запрос DeviceInfo, подписка на ответы и сбор key/value закомментированы.
Исполняемый код возвращает firmware 1.2.0 (demo), DEMO-00001 и заряд 85. Поэтому в этой версии реальная информация вообще не запрашивается.

В закомментированной попытке дополнительно видны проблемы, которые нужно исправить при реализации:

1. Проверка результата sendMessage перевёрнута: ошибка логируется при failure == null, то есть при успехе.
2. После завершения сбора повторно вызывается sendMessage с тем же command_id. Это лишняя отправка.
3. У ожидания first { !hasNext } нет timeout. SharedFlow не завершится при disconnect, поэтому ожидание может зависнуть.
4. Нет отдельной обработки PowerInfo и перевода реальных ключей firmware в FlipperDeviceInfo; demo.* ключи из main к настоящему ответу не относятся.

Это наблюдения о неактивном коде, а не подтверждение того, что перечисленные строки сейчас выполняются.

## Чего не хватало в исходной ветке поверх framing

- PingUseCase проверяет результат отправки, но сам не ждёт и не валидирует PingResponse. Ответ виден через общий поток/логи.
- Общего RpcClient с маршрутизацией command_id, timeout, command_status и отменой запросов при разрыве пока нет.
- messages — поток без replay. Без подписчика ответы теряются; подписка должна появляться до sendMessage.
- Медленный подписчик задерживает emit и может заполнить входной Channel; политика backpressure ещё не определена.
- В исходной ветке наружу были выданы MutableSharedFlow и AtomicInteger. В нашем контракте поток только для чтения, ID выдаётся методом nextCommandId().
- Схемы перенесены из конкретного commit проекта коллеги, но их upstream-версия/совместимость с firmware девайса ещё не зафиксирована.
- MTU обновляется по callback, но requestMtu не вызывается: при исходном MTU 23 полезная нагрузка составляет 20 байт.

## Что учитывать в протоколе

DeviceInfo и PowerInfo приходят сериями key/value с одним command_id; собирать их нужно до has_next=false. Это видно в [rpc_system.c](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/applications/services/rpc/rpc_system.c).
Оболочка команд описана в [flipper.proto](https://github.com/flipperdevices/flipperzero-protobuf/blob/dev/flipper.proto), delimited формат — в [rpc.c](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/applications/services/rpc/rpc.c).
BIG_ENDIAN для FLOW_CONTROL соответствует firmware, которая переставляет байты uint32 перед публикацией: [serial_service.c](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/ble_glue/services/serial_service.c).
Ветви dev этих upstream-источников просмотрены 23.11.2026 как справка, не как закреплённая версия реализации.
