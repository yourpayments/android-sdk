# YPMN Android SDK — руководство по интеграции

> Headless Kotlin SDK для приёма платежей в Android-приложении мерчанта: банковские карты с 3-D Secure, СБП, SberPay и другие альтернативные методы. Вы полностью управляете интерфейсом — SDK берёт на себя API, шифрование карточных данных и отслеживание результата. Нативный аналог YPMN Web SDK с тем же набором понятий и сценариев.

**Версия SDK**: 0.0.1 · **Версия документа**: 1.0 (август 2026)

---

## Содержание

1. [О продукте](#1-о-продукте)
2. [Быстрый старт](#2-быстрый-старт)
3. [Основные понятия](#3-основные-понятия)
4. [Подключение](#4-подключение)
5. [Создание интента](#5-создание-интента)
6. [Объект Intent](#6-объект-intent)
7. [Оплата картой и 3-D Secure](#7-оплата-картой-и-3-d-secure)
8. [Альтернативные методы оплаты](#8-альтернативные-методы-оплаты)
9. [Ожидание результата: waitForResult](#9-ожидание-результата-waitforresult)
10. [Обновление интента](#10-обновление-интента)
11. [Восстановление интента](#11-восстановление-интента)
12. [Использование из Java](#12-использование-из-java)
13. [Обработка ошибок](#13-обработка-ошибок)
14. [Тестирование в песочнице](#14-тестирование-в-песочнице)
15. [Безопасность и PCI DSS](#15-безопасность-и-pci-dss)
16. [Справочник типов](#16-справочник-типов)
17. [Чек-лист перед выходом в продакшен](#17-чек-лист-перед-выходом-в-продакшен)

---

## 1. О продукте

Android SDK — это **headless**-решение: SDK не рисует никакого интерфейса. Экраны оплаты, форму карты, кнопки способов оплаты и состояния ожидания вы строите сами (View или Jetpack Compose). SDK даёт программный API:

- создать платёжную сессию (**интент**) и управлять ею;
- принять карту: шифрование данных на устройстве, проведение платежа, 3-D Secure в WebView;
- получить ссылки/QR для СБП, SberPay и других альтернативных методов;
- дождаться результата оплаты (поллинг статуса с учётом отклонённых попыток).

**Разделение ответственности:**

| Задача | Кто делает |
|---|---|
| UI: экраны, форма карты, кнопки | Мерчант |
| Валидация и маскирование полей ввода | Мерчант |
| Шифрование карточных данных (RSA-OAEP SHA-256) | SDK |
| Запросы к платёжному API | SDK |
| 3-D Secure (WebView + отслеживание результата) | SDK |
| Поллинг статуса платежа | SDK |

Основной API — Kotlin с корутинами (`suspend`-функции, `Flow`). Для проектов на Java есть [мост с колбэками и `CompletableFuture`](#12-использование-из-java).

---

## 2. Быстрый старт

Минимальный сценарий «оплата картой» (Kotlin, из `viewModelScope` или другого корутин-скоупа):

```kotlin
import ru.ypmn.sdk.*

val config = YpConfig(baseUrl = "https://sandbox.ypmn.ru") // прод: https://ypmn.ru

suspend fun payWithCard(container: ViewGroup) {
    // 1. Создаём интент (платёжную сессию)
    val intent = YP.createIntent(
        CreateIntentRequest(
            merchantCode = "your_terminal_id",
            amount = 100_000,        // 1000.00 ₽ — сумма в копейках
            currency = "RUB",
            messageScheme = "SMS",
            description = "Заказ #123",
            userInfo = UserInfo(billing = Billing(email = "buyer@example.com")),
        ),
        config,
    )

    // 2. Проводим платёж данными карты из вашей формы
    when (val res = intent.pay(PayInput.Card(CardData(pan, expMonth, expYear, cvv)))) {
        is PayResult.Authorized -> showSuccessScreen(intent.id)
        is PayResult.ThreeDsRequired -> {
            // 3. Банк требует 3-D Secure — показываем WebView подтверждения
            val view = intent.create3dsView(res).mount(container) // на main-потоке
            val result = view.results.first()                     // ждём исход
            view.destroy()
            when (result.status) {
                ThreeDsResult.Status.SUCCESS -> showSuccessScreen(intent.id)
                ThreeDsResult.Status.FAILURE -> showRetryScreen()
            }
        }
    }
}
```

Дальше по документу каждый шаг разобран подробно.

---

## 3. Основные понятия

### Интент

**Интент (Intent)** — платёжная сессия на стороне платёжного API. Он хранит сумму, валюту, описание заказа, список доступных методов оплаты и текущий статус. Одна оплата заказа = один интент; внутри одного интента возможно несколько **попыток** оплаты (после отказа покупатель может попробовать снова — другой картой или другим методом).

> Не путайте `ru.ypmn.sdk.Intent` с `android.content.Intent` — при совместном использовании в одном файле применяйте импорт-псевдоним: `import ru.ypmn.sdk.Intent as YpIntent`.

### Жизненный цикл и статусы

| Статус (`IntentStatus`) | Значение |
|---|---|
| `RequiresPaymentData` | Ожидаются данные для оплаты (начальное состояние) |
| `RequiresPaymentMethod` | Ожидается выбор/повтор метода оплаты |
| `Success` | Оплата успешна. **Терминальный** |
| `Expired` | Время жизни сессии истекло. **Терминальный** |

У `IntentStatus` есть свойство `isTerminal` (`Success`/`Expired` → `true`).

> **Важно:** отдельного терминального статуса «неуспех» у интента **нет**. Отклонённая попытка возвращает интент в `RequiresPayment*`, а сам факт отказа виден по транзакции со статусом `DECLINED`. Чтобы надёжно ловить отказ конкретной попытки, используйте механизм `puid` (ниже).

### PUID — идентификатор попытки оплаты

`puid` (Payment User ID) — уникальная строка, которую **вы генерируете на каждую попытку** оплаты альтернативным методом:

```kotlin
val puid = java.util.UUID.randomUUID().toString()
```

Один и тот же `puid` обязан уходить в `getPaymentMethod` (а значит — в `getLink`/`getImage`) и затем в `waitForResult(WaitForResultOpts(puid = puid))`. Когда среди транзакций интента появится `DECLINED`-транзакция с этим `puid`, `waitForResult` вернёт `Declined` — вы точно узнаете, что отклонена **именно текущая** попытка, а не какая-то из прошлых. **Без `puid` детект отказа не работает.**

### Окружения

| Окружение | `YpConfig.baseUrl` | Назначение |
|---|---|---|
| Песочница | `https://sandbox.ypmn.ru` | Разработка и тесты; работают [тестовые карты](#14-тестирование-в-песочнице) |
| Продакшен | `https://ypmn.ru` | Реальные платежи |

Адрес API задаётся в рантайме через `YpConfig` — в отличие от Web SDK, отдельных сборок под окружения нет. `merchantCode` вам выдаёт менеджер при подключении или можете посмотреть в настройках Личного кабинета.

---

## 4. Подключение

### Требования

- Android **minSdk 24** (Android 7.0+), compileSdk 35;
- Kotlin + kotlinx.coroutines (для Java-проектов — [мост](#12-использование-из-java));
- Java 17 (`sourceCompatibility`/`targetCompatibility`).

### Gradle (JitPack)

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`build.gradle.kts` модуля приложения:

```kotlin
implementation("com.github.onemantooo.android-sdk:sdk:<tag>")
```

Актуальный `<tag>` (версия релиза) вам сообщит менеджер.

### Манифест

SDK не декларирует разрешений сам — убедитесь, что в манифесте приложения есть доступ в интернет:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### ProGuard / R8

Правила для потребителей поставляются вместе с библиотекой (`consumer-rules.pro`) — дополнительная настройка не требуется.

---

## 5. Создание интента

```kotlin
val intent: Intent = YP.createIntent(
    CreateIntentRequest(
        merchantCode = "your_terminal_id",
        amount = 100_000L,
        currency = "RUB",
        messageScheme = "SMS",
        // ...опциональные параметры
    ),
    YpConfig(baseUrl = "https://ypmn.ru"),
)
```

`YP.createIntent` — `suspend`-функция; вызывайте из корутины (например, `viewModelScope.launch`).

### Обязательные параметры

| Параметр | Тип | Описание |
|---|---|---|
| `merchantCode` | `String` | Публичный идентификатор вашего терминала |
| `amount` | `Long` | Сумма в **минорных единицах** (копейки). Минимум `100` |
| `currency` | `String` | Код валюты: `"RUB"`|
| `messageScheme` | `String` | `"SMS"` — одностадийный платёж (списание сразу), `"DMS"` — двухстадийный (холдирование с последующим подтверждением) |

### Опциональные параметры

| Параметр | Тип | Описание |
|---|---|---|
| `scenario` | `String?` | Сценарий интеграции. Если не задан, SDK автоматически подставляет `"AndroidSDK"` — оставляйте пустым |
| `culture` | `String?` | Локаль: `"ru-RU"`  |
| `description` | `String?` | Описание платежа, до 255 символов |
| `merchantPaymentReference` | `String?` | Ваш идентификатор заказа, до 100 символов |
| `receiptEmail` | `String?` | Email для квитанции |
| `accountId` | `String?` | Ваш идентификатор плательщика |
| `orderTimeout` | `Int?` | Время жизни интента до 30 дней, в секундах (1200–2592000). По дефолту 3600 секунд. По истечении — статус `Expired` |
| `retryPayment` | `Boolean?` | Разрешить повторные попытки после отказа |
| `tokenize` | `Boolean?` | Токенизировать карту для будущих платежей |
| `successRedirectUrl` / `failRedirectUrl` | `String?` | URL возврата покупателя с внешних платёжных страниц (актуально для альтернативных методов) |
| `items` | `List<ProductItem>?` | Состав заказа ([формат](#productitem)) |
| `receipts` | `JsonObject?` | Данные чеков (формат АТОЛа, см. документацию провайдера)|
| `userInfo` | `UserInfo?` | Данные плательщика ([формат](#userinfo)) |
| `metaData` | `JsonObject?` | Произвольные метаданные — вернуться в интенте |

Поля `skin`, `emailBehavior`, `paymentUrl`, `autoClose` относятся к хостед-страницам (виджет/чекаут) и нативному SDK обычно не нужны; они оставлены в контракте для полноты.

> **Рекомендация:** при оплате картой всегда передавайте `userInfo.billing` (достаточно `email` и/или имя плательщика). В текущей версии API оплата картой по интенту, созданному без billing-данных, может быть отклонена сервером.

---

## 6. Объект Intent

`YP.createIntent()` и `YP.getIntent()` возвращают **хэндл интента** — интерфейс `ru.ypmn.sdk.Intent`:

| Свойство | Тип | Описание |
|---|---|---|
| `id` | `String` | Идентификатор интента — сохраняйте для восстановления |
| `status` | `IntentStatus` | Текущий (закэшированный) статус |
| `data` | `IntentResponse` | Полные данные интента: `amount`, `currency`, `paymentMethods`, `transactions`, `expiredAt` и т.д. Обновляются после `update()` |
| `events` | `Flow<IntentEvent>` | Поток событий (ниже) |

### Методы

| Метод | Возвращает | Описание |
|---|---|---|
| `pay(input)` | `PayResult` | Оплата картой ([раздел 7](#7-оплата-картой-и-3-d-secure)) |
| `create3dsView(res)` | `ThreeDsView` | WebView 3-D Secure ([раздел 7](#7-оплата-картой-и-3-d-secure)) |
| `getPaymentMethod(method, opts)` | `AltPayFlow` | Флоу альтернативного метода ([раздел 8](#8-альтернативные-методы-оплаты)) |
| `waitForResult(opts)` | `WaitForResultStatus` | Поллинг до результата ([раздел 9](#9-ожидание-результата-waitforresult)) |
| `update(changes)` | `Unit` | Обновление полей интента ([раздел 10](#10-обновление-интента)) |
| `getStatus()` | `IntentStatus` | Разовый запрос текущего статуса |
| `getStatusDetails()` | `IntentStatusResponse` | Статус и список транзакций (для собственного поллинга) |
| `addEventListener(l)` / `removeEventListener(l)` | `Cancellable` / `Unit` | Подписка на события для Java ([раздел 12](#12-использование-из-java)) |

Все методы, кроме `create3dsView` и подписок, — `suspend`-функции.

### События

`intent.events` — горячий `Flow<IntentEvent>`; события дублируют исходы операций, чтобы результат можно было обрабатывать в одном месте независимо от способа оплаты:

| Событие | Payload | Когда возникает |
|---|---|---|
| `IntentEvent.Success` | `result: PayResult` | Оплата успешно завершена: после `pay()`, после 3-D Secure или когда `waitForResult` дождался `Success` |
| `IntentEvent.Error` | `error: Throwable` | Неуспех: ошибка `pay()`, провал 3-D Secure, `Expired`, отклонение попытки |
| `IntentEvent.StatusChange` | `status: IntentStatus` | Статус интента изменился (при любом запросе статуса) |
| `IntentEvent.Update` | `intent: Intent` | Поля интента обновлены после `update()` |

```kotlin
lifecycleScope.launch {
    intent.events.collect { event ->
        when (event) {
            is IntentEvent.Success      -> showSuccess()
            is IntentEvent.Error        -> showError(event.error)
            is IntentEvent.StatusChange -> log("Статус: ${event.status}")
            is IntentEvent.Update       -> refreshUi(event.intent)
        }
    }
}
```

> **Потоки.** События и результаты `suspend`-функций приходят на фоновых диспетчерах. Перед обновлением UI переключайтесь на main (`withContext(Dispatchers.Main)`, `lifecycleScope` в UI-слое и т.п.).

---

## 7. Оплата картой и 3-D Secure

### intent.pay(input)

Принимает данные карты, шифрует их на устройстве (RSA-OAEP SHA-256, открытый ключ запрашивается у API автоматически) и проводит платёж. PAN и CVV уходят на сервер **только внутри криптограммы**.

```kotlin
val res = intent.pay(
    PayInput.Card(
        CardData(
            pan = "4111111111111111", // номер карты, только цифры
            expMonth = "12",          // месяц, 2 цифры
            expYear = "30",           // год, 2 цифры
            cvv = "123",
        )
    )
)
```

Если криптограмма собрана вами заранее, передайте её напрямую: `intent.pay(PayInput.Cryptogram(cryptogram))`.

Результат — `PayResult`:

| Вариант | Что означает | Что делать |
|---|---|---|
| `PayResult.Authorized` | Платёж проведён (3DS не потребовался). `data` — статус и транзакции | Показать экран успеха (также придёт `IntentEvent.Success`) |
| `PayResult.ThreeDsRequired` | Банк требует подтверждение 3-D Secure. `threeDsUrl` — адрес страницы подтверждения | Создать и смонтировать `ThreeDsView` (ниже) |

При отказе банка `pay()` бросает исключение и эмитит `IntentEvent.Error`.

### 3-D Secure: ThreeDsView

```kotlin
val res = intent.pay(PayInput.Card(card))
if (res is PayResult.ThreeDsRequired) {
    val view = intent.create3dsView(res)

    withContext(Dispatchers.Main) {
        view.mount(binding.threeDsContainer)   // container: ViewGroup; только main-поток
    }

    val result = view.results.first()          // Flow<ThreeDsResult>, результат реплеится
    view.destroy()

    when (result.status) {
        ThreeDsResult.Status.SUCCESS -> showSuccess()
        ThreeDsResult.Status.FAILURE -> showRetryScreen()
    }
}
```

`mount(container)` создаёт `WebView` со страницей подтверждения банка и добавляет его в ваш контейнер. SDK следит за результатом двумя путями одновременно: принимает сообщение от возвратной страницы 3DS (только с origin платёжного API — подделать результат со сторонней страницы нельзя) и параллельно опрашивает статус интента каждые 3 секунды.

Как только результат известен:

- в `view.results` приходит `ThreeDsResult(status, code, intentStatus)` — поток с `replay = 1`, поэтому подписаться можно и после завершения;
- у интента эмитится `IntentEvent.Success` (платёж прошёл) или `IntentEvent.Error` (3DS не пройден).

**API ThreeDsView:**

| Метод/свойство | Описание |
|---|---|
| `mount(container)` | Создать WebView и добавить в `ViewGroup`. Возвращает `this`. Вызывать на main-потоке |
| `unmount()` | Извлечь WebView из контейнера, приостановив отслеживание (можно смонтировать снова) |
| `destroy()` | Полностью завершить: остановить поллинг, удалить и уничтожить WebView. Вызывайте всегда после получения результата и при закрытии экрана |
| `results` | `Flow<ThreeDsResult>` — исход проверки |
| `webView` | Сам `WebView` (после `mount`) — для тонкой настройки |

В WebView включён JavaScript — это требование банковских страниц 3-D Secure.

---

## 8. Альтернативные методы оплаты

Список методов, доступных покупателю, приходит в `intent.data.paymentMethods` — он зависит от настроек вашего терминала. Стройте кнопки способов оплаты по нему:

```kotlin
val available: List<AltPayMethod> = intent.data.paymentMethods
    .orEmpty()
    .mapNotNull { AltPayMethod.fromType(it.type) }
```

Для запуска оплаты получите **флоу** метода:

```kotlin
val flow = intent.getPaymentMethod(AltPayMethod.SberPay, AltRequestOpts(puid = puid))
```

`getPaymentMethod(method, opts)`:

| Параметр | Тип | Описание |
|---|---|---|
| `method` | `AltPayMethod` | `FasterPayments`, `SberPay`, `AlfaPay`, `MirPay`, `TPay` |
| `opts.puid` | `String?` | Идентификатор попытки ([раздел 3](#puid--идентификатор-попытки-оплаты)). |
| `opts.webview` | `Boolean` | Укажите `true`, если страница будет открыта внутри WebView мобильного приложения — платёжный сервис вернёт ссылку, корректно работающую из WebView  |

Если метода нет в интенте, вызов бросит `YpException`. Для `FasterPayments` и `SberPay` есть перегрузки, возвращающие конкретный тип флоу без приведения (`AltPayFlow.FasterPaymentsFlow` / `AltPayFlow.SberPayFlow`).

Общий API флоу (`AltPayFlow`):

| Свойство/метод | Описание |
|---|---|
| `method` | Тип метода |
| `link` | Готовая ссылка/диплинк из данных интента (если платёжная система её вернула). **Внимание:** оплата по ней не связана с вашим `puid` |
| `getLink(opts)` | Запросить свежую ссылку у API с учётом `puid`/`webview` (и `schema` банка для СБП) — `suspend` |
| `getImage()` | Синхронно вернуть URL QR-картинки (актуально для СБП). Если флоу создан с `puid`, URL учитывает его |

> **Правило:** если вы используете `waitForResult(puid)`, получайте ссылку через `getLink()` (и QR через `getImage()` у флоу, созданного с `puid`) — тогда транзакция попытки будет помечена вашим `puid`. Встроенный `flow.link` этого не гарантирует.

### SberPay

```kotlin
val puid = UUID.randomUUID().toString()
val sber = intent.getPaymentMethod(AltPayMethod.SberPay, AltRequestOpts(puid = puid))

// Вариант А: открыть приложение СберБанка по ссылке/диплинку
val link = sber.getLink()
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))

// Вариант Б: отправить пуш/СМС со ссылкой на оплату в приложение СберБанка
sber.sendSms(phone = "79990000000") // формат 79XXXXXXXXX

// В обоих случаях — ждём результат
val status = intent.waitForResult(WaitForResultOpts(puid = puid))
```

### СБП (FasterPayments)

У СБП-флоу есть список банков и QR-код:

```kotlin
val puid = UUID.randomUUID().toString()
val sbp = intent.getPaymentMethod(AltPayMethod.FasterPayments, AltRequestOpts(puid = puid))

// 1) Список банков для выбора — sbp.banks: List<SbpBank>
//    (bankName, logoUrl, schema, packageName — package приложения банка)
showBankPicker(sbp.banks)

// 2) Диплинк в выбранный банк:
val link = sbp.getLink(AltLinkOpts(schema = selectedBank.schema))
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))

// 3) Либо QR-код (например, для планшета/кассы) — URL картинки в ImageView:
imageView.load(sbp.getImage()) // Coil/Glide

// Ожидание результата
val status = intent.waitForResult(WaitForResultOpts(puid = puid))
```

`SbpBank.packageName` — package приложения банка: по нему можно проверить установленность (`PackageManager`) и поднять нужное приложение в списке.

### Прочие методы: AlfaPay, MirPay, T-Pay

Универсальный сценарий — получить ссылку и открыть её:

```kotlin
val puid = UUID.randomUUID().toString()
val flow = intent.getPaymentMethod(AltPayMethod.TPay, AltRequestOpts(puid = puid))
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(flow.getLink())))
val status = intent.waitForResult(WaitForResultOpts(puid = puid))
```

---

## 9. Ожидание результата: waitForResult

Для карты результат приходит синхронно (или через `ThreeDsView`), а при оплате альтернативным методом покупатель уходит в приложение банка — результат нужно **ждать**. `waitForResult` опрашивает статус интента до терминального исхода:

```kotlin
val status = intent.waitForResult(
    WaitForResultOpts(
        puid = puid,          // ловить отказ именно этой попытки
        intervalMs = 3_000,   // период опроса (по умолчанию 3000 мс)
        timeoutMs = 600_000,  // общий таймаут (по умолчанию 10 минут)
    )
)

when (status) {
    WaitForResultStatus.Success  -> showSuccessScreen()
    WaitForResultStatus.Declined -> showRetryScreen()   // попытка отклонена — интент жив, новый puid и повтор
    WaitForResultStatus.Expired  -> showExpiredScreen() // сессия истекла — нужен новый интент
    else -> Unit // RequiresPayment* — только при нештатном выходе из поллинга
}
```

Исходы:

- **`Success`** — оплата прошла (дополнительно эмитится `IntentEvent.Success`);
- **`Declined`** — среди транзакций появилась `DECLINED`-транзакция с вашим `puid`: текущая попытка отклонена, но интент не терминален — предложите покупателю оплатить снова (эмитится `IntentEvent.Error`);
- **`Expired`** — время жизни интента истекло (эмитится `IntentEvent.Error`).

По истечении `timeoutMs` бросается `YpException("pollUntil: timeout")`.

> **Отмена ожидания** — стандартная отмена корутины (аналог `AbortSignal` в Web SDK). Вызывайте `waitForResult` из scope, привязанного к жизненному циклу экрана (`viewModelScope`, `lifecycleScope`): при уходе с экрана ожидания отменяйте Job/scope, иначе фоновый опрос API доживёт до таймаута.

```kotlin
val job = viewModelScope.launch {
    val status = intent.waitForResult(WaitForResultOpts(puid = puid))
    ...
}
// при закрытии экрана ожидания:
job.cancel()
```

Если нужен собственный цикл опроса (например, с отображением списка попыток), используйте `intent.getStatusDetails()` — он возвращает статус вместе с транзакциями.

---

## 10. Обновление интента

После создания интента можно изменить ограниченный набор полей:

```kotlin
intent.update(UpdateChanges(receiptEmail = "buyer@example.com", tokenize = true))

// Поля обновились в intent.data
```

`UpdateChanges` — поле со значением `null` означает «не менять»:

| Поле | Тип | Описание |
|---|---|---|
| `receiptEmail` | `String?` | Новый email для квитанции |
| `tokenize` | `Boolean?` | Токенизация карты |
| `clearReceiptEmail` | `Boolean` | `true` — очистить email (нельзя выразить через `null`, он значит «не трогать») |

Типовой сценарий: покупатель ввёл email на вашем экране прямо перед оплатой — обновите интент до вызова `pay()`. Несколько параллельных вызовов `update()` SDK автоматически объединяет в один запрос. Вызов без единого редактируемого поля бросает `YpException`. После успешного обновления эмитится `IntentEvent.Update`.

---

## 11. Восстановление интента

`YP.getIntent(id, config)` загружает существующий интент — тот же полнофункциональный хэндл, что и после `createIntent`:

```kotlin
val intent = YP.getIntent(savedIntentId, config)

when {
    intent.status == IntentStatus.Success -> showSuccessScreen()
    intent.status == IntentStatus.Expired -> showExpiredScreen()
    else -> resumeCheckout(intent) // можно продолжать оплату
}
```

Когда это нужно:

- **пересоздание Activity / смерть процесса** во время оплаты — сохраните `intent.id` (например, в `SavedStateHandle`) и восстановите сессию;
- **возврат покупателя** из приложения банка в ваше приложение — проверьте фактический статус;
- **интент создан на вашем бэкенде** (сервер-сервер), а приложение получает только его id — так параметры платежа (сумма, состав заказа) не проходят через клиент.

Если ваш бэкенд уже отдал приложению полный JSON интента, можно обойтись без лишнего запроса: `YP.wrapIntent(intentResponse, config)`.

---

## 12. Использование из Java

Для Java-проектов есть мост `ru.ypmn.sdk.java`: колбэки вместо `suspend`, `CompletableFuture`, слушатель событий.

```java
CreateIntentRequest req = CreateIntentRequest
    .builder("your_terminal_id", 100000L, "RUB", "SMS")
    .description("Заказ #123")
    .build();

YpConfig config = new YpConfig("https://ypmn.ru");

YPJava.createIntent(req, config, new YpCallback<Intent>() {
    @Override public void onSuccess(Intent intent) { startPayment(intent); }
    @Override public void onError(Throwable e) { showError(e); }
});

// Или через CompletableFuture:
CompletableFuture<Intent> future = YPJava.createIntentFuture(req, config);

// Восстановление: YPJava.getIntent(id, config, cb) / YPJava.getIntentFuture(id, config)
```

Операции над интентом — статические хелперы класса `YPJavaKt` (расширения Kotlin):

```java
YPJavaKt.payAsync(intent, new PayInput.Card(card), new YpCallback<PayResult>() { ... });
YPJavaKt.updateAsync(intent, changes, cb);
YPJavaKt.waitForResultAsync(intent, new WaitForResultOpts(null, null, puid), cb);
YPJavaKt.getPaymentMethodAsync(intent, AltPayMethod.SberPay.INSTANCE, cb); // колбэк сразу с SberPayFlow
YPJavaKt.getLinkAsync(flow, opts, cb);
YPJavaKt.sendSmsAsync(sberFlow, "79990000000", cb);
```

События:

```java
Cancellable subscription = intent.addEventListener(event -> {
    if (event instanceof IntentEvent.Success) { ... }
    else if (event instanceof IntentEvent.Error) { ... }
});
// при закрытии экрана:
subscription.cancel();
```

Каждый асинхронный вызов возвращает `Cancellable` — вызовите `cancel()`, чтобы отменить операцию (например, остановить `waitForResultAsync` при уходе с экрана).

> **Внимание: потоки.** Колбэки и события приходят на **фоновом** потоке, не на main. Для обновления UI переключайтесь самостоятельно: `runOnUiThread(...)` или `new Handler(Looper.getMainLooper()).post(...)`.

---

## 13. Обработка ошибок

Все ошибки SDK — `YpException` (наследник `Exception`); для программной обработки есть подтипы:

| Тип | Когда | Полезные поля |
|---|---|---|
| `YpApiException` | API ответил ошибкой (4xx/5xx) | `status` — HTTP-код; `body` — сырое тело ответа; `message` — сообщение бэкенда |
| `YpNetworkException` | Сетевая ошибка: запрос не отправлен или ответ не получен | Можно предложить повторить |
| `YpException` (базовый) | Ошибки логики SDK | `message` |

```kotlin
try {
    intent.pay(PayInput.Card(card))
} catch (e: YpApiException) {
    showError("Платёж отклонён: ${e.message}") // + e.status для диагностики
} catch (e: YpNetworkException) {
    showError("Нет соединения. Проверьте интернет и попробуйте ещё раз.")
} catch (e: YpException) {
    showError(e.message ?: "Ошибка оплаты")
}
```

Типичные сообщения базового `YpException`:

| Сообщение | Причина |
|---|---|
| `payment declined (transaction N)` | Попытка с вашим `puid` отклонена (`waitForResult` → `Declined`) |
| `payment expired` | Интент истёк (`waitForResult` → `Expired`) |
| `3DS failed` | Покупатель не прошёл 3-D Secure |
| `pollUntil: timeout` | `waitForResult` не дождался результата за `timeoutMs` |
| `getPaymentMethod: метод X отсутствует в интенте` | Метод недоступен для терминала или запрещён |
| `update: no editable fields provided` | В `update()` не передано ни одного редактируемого поля |

**Рекомендации по UX ошибок:**

- отказ оплаты (`Declined`, ошибка `pay()`) — не тупик: предложите повторить оплату другой картой или другим методом (интент остаётся в `RequiresPayment*`);
- `Expired` — создайте новый интент и предложите оплатить заново;
- не показывайте покупателю технические сообщения — переводите их в понятные формулировки («Платёж отклонён банком. Попробуйте другую карту»).

---

## 14. Тестирование в песочнице

Для тестов используйте `YpConfig(baseUrl = "https://sandbox.ypmn.ru")`. Тестовые карты действуют **только в песочнице**:

### Успешная оплата

| Номер карты | Платёжная система | 3-D Secure | Срок действия | CVV |
|---|---|---|---|---|
| `4652 0354 4066 7037` | VISA | нет | 08 / следующий год | 971 |
| `4051 0600 0000 0178` | VISA | да | 12 / следующий год | 895 |
| `5105 1051 0510 5100` | MasterCard | нет | 03 / следующий год | 235 |
| `5547 6294 7878 5897` | MasterCard | да | 07 / следующий год | 123 |
| `2200 2042 6557 0145` | МИР | нет | 03 / следующий год | 235 |
| `2200 2016 7368 7446` | МИР | да | 07 / следующий год | 123 |

### Отказ в авторизации

| Номер карты | Платёжная система | Причина отказа | Срок действия | CVV |
|---|---|---|---|---|
| `5563 6930 6203 0796` | MasterCard | Stolen card, pick up | 03 / любой будущий год | 235 |
| `4921 3010 1045 9253` | VISA | Default error | 03 / любой будущий год | 235 |
| `2200 2000 0000 0000` | МИР | Non Sufficient Funds | 03 / любой будущий год | 235 |

> «Следующий год» — именно следующий календарный год (например, в 2026 году указывайте `27`). Месяц — из таблицы.

**Чек-лист сценариев для проверки интеграции:**

- [ ] оплата картой без 3DS → экран успеха;
- [ ] оплата картой с 3DS → WebView → успех; `destroy()` вызван;
- [ ] карта с отказом → сообщение об ошибке → повторная попытка успешной картой;
- [ ] альтернативный метод: ссылка/QR получены с `puid`, `waitForResult` доводит до результата;
- [ ] сворачивание приложения и возврат во время ожидания → поллинг продолжает работать в scope экрана;
- [ ] уход с экрана ожидания → корутина отменена, опрос остановлен;
- [ ] пересоздание Activity во время оплаты → `getIntent` восстанавливает сессию.

---

## 15. Безопасность и PCI DSS

- SDK шифрует данные карты на устройстве алгоритмом **RSA-OAEP (SHA-256)** до отправки; PAN и CVV покидают приложение только внутри криптограммы. Открытый ключ шифрования SDK получает у API автоматически.
- Поскольку данные карты вводятся в вашем приложении, объём ваших обязательств по PCI DSS согласуйте с эквайером (для mobile-native интеграций он аналогичен web-сценарию SAQ A-EP).

**Обязательные требования к вашему приложению:**

1. **Не сохраняйте и не логируйте** PAN, CVV и срок действия: ни в логи (`Log.*`), ни в аналитику, ни в крэш-репорты. `CardData.toString()` в SDK намеренно маскирует данные (`CardData(pan=****1111, exp=**/**, cvv=***)`) — не обходите это, собирая строки из полей вручную.
2. **Не передавайте данные карты на свой сервер.** Они должны идти только в `intent.pay()`.
3. На экране ввода карты отключите скриншоты и запись экрана: `window.setFlags(FLAG_SECURE, FLAG_SECURE)`.
4. Не переопределяйте обработку сертификатов/SSL-ошибок в приложении; SDK работает только по HTTPS.
5. `intent.data.secret` — служебное поле сессии; не логируйте его и не передавайте третьим лицам.

---

## 16. Справочник типов

### YpConfig

```kotlin
data class YpConfig(
    val baseUrl: String,                           // https://sandbox.ypmn.ru | https://ypmn.ru
    val headers: Map<String, String> = emptyMap(), // доп. заголовки ко всем запросам (по согласованию)
)
```

### CreateIntentRequest

```kotlin
CreateIntentRequest(
    // обязательные
    merchantCode: String,
    amount: Long,                  // сумма в копейках, min 100
    currency: String,              // "RUB" 
    messageScheme: String,         // "SMS" | "DMS"
    // опциональные
    culture: String? = null,       // "ru-RU
    description: String? = null,   // ≤ 255 символов
    merchantPaymentReference: String? = null,    // ≤ 100 символов
    receiptEmail: String? = null,
    accountId: String? = null,
    orderTimeout: Int? = null,     // секунды, 1200–2592000
    retryPayment: Boolean? = null,
    tokenize: Boolean? = null,
    scenario: String? = null,      // null → "AndroidSDK"
    successRedirectUrl: String? = null,
    failRedirectUrl: String? = null,
    items: List<ProductItem>? = null,
    receipts: JsonObject? = null,
    userInfo: UserInfo? = null,
    metaData: JsonObject? = null,
)
// Для Java: CreateIntentRequest.builder(terminal, amount, currency, schema)...build()
```

### ProductItem

```kotlin
ProductItem(
    name: String,                     // ≤ 255 символов
    sku: String,                      // ≤ 100 символов
    unitPrice: String,                // строка, ≤ 9999999
    quantity: String,                 // строка, ≤ 99999
    additionalDetails: String? = null,
    vat: String? = null,              // "0" | "5" | "7" | "10" | "22"
    marketplace: Marketplace? = null, // Marketplace(merchantCode)
)
```

### UserInfo

```kotlin
UserInfo(billing = Billing(
    firstName = null, lastName = null,
    email = null,
    countryCode = null,   // ISO 3166-1 alpha-2, например "RU"
    phone = null,         // 79XXXXXXXXX
    city = null, state = null,
    companyName = null, taxId = null,
    addressLine1 = null, addressLine2 = null, zipCode = null,
))
```

### IntentResponse (intent.data)

```kotlin
data class IntentResponse(
    val id: String,
    val status: String,
    val secret: String,                             // служебное — не логировать
    val createdAt: Long, val updatedAt: Long,
    val expiredAt: Long,                            // когда интент перейдёт в Expired
    val merchantCode: String,
    val amount: Long, val currency: String,
    val messageScheme: String,
    val description: String?, val receiptEmail: String?, val tokenize: Boolean?,
    val paymentMethods: List<PaymentMethodDto>?,    // доступные методы оплаты
    val transactions: List<Transaction>?,           // попытки оплаты
)
```

### Статусы и транзакции

```kotlin
enum class IntentStatus { RequiresPaymentData, RequiresPaymentMethod, Expired, Success }
// isTerminal == true для Success и Expired

enum class WaitForResultStatus { RequiresPaymentData, RequiresPaymentMethod, Expired, Success, Declined }

data class Transaction(
    val id: Long,
    val status: String,       // "PENDING" | "AUTHORIZED" | "DECLINED"
    val puid: String? = null, // идентификатор попытки, если вы его передавали
)

data class IntentStatusResponse(
    val intent: IntentStatusData,          // IntentStatusData(status: String)
    val transactions: List<Transaction>,
)
```

### Оплата картой

```kotlin
data class CardData(
    val pan: String,      // только цифры
    val expMonth: String, // 2 цифры, "01"–"12"
    val expYear: String,  // 2 цифры
    val cvv: String,
) // toString() маскирует данные

sealed interface PayInput {
    data class Card(val card: CardData) : PayInput
    data class Cryptogram(val cryptogram: String) : PayInput
}

sealed interface PayResult {
    data class Authorized(val data: IntentStatusResponse) : PayResult
    data class ThreeDsRequired(val threeDsUrl: String) : PayResult
}
```

### Альтернативные методы

```kotlin
sealed class AltPayMethod(val type: String) {
    data object FasterPayments; data object SberPay; data object AlfaPay
    data object MirPay; data object TPay
    companion object {
        val entries: List<AltPayMethod>              // все методы
        fun fromType(type: String): AltPayMethod?    // строка бэкенда → метод
    }
}

data class AltRequestOpts(val webview: Boolean = false, val puid: String? = null)
data class AltLinkOpts(val webview: Boolean = false, val puid: String? = null, val schema: String? = null)

sealed interface AltPayFlow {
    val method: AltPayMethod
    val link: String?                                  // встроенная ссылка (без привязки к puid)
    fun getImage(): String                             // URL QR (актуально для СБП)
    suspend fun getLink(opts: AltLinkOpts = AltLinkOpts()): String

    interface SberPayFlow : AltPayFlow { suspend fun sendSms(phone: String) } // 79XXXXXXXXX
    interface FasterPaymentsFlow : AltPayFlow { val banks: List<SbpBank> }
    interface GenericAltFlow : AltPayFlow
}

data class SbpBank(
    val bankName: String,
    val logoUrl: String?,
    val schema: String,             // передаётся в getLink(AltLinkOpts(schema = ...))
    val webClientUrl: String?,
    val isWebClientActive: String?,
    val packageName: String?,       // package Android-приложения банка
)
```

### 3-D Secure

```kotlin
interface ThreeDsView {
    val webView: WebView?
    fun mount(container: ViewGroup): ThreeDsView
    fun unmount()
    fun destroy()
    val results: Flow<ThreeDsResult>   // replay = 1
}

data class ThreeDsResult(
    val status: Status,                // SUCCESS | FAILURE
    val code: String? = null,          // код от банка (при наличии)
    val intentStatus: IntentStatus? = null,
)
```

### Ошибки

```kotlin
open class YpException(message: String, cause: Throwable? = null) : Exception
class YpApiException(val status: Int, val body: String?, message: String, ...) : YpException
class YpNetworkException(message: String, ...) : YpException
```

---

## 17. Чек-лист перед выходом в продакшен

- [ ] `YpConfig.baseUrl` указывает на продакшен (`https://ypmn.ru`), используется продакшен-`merchantCode`.
- [ ] `amount` передаётся в копейках (частая ошибка — передать рубли).
- [ ] Передаётся `userInfo.billing` для карточных платежей.
- [ ] Обработаны оба исхода `pay()`: `Authorized` и `ThreeDsRequired`; `ThreeDsView.destroy()` вызывается после результата и при закрытии экрана.
- [ ] Для альтернативных методов: `puid` генерируется на каждую попытку, ссылки берутся через `getLink()`, результат — через `waitForResult(puid)`.
- [ ] `waitForResult` вызывается из scope, привязанного к жизненному циклу экрана; при уходе с экрана корутина отменяется.
- [ ] `intent.id` переживает пересоздание Activity (`SavedStateHandle`), восстановление — через `getIntent`.
- [ ] Отказ и истечение сессии обрабатываются: покупателю предложен повтор или новый интент.
- [ ] Данные карты не логируются и не попадают в аналитику/крэш-репорты; на экране карты включён `FLAG_SECURE`.
- [ ] UI обновляется только на main-потоке (особенно при использовании Java-моста).
- [ ] Проведены тестовые платежи по [чек-листу сценариев](#14-тестирование-в-песочнице).

---

*Появились вопросы по интеграции — обратитесь к вашему менеджеру или в техническую поддержку.*
