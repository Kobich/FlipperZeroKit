# flipper

Единая Android Library фичи Flipper: BLE-сопряжение, RPC/файловый обмен и встраиваемый Compose UI. Хост подключает только :flipper.

## Структура

```text
flipper/
  build.gradle.kts
  consumer-rules.pro
  src/main/
    AndroidManifest.xml
    kotlin/dev/flipper/
      api/
        Flipper.kt
        FlipperPermissions.kt
        model/
      ui/
        FlipperScreen.kt
        FlipperConnectionController.kt
        internal/
      internal/
        DefaultFlipper.kt
        ble/
        transport/
        rpc/
        permissions/
        util/
    proto/
  src/test/kotlin/dev/flipper/internal/rpc/
```

api — контракт, разрешения для хоста и публичные модели.
internal — реализация клиента, GATT, транспорт, framing и утилиты; классы скрыты Kotlin internal.
proto — схемы RPC; сгенерированные PB.Main-типы пока являются частью низкоуровневого публичного контракта.
Публичные модели: DeviceInfo, PowerInfo, StorageEntry, TransferProgress, TransferResult, FlipperRpcException. RpcClient маршрутизирует сведения, Ping и Storage по command_id с ограниченным буфером, timeout, проверкой статуса/содержимого и завершения серии.
Тесты находятся в src/test/kotlin. consumer-rules.pro подключён к Android Library для правил R8 потребителя.
UI находится в ui/ внутри этой же библиотеки; Hilt, Activity, тема и внешняя навигация — в sample/целевом хосте. BLE/RPC-пакеты не зависят от UI.

## Подключение

```kotlin
implementation(project(":flipper"))
```

```kotlin
import dev.flipper.api.Flipper
import dev.flipper.api.FlipperPermissions

val flipper = Flipper.create(applicationContext)
val permissions = FlipperPermissions.requiredPermissions
// Хост запрашивает permissions и управляет жизненным циклом.
// Suspend-вызовы выполняются из coroutine после получения разрешений:
flipper.startScan()
// flipper.connect(selectedDevice)
// После GATT Connected и RPC ACTIVE:
// val info = flipper.getDeviceInfo()
// При завершении владельца:
flipper.close()
```

Состояния доступны через StateFlow; RPC-сообщения — через SharedFlow без replay.
Для типизированных операций подписки снаружи не нужны: модуль сам проверяет ответы, завершение серии, timeout и потерю сессии.
При ручной работе подписка на messages должна появиться до sendMessage; успех отправки не подтверждает выполнение RPC. messages имеет буфер 64 с DROP_OLDEST для медленных наблюдателей.
close окончательно освобождает экземпляр. disconnect оставляет возможность повторного подключения.
Библиотека использует корневой version catalog проекта; при переносе каталога в другое приложение нужно предоставить его зависимости.

## Экспериментальные файловые операции

После Connected + RPC ACTIVE: getPowerInfo(), ping(), listFiles("/ext").
Для downloadFile(path, destination) и uploadFile(path, source, sizeBytes) поток открывает и закрывает вызывающий код. sizeBytes может быть null. TransferResult содержит bytesTransferred, md5 и фактический path; upload подбирает свободное имя (2), (3)… и проверяет временную и финальную копию. Пустой файл поддерживается.
Все Storage-вызовы сериализованы. Upload использует UUID-временный файл, а после проверки публикует свободное имя. Файловый Rename в firmware может быть copy/delete; атомарной защиты от параллельного внешнего писателя RPC не даёт. При ошибке удаляются только зарегистрированные нами provisional-пути; offline-очистка хранится по адресу устройства в pendingFileCleanup и запускается на RPC-ready. Для прямого downloadFile(OutputStream) локальную очистку выполняет вызывающий код; готовый UI делает это сам через SAF-журнал. Пути абсолютные ASCII.

Сценарий проверки, таймауты и ограничения: [PROTOTYPE.md](../docs/PROTOTYPE.md).

## Известные устройства без поиска

getKnownDevices() возвращает KnownDevicesResult.Available(devices) либо Failed(BleFailure). KnownBleDevice содержит BleDevice, isBonded и isConnectedToSystem. Данные объединены по адресу из сохранённых BLE-устройств и системного GATT-профиля. Чтение не меняет текущую сессию, не сканирует и не удаляет bond; connect(device) затем создаёт собственную сессию и переиспользует BOND_BONDED.
Хост запрашивает FlipperPermissions.connectPermissions; requiredPermissions по-прежнему предназначен для поиска. RSSI теперь Int? с null по умолчанию, поскольку для сохранённого адреса измерения нет. Для уже сопряжённого устройства не требуется SCAN/location при прямом подключении. Подключённый системный профиль не гарантирует RPC или право перенять клиент другого приложения.

## Встраивание UI

UI-блок внутри единого модуля :flipper: одна точка входа dev.flipper.ui.FlipperScreen, с общими блоками устройства и файлов. Нижний бар, верхняя панель, MaterialTheme и NavController принадлежат хосту. Библиотека не содержит Activity, Hilt и собственного NavHost.

### Подключение готового экрана

```kotlin
implementation(project(":flipper"))
```

Подключается один модуль :flipper с BLE/RPC и UI. При переносе в другой Gradle-проект нужно предоставить его version catalog/зависимости; Maven-публикация пока не настроена. Для готового экрана нужен Compose-хост.

```kotlin
// Клиент создаётся один раз владельцем, а не внутри рекомпозиции.
val flipper: Flipper = /* host-owned client */

NavHost(navController, startDestination = "home") {
    composable("home") { /* экран хоста */ }
    composable("flipper") {
        FlipperScreen(
            flipper = flipper,
        )
    }
}
// В нижнем баре хоста — один пункт, ведущий на route="flipper".
```

### Владение состоянием UI

- Один стабильный Flipper и controller на время жизни destination. Использовать ViewModelStoreOwner внешнего NavBackStackEntry; не создавать отдельный owner на каждый блок экрана.
- Один общий LazyColumn содержит блок устройства, состояние/выбор передачи и каталог. UI ViewModel привязаны к destination, но файл передаёт TransferManager/TransferService; уничтожение UI не отменяет сервисную операцию в живом процессе.
- Back принадлежит хосту; переход вверх по каталогу выполняется явной кнопкой. Внутренних вкладок и отдельного back stack больше нет.
- Файловая операция начинается из видимого UI и продолжает работу через connectedDevice foreground service с уведомлением/отменой. Смена destination, сворачивание и блокировка не отменяют её. Force stop/смерть процесса останавливают работу; restart/resume данных нет, незавершённые файлы очищаются через журналы.
- Клиент Flipper должен жить дольше активной передачи (в sample — Hilt singleton процесса). Хост не должен close() клиент лишь из-за ухода с destination; явный close()/disconnect() имеет приоритет над recovery. Upload при отмене пытается удалить временную запись без разрыва, Read сбрасывает сессию и восстанавливает её.
- При добавлении в Scaffold хост передаёт Modifier.padding(contentPadding) ровно один раз. FlipperScreen не накладывает собственные глобальные бары и берёт цвета/типографику из MaterialTheme хоста.

### Политика подключения UI

Необязательный FlipperConnectionController позволяет заменить connect/disconnect и хуки onEnter/onLeave(changingConfigurations). По умолчанию внутри ui/internal/connection работает ConnectionViewModel: сохранение последнего устройства, серия до 3 попыток с паузами 0/2/6 с и cooldown 60 с после провала. При уходе в фон без передачи нет циклических попыток, при явном disconnect/поиске автоматическая политика выключается до ручного выбора устройства. Поворот Activity не считается новым входом.

Без controller готовая фича сама сохраняет последнее успешно подключённое устройство и выполняет ограниченную серию восстановления при входе/обрыве в видимой фиче. Политика живёт в ViewModel внешнего destination; хост по-прежнему владеет самим клиентом Flipper. Повторный вход запускается lifecycle destination, а не только Activity: это важно при переходах между нижними пунктами большого приложения.

Разрешения и системные picker показываются библиотечным UI через Activity Result API хоста. Только applicationContext.contentResolver передаётся во FilesViewModel; Activity/Context в ViewModel не сохраняется. BLE/RPC-слои api/internal не зависят от UI и URI. UI, Compose и ресурсы находятся в том же модуле в dev.flipper.ui; Hilt модулю не нужен.

### Проверка UI

Список A/B/U/P/F с точными шагами и ограничениями: [PROTOTYPE.md](../docs/PROTOTYPE.md). Тесты U05 требуют реального хоста с соседними нижними пунктами; sample намеренно показывает только одну фичу.

Архитектурные основания: [Navigation](https://developer.android.com/guide/navigation), [LifecycleStartEffect](https://developer.android.com/topic/libraries/architecture/lifecycle#lifecyclestarteffect).

### Длительность и фон

В Read/Write timeoutMillis=0 означает отсутствие общего лимита. По умолчанию 60 секунд без активности потокового RPC дают техническую ошибку; GATT write/flow control и metadata остаются ограниченными. Финальные MD5/публикация ждут пока жива сессия и могут быть отменены.

В манифест библиотеки включены FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS, WAKE_LOCK и неэкспортируемая connectedDevice-служба. UI запрашивает уведомления перед первой передачей на Android 13+; отказ уведомлений не подменяет foreground service. Service стартует из видимого пользовательского действия; при запрете запуска ошибка видна в UI. CPU lease удерживается только на время операции, обновляется ограниченными интервалами и снимается при завершении.

Таблица решений/статусов — docs/TASK.md; новые правила требуют физического прогона Android 16. Жёстких гарантий против Force stop, выключения телефона и ошибок внешнего провайдера нет.


26.11.2026: по указанию пользователя файловый браузер вынесен на внутренний экран Files внутри единой фичи; главный содержит компактную кнопку Open files и статус передачи, память SD остаётся в сводке устройства. Перед отправкой — диалог имени/назначения. Стрелка/системный Back возвращают в сводку, вверх по каталогу — отдельная кнопка. Job foreground service и ViewModel внешнего destination сохранены; смена внутренней страницы не отменяет передачу. Последнее уточнение заменяет прежнюю компоновку общего экрана с полным списком файлов.


26.11.2026: [точечные исправления ревью и оставшиеся ограничения](../docs/FIXES_1_14.md). Версия 1.14 сохраняет структуру модуля и текущие экраны.
