# DeviceInfo

В :flipper реализован suspend API getDeviceInfo(timeoutMillis = 10_000).
Он требует GATT Connected и RPC_STATUS=ACTIVE; sample автоматически вызывает его после готовности RPC, затем позволяет повторить запрос кнопкой «Обновить данные».

## Что возвращается

DeviceInfo.fields содержит все переданные устройством key/value, включая неизвестные ключи кастомной прошивки.
Удобные nullable-свойства: firmwareVersion, firmwareBranch, hardwareVersion, hardwareModel, hardwareUid, name.
Основные ключи официального RPC: firmware_version, firmware_branch, hardware_ver, hardware_model, hardware_uid и hardware_name. Также поддержаны dotted aliases; отсутствие поля не ломает результат.
UID показывается как UID, не как серийный номер. Заряд не входит в эту операцию: demo-заряд удалён, PowerInfo реализован отдельным запросом/моделью и показан в sample; его ошибка не скрывает DeviceInfo. Общий пробный прогон — PROTOTYPE.md.
В sample кнопка «Все поля DeviceInfo» раскрывает исходную карту для диагностики неизвестной прошивки.

Поля сверены с [furi_hal_info.c](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/targets/f7/furi_hal/furi_hal_info.c).
Серия ответов и завершение has_next проверены по [rpc_system.c](https://github.com/flipperdevices/flipperzero-firmware/blob/dev/applications/services/rpc/rpc_system.c).
Ветви dev просмотрены 23.11.2026; версия firmware физического Flipper пока неизвестна.

## Запрос и ошибки

Ожидание регистрируется до единственной отправки. Ответы маршрутизируются по command_id, проверяются command_status и тип content, накапливаются до has_next=false.
Таймаут охватывает отправку и ожидание всей серии; частичный результат не возвращается. Повторный запрос получает новый ID, поздние ответы старого игнорируются.
Отмена coroutine отменяет отправку/ожидание. Disconnect, close или RPC NOT_ACTIVE немедленно завершают ожидающие запросы ошибкой SESSION_CLOSED.
Отправка привязана к исходному BluetoothGatt: старый запрос не должен попасть в новое соединение после reconnect.
Слишком быстрый ответ не отменяет ожидание уже начатого GATT write callback.

FlipperRpcException.reason: NOT_READY, SESSION_CLOSED, SEND_FAILED, REMOTE_ERROR, UNEXPECTED_RESPONSE, TIMEOUT.
Для REMOTE_ERROR сохранён числовой statusCode, для SEND_FAILED — transportFailure.
Sample показывает соответствующее сообщение вместо demo и отменяет предыдущую загрузку при refresh/потере готовности RPC.

Сырые messages остаются без replay, с буфером на 64 сообщения и DROP_OLDEST для медленных наблюдателей. Они не блокируют внутреннюю маршрутизацию DeviceInfo.
Для получения сведений следует вызывать getDeviceInfo, а не собирать ответы через внешний messages.

## Проверка на Android 16

Android 16 указан пользователем; модель телефона и firmware неизвестны, для реализации это не требуется.

1. Запустить sample, выдать разрешения Bluetooth и подключить Flipper.
2. После RPC-ready должны появиться настоящие версия/железо/UID. Отсутствующие поля отображаются как «Не передано устройством».
3. Раскрыть «Все поля DeviceInfo»; проверить версию и ветку прошивки, сохранить список неизвестных полей при необходимости.
4. Нажать «Обновить данные», затем отключить/подключить устройство и повторить.
5. При ошибке отметить показанное сообщение, этап (GATT или RPC-ready) и лог DeviceViewModel/Flipper. Не считать успешную сборку подтверждением физической работы BLE.

Физическая проверка нами ещё не проведена. Результаты unit-тестов и сборки — в VALIDATION.md.
