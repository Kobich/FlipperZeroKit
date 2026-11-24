# Происхождение кода

Проект создан 23 ноября 2026 по запросу владельца окружения на основе [an-onim/Flipper-Zero-Companion](https://github.com/an-onim/Flipper-Zero-Companion).
Это самостоятельный проект с переразделением модулей, а не заявление об оригинальном авторстве перенесённого кода.

Проверены локальные Git refs: main 852ece22e0affa094157aab7dc3854253364369f, ble-transport dff459e866980a2283ba55b7984ed78a36251614 и [protobuf-framing 5d4be58d628b68249cd7d744c445143f4b686618](https://github.com/an-onim/Flipper-Zero-Companion/tree/5d4be58d628b68249cd7d744c445143f4b686618).
Первоначально изучалась main; после уточнения пользователя база обновлена по protobuf-framing.
BLE-компоненты общие для этих веток. Framing, схемы, RPC-приём/отправка и сценарий ping перенесены из protobuf-framing.

В flipper находятся BLE, модели/контракт, утилиты, RpcMessageFramer и восемь proto-файлов.
Текущий Compose UI вынесен в нашу :flipper-ui; в sample — Hilt, тема/DataStore, внешний NavHost/нижний бар и ConnectionSession. Демонстрационные данные и прикладные Flipper use cases удалены.
Изменения относительно исходника перечислены в docs/ARCHITECTURE.md.
Взяты исходные версии зависимостей и текущая локальная версия Gradle wrapper 8.13; ранее существовавшее изменение wrapper properties в исходнике не исправлялось.

История .git, remotes коллеги, .idea, .gradle, build, тесты-заглушки и HTML-документация не копировались.
Git-архив protobuf-framing распаковывался вне исходного репозитория; рабочая ветка коллеги осталась main.
local.properties содержит локальную привязку к SDK и исключён из Git.
В исходном корне LICENSE не найден; отдельная лицензия переносу не назначалась.

После структурного рефакторинга библиотека называется flipper, namespace dev.flipper. Публичный Flipper и модели отделены от internal-реализации; sample сохраняет свой application package.

В нашем проекте добавлены DeviceInfo, FlipperRpcException и внутренний RpcClient: маршрутизация, ожидание полной серии, timeout и отмена по сессии. Исходная закомментированная попытка DeviceInfo заменена собственным API и проверками; firmware-ключи сверены с официальным furi_hal_info.c, см. docs/DEVICE_INFO.md.

24.11.2026: по уточнению интеграции создана flipper-ui с одной точкой FlipperScreen, внутренними вкладками и UI ViewModel без Hilt. Существующие экраны/components перенесены из нашего sample с изменением namespace и resource prefix; BLE/RPC-протокол в этом переносе не менялся.
