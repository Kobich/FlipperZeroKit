# Пробная сборка FlipperZeroKit

[Скачать APK](FlipperZeroKit-debug.apk?raw=true).
[Контрольные файлы ZIP](flipper-test-files.zip?raw=true).

- Вариант: sample debug, подписан debug-ключом.
- Исходный коммит: c12e848d4400cdb8c03b9d3d2ce70b0cfc4ba772.
- Application ID: dev.flipper.zerokit.sample.
- Version: 1.2-prototype, code 3.
- APK SHA-256: cb5393f69ca4ddd46127109432ee0dc27945ef06a3e8ba14ad32180b9c809110.
- Fixtures ZIP SHA-256: ca590796370a083a4405730a72d985fea6d1be7bb60fd6b2054b70e087945479.

Один пункт Flipper в нижнем баре хоста; «Устройство» и «Файлы» — внутренние вкладки встраиваемой библиотеки flipper-ui.
Клиент, Hilt, внешняя навигация и политика reconnect принадлежат хосту; библиотеки не требуют Hilt.

40 сценариев с шагами/ожидаемым результатом и MD5 контрольных файлов: [PROTOTYPE.md](../docs/PROTOTYPE.md).
[Контракт встраивания UI](../flipper-ui/README.md).
APK и обе AAR собраны; 29 регрессионных тестов прошли. Lint sample/ui 0/0, core 0 ошибок/3 прежних предупреждения.
Предыдущий прогон пользователя подтвердил скачивание; новый UI и исходящая передача требуют физической проверки.
