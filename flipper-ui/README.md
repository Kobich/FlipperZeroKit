# flipper-ui

Встраиваемая Compose-фича Flipper: одна точка входа dev.flipper.ui.FlipperScreen, внутри «Устройство» и «Файлы». Нижний бар, верхняя панель, MaterialTheme и NavController принадлежат хосту. Библиотека не содержит Activity, Hilt и собственного NavHost.

## Подключение

```kotlin
implementation(project(":flipper-ui"))
```

UI-модуль транзитивно предоставляет :flipper. При переносе в другой Gradle-проект нужно подключить оба каталога и их version catalog/зависимости; Maven-публикация пока не настроена. Нужен Compose-хост.

```kotlin
// Клиент создаётся один раз владельцем, а не внутри рекомпозиции.
val flipper: Flipper = /* host-owned client */

NavHost(navController, startDestination = "home") {
    composable("home") { /* экран хоста */ }
    composable("flipper") {
        FlipperScreen(
            flipper = flipper,
            // Необязательно: сохранение устройства и reconnect из хоста.
            connectionController = hostConnectionController,
        )
    }
}
// В нижнем баре хоста — один пункт, ведущий на route="flipper".
```

## Владение состоянием

- Один стабильный Flipper и controller на время жизни destination. Использовать ViewModelStoreOwner внешнего NavBackStackEntry; не создавать отдельный owner на каждую внутреннюю вкладку.
- Оба внутренних ViewModel привязаны к этому destination; переключение вкладок не отменяет передачу. Каталог, форма и прокрутка сохраняются. На другом разделе активная передача видна баннером.
- При Back сначала поднимаемся из вложенного каталога, затем из «Файлов» переходим на «Устройство», затем Back обрабатывает хост. Во время передачи Back меняет внутренний раздел, не каталог.
- Уход с destination при сохранённом navigation state/owner не удаляет ViewModel; операция может продолжаться в живом процессе. Удаление destination из back stack очищает ViewModel и отменяет его операции. Resume после очистки/смерти процесса не реализован.
- Хост закрывает Flipper при окончании его lifetime. Обычный уход с экрана и смена внутренней вкладки не вызывают close()/disconnect(). Отмена/ошибка активного Storage.Read/Write по нынешней политике ядра сбрасывает клиентскую сессию.
- При добавлении в Scaffold хост передаёт Modifier.padding(contentPadding) ровно один раз. FlipperScreen не накладывает собственные глобальные бары и берёт цвета/типографику из MaterialTheme хоста.

## Политика подключения

Необязательный FlipperConnectionController передаёт connect/disconnect и хуки onEnter/onLeave(changingConfigurations). В sample его реализует ConnectionSession: сохранение последнего успешного устройства, одна попытка при входе/возврате и подавление reconnect после ручного disconnect до следующего настоящего входа. Поворот Activity не считается новым входом.

Без controller работают прямые flipper.connect/disconnect; автоматическое восстановление остаётся решением хоста. Повторный вход запускается lifecycle destination, а не только Activity: это важно при переходах между нижними пунктами большого приложения.

Разрешения и системные picker показываются библиотечным UI через Activity Result API хоста. Только applicationContext.contentResolver передаётся во FilesViewModel; Activity/Context в ViewModel не сохраняется. Core :flipper по-прежнему не содержит Compose/Hilt/URI.

## Проверка

Список A/B/U/P с точными шагами и ограничениями: [PROTOTYPE.md](../docs/PROTOTYPE.md). Тесты U05 требуют реального хоста с соседними нижними пунктами; sample намеренно показывает только одну фичу.

Архитектурные основания: [Navigation](https://developer.android.com/guide/navigation), [LifecycleStartEffect](https://developer.android.com/topic/libraries/architecture/lifecycle#lifecyclestarteffect).
