# Пробная сборка FlipperZeroKit

[Скачать APK](FlipperZeroKit-debug.apk?raw=true).

- Вариант: sample debug, подписан debug-ключом.
- Исходный коммит: eff9de9689785a4bb70eb97d0781500c4b95ba22.
- Application ID: dev.flipper.zerokit.sample.
- Version: 1.1-prototype, code 2.
- SHA-256: 64ec9f2df6323096a627522cc0c0091b0c1b282368ae9917f69a7e5530a1ce5e.

Добавлен выбор сопряжённого или системно подключённого Flipper без общего BLE-поиска: «Уже известные устройства».
Системное GATT-подключение и готовая RPC-сессия приложения отображаются раздельно; существующая пара переиспользуется.

APK скопирован после успешного assembleDebug; 29 регрессионных тестов и оба Lint прошли (sample 0/0, flipper 0 ошибок/3 прежних предупреждения).
Инструкция проверки и ограничения: [PROTOTYPE.md](../docs/PROTOTYPE.md).
Физическая проверка на Flipper ещё предстоит.
