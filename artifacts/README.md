# Пробная сборка FlipperZeroKit

[Скачать APK](FlipperZeroKit-debug.apk?raw=true).
[Контрольные файлы ZIP](flipper-test-files.zip?raw=true).

- Вариант: sample debug, подписан debug-ключом.
- Исходный коммит: e0e1321f2c8cb04ad287bc9ecc21a95117a8d083.
- Application ID: dev.flipper.zerokit.sample.
- Version: 1.4-prototype, code 5.
- APK SHA-256: bf3351c757be37f74e33a7f5da6f307f7308bdc4c1c955ff4c0a77d1527025d9.
- Fixtures ZIP SHA-256: ca590796370a083a4405730a72d985fea6d1be7bb60fd6b2054b70e087945479.

Единая библиотека :flipper включает BLE/RPC, файлы и UI-блок ui; отдельного UI Gradle-модуля нет. FlipperScreen(flipper) — один общий экран с текущими блоками устройства/передачи/файлов.

В 1.4 добавлены автонумерация (2)/(3), журналы очистки partial, мягкая отмена upload с fallback восстановления, foreground connectedDevice-передача с уведомлением и отменой, ограниченный reconnect без фонового polling. Общего лимита длительности передачи нет; технические таймауты обмена сохраняются. Клиент хоста должен жить до конца операции.

[Таблица решений и остатка](../docs/TASK.md#7-решения-пользователя-и-выбранная-реализация--24112026).
[46 тестов с шагами и MD5](../docs/PROTOTYPE.md).
[Контракт единой фичи](../flipper/README.md).

AAR и APK собраны, 39 unit-тестов прошли; Lint flipper 0 ошибок/3 прежних предупреждения, sample 0/0. Предыдущий прогон пользователя подтвердил скачивание. Новые фон/отмена/recovery и отправка требуют физического прогона Android 16; force stop/смерть процесса не поддерживают resume, остаётся последующая очистка journals.
