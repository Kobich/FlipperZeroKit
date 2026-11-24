# Пробная сборка FlipperZeroKit

[Скачать APK](FlipperZeroKit-debug.apk?raw=true).
[Контрольные файлы ZIP](flipper-test-files.zip?raw=true).

- Вариант: sample debug, подписан debug-ключом.
- Исходный коммит: ecb1dd1342ded9f3af564c30b1781de464fdce57.
- Application ID: dev.flipper.zerokit.sample.
- Version: 1.3-prototype, code 4.
- APK SHA-256: 3dc5b8239498fe29d6d1694153eeabc8ccf40231cea2063a058fe8991f9efad3.
- Fixtures ZIP SHA-256: ca590796370a083a4405730a72d985fea6d1be7bb60fd6b2054b70e087945479.

Единая библиотека :flipper включает BLE/RPC, файлы и UI-блок ui; отдельного UI Gradle-модуля нет.
FlipperScreen(flipper) встраивается одним destination в нижний бар хоста. Внутри — «Устройство»/«Файлы», сохранение последнего устройства и reconnect по умолчанию. Хост владеет клиентом и темой; Hilt библиотеке не требуется.

40 тестов с шагами и MD5: [PROTOTYPE.md](../docs/PROTOTYPE.md).
[Контракт единой фичи](../flipper/README.md).
AAR и APK собраны, 29 регрессионных тестов прошли; Lint flipper 0 ошибок/3 прежних предупреждения, sample 0/0.
Предыдущий прогон пользователя подтвердил скачивание. APK после объединения и отправка ещё требуют физической проверки.
