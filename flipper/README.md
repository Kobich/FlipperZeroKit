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
Для downloadFile(path, destination) и uploadFile(path, source, sizeBytes) поток открывает и закрывает хост. sizeBytes может быть null; прогресс считается по содержимому, а успешный TransferResult возвращается после завершающего RPC и сверки MD5. Пустой файл поддерживается.
Все Storage-вызовы сериализованы. Upload предварительно отклоняет существующий путь; атомарное отсутствие перезаписи протокол не гарантирует. Пути абсолютные, ASCII. При ошибке/отмене начавшейся передачи сессия закрывается; частичный файл может остаться. Хост определяет очистку и повтор.

Сценарий проверки, таймауты и ограничения: [PROTOTYPE.md](../docs/PROTOTYPE.md).

## Известные устройства без поиска

getKnownDevices() возвращает KnownDevicesResult.Available(devices) либо Failed(BleFailure). KnownBleDevice содержит BleDevice, isBonded и isConnectedToSystem. Данные объединены по адресу из сохранённых BLE-устройств и системного GATT-профиля. Чтение не меняет текущую сессию, не сканирует и не удаляет bond; connect(device) затем создаёт собственную сессию и переиспользует BOND_BONDED.
Хост запрашивает FlipperPermissions.connectPermissions; requiredPermissions по-прежнему предназначен для поиска. RSSI теперь Int? с null по умолчанию, поскольку для сохранённого адреса измерения нет. Для уже сопряжённого устройства не требуется SCAN/location при прямом подключении. Подключённый системный профиль не гарантирует RPC или право перенять клиент другого приложения.

## Встраивание UI

UI-блок внутри единого модуля :flipper: одна точка входа dev.flipper.ui.FlipperScreen, внутри «Устройство» и «Файлы». Нижний бар, верхняя панель, MaterialTheme и NavController принадлежат хосту. Библиотека не содержит Activity, Hilt и собственного NavHost.

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

- Один стабильный Flipper и controller на время жизни destination. Использовать ViewModelStoreOwner внешнего NavBackStackEntry; не создавать отдельный owner на каждую внутреннюю вкладку.
- Оба внутренних ViewModel привязаны к этому destination; переключение вкладок не отменяет передачу. Каталог, форма и прокрутка сохраняются. На другом разделе активная передача видна баннером.
- При Back сначала поднимаемся из вложенного каталога, затем из «Файлов» переходим на «Устройство», затем Back обрабатывает хост. Во время передачи Back меняет внутренний раздел, не каталог.
- Уход с destination при сохранённом navigation state/owner не удаляет ViewModel; операция может продолжаться в живом процессе. Удаление destination из back stack очищает ViewModel и отменяет его операции. Resume после очистки/смерти процесса не реализован.
- Хост закрывает Flipper при окончании его lifetime. Обычный уход с экрана и смена внутренней вкладки не вызывают close()/disconnect(). Отмена/ошибка активного Storage.Read/Write по нынешней политике ядра сбрасывает клиентскую сессию.
- При добавлении в Scaffold хост передаёт Modifier.padding(contentPadding) ровно один раз. FlipperScreen не накладывает собственные глобальные бары и берёт цвета/типографику из MaterialTheme хоста.

### Политика подключения UI

Необязательный FlipperConnectionController позволяет заменить connect/disconnect и хуки onEnter/onLeave(changingConfigurations). По умолчанию внутри ui/internal/connection работает ConnectionViewModel: сохранение последнего успешного устройства, одна попытка при входе/возврате и подавление reconnect после ручного disconnect до следующего настоящего входа. Поворот Activity не считается новым входом.

Без controller готовая фича сама сохраняет последнее успешно подключённое устройство и выполняет одну попытку восстановления при входе. Политика живёт в ViewModel внешнего destination; хост по-прежнему владеет самим клиентом Flipper. Повторный вход запускается lifecycle destination, а не только Activity: это важно при переходах между нижними пунктами большого приложения.

Разрешения и системные picker показываются библиотечным UI через Activity Result API хоста. Только applicationContext.contentResolver передаётся во FilesViewModel; Activity/Context в ViewModel не сохраняется. BLE/RPC-слои api/internal не зависят от UI и URI. UI, Compose и ресурсы находятся в том же модуле в dev.flipper.ui; Hilt модулю не нужен.

### Проверка UI

Список A/B/U/P с точными шагами и ограничениями: [PROTOTYPE.md](../docs/PROTOTYPE.md). Тесты U05 требуют реального хоста с соседними нижними пунктами; sample намеренно показывает только одну фичу.

Архитектурные основания: [Navigation](https://developer.android.com/guide/navigation), [LifecycleStartEffect](https://developer.android.com/topic/libraries/architecture/lifecycle#lifecyclestarteffect).
