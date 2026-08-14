# YP Android SDK — Полная спецификация API

> Назначение документа: исчерпывающее описание **всех** публичных и внутренних методов,
> функций, классов и типов Android SDK (`ru.ypmn.sdk`), достаточное для того, чтобы
> Swift-разработчик воспроизвёл эквивалентный iOS SDK **1:1 по поведению и сетевому контракту**.
>
> Документ описывает **фактический код** на момент коммита `df01a8c`
> (`refactor(sdk): type-safe getPaymentMethod overloads`), а не проектный черновик `DESIGN.md`.
>
> ⚠️ **Исторический снапшот.** После `df01a8c` API ушёл вперёд (спека v1.0.0, DECLINED/puid-модель
> `waitForResult`, `getStatusDetails`, иерархия ошибок, полный CreateIntentRequest и др.) —
> актуальный публичный контракт см. в `API-SPEC.md`.
> Там, где реализация разошлась с `DESIGN.md`, приведена пометка **⚠️ Отличие от DESIGN.md**.

---

## Оглавление

1. [Обзор и принципы](#1-обзор-и-принципы)
2. [Топология пакетов и модулей](#2-топология-пакетов-и-модулей)
3. [Зависимости (что искать в iOS-эквиваленте)](#3-зависимости)
4. [Модель потоков и конкурентности](#4-модель-потоков-и-конкурентности)
5. [Публичный API — точка входа `YP`](#5-публичный-api--точка-входа-yp)
6. [Публичный API — `Intent`](#6-публичный-api--intent)
7. [Публичные типы (доменная модель)](#7-публичные-типы-доменная-модель)
8. [Контрактные типы (wire-модель)](#8-контрактные-типы-wire-модель)
9. [Событийная модель `IntentEvent`](#9-событийная-модель-intentevent)
10. [`ThreeDsView` — 3DS через WebView](#10-threedsview--3ds-через-webview)
11. [Ошибки — `YpException`](#11-ошибки--ypexception)
12. [Java-фасад (`ru.ypmn.sdk.java`)](#12-java-фасад)
13. [Внутренняя реализация — сессия и хэндл](#13-внутренняя-реализация--сессия-и-хэндл)
14. [Внутренняя реализация — очередь операций и коалесинг](#14-очередь-операций-и-коалесинг)
15. [Внутренняя реализация — операции](#15-операции-internaloperations)
16. [Внутренняя реализация — поллер](#16-поллер-polluntil)
17. [Криптограмма — точный формат (RSA-OAEP)](#17-криптограмма--точный-формат)
18. [BrowserInfo](#18-browserinfo)
19. [Сетевой слой (Retrofit/OkHttp)](#19-сетевой-слой)
20. [HTTP-контракт эндпоинтов](#20-http-контракт-эндпоинтов)
21. [Обработка ошибок API (`apiCall`)](#21-обработка-ошибок-api-apicall)
22. [3DS — парсинг сообщений и поллинг](#22-3ds--парсинг-сообщений-и-поллинг)
23. [Поведенческие контракты из тестов](#23-поведенческие-контракты-из-тестов)
24. [Карта портирования Kotlin → Swift](#24-карта-портирования-kotlin--swift)

---

## 1. Обзор и принципы

**YP Android SDK** — headless (без UI) SDK приёма платежей, нативный аналог `@yp/web-sdk`.
Мерчант строит собственный UI; SDK предоставляет только программный контроль над платёжным
интентом.

Жизненный цикл:

```
YP.createIntent(...) ──► Intent (хэндл)
                          ├─ pay(Card|Cryptogram) ──► Authorized | ThreeDsRequired
                          │                                          └─ create3dsView(...).mount(container)
                          ├─ getPaymentMethod(СБП/SberPay/...) ──► AltPayFlow (QR/deeplink/SMS)
                          ├─ update(receiptEmail/tokenize)
                          ├─ getStatus() / waitForResult()
                          └─ events: Flow<IntentEvent>
```

Ключевые принципы:

- **Паритет с web-sdk.** Публичный API дословно зеркалит intent-handle web-sdk: те же методы,
  та же событийная модель, тот же сетевой контракт. Идиомы переведены на Kotlin
  (`Promise<T>` → `suspend fun`, `EventEmitter` → `Flow`, iframe → `WebView`).
- **Coroutines-first ядро** + тонкий **Java-фасад** (callback / `ListenableFuture`).
- **Одна точка входа** — `object YP` (в Swift — `enum YP` / статические методы).
- **PCI:** данные карты шифруются на устройстве (RSA-OAEP SHA-256) до отправки; PAN/CVV
  уходят только внутри криптограммы.

---

## 2. Топология пакетов и модулей

Gradle-модули:

| Модуль | Роль | Публикуется |
|---|---|---|
| `:sdk` | headless-ядро + публичный API | **да** (JitPack) |
| `:sample` | демо-приложение (Compose) | нет |

Пакеты внутри `:sdk` (`sdk/src/main/java/ru/ypmn/sdk/`):

| Пакет | Видимость | Содержимое |
|---|---|---|
| `ru.ypmn.sdk` | **публичный** | `YP`, `Intent`, доменные и контрактные типы, `IntentEvent`, `ThreeDsView`, `YpException` |
| `ru.ypmn.sdk.java` | **публичный** | Java-фасад: `YPJava`, `YpCallback`, `IntentEventListener`, `Cancellable`, async-обёртки |
| `ru.ypmn.sdk.internal` | internal | `IntentSession`, `IntentHandle`, `OperationQueue`, `Poller`, `ThreeDsViewImpl`, `Headers` |
| `ru.ypmn.sdk.internal.api` | internal | `ApiClient`, `YpApiService` (Retrofit), wire-only DTO, `apiCall` |
| `ru.ypmn.sdk.internal.crypto` | internal | `Cryptogram`, `BrowserInfo` |
| `ru.ypmn.sdk.internal.operations` | internal | функции операций (`pay`, `update`, `getStatus`, `getPaymentMethod`, `waitForResult`) |

Координаты публикации (JitPack): `com.github.onemantooo.android-sdk:sdk:<tag>`.
Namespace кода — `ru.ypmn.sdk` (не зависит от координат).

`YP_API_URL` по умолчанию (`BuildConfig`): `https://ypmn.ru`. `minSdk 24`, `compileSdk 35`, Java 17, Kotlin 2.1.0.

---

## 3. Зависимости

Что использует Android SDK и что искать/подобрать на iOS:

| Android | Назначение | iOS-эквивалент (ориентир) |
|---|---|---|
| kotlinx.coroutines 1.9.0 | async-ядро, `Flow` | Swift Concurrency (`async/await`, `AsyncSequence`), либо Combine |
| kotlinx.coroutines-guava | `ListenableFuture` для Java-фасада | не нужен (в Swift нет Java-совместимости) |
| kotlinx.serialization 1.7.3 | JSON | `Codable` |
| Retrofit 2.11 + converter-kotlinx + converter-scalars | HTTP-клиент, JSON + plain-text | `URLSession` + `Codable` (плюс отдельная ветка для plain-text ответа altLink) |
| OkHttp 4.12 (+logging) | транспорт, интерсепторы | `URLSession` + кастомная логика заголовков |
| androidx.core-ktx | утилиты | — |
| androidx.webkit 1.12.1 | `WebViewCompat` для 3DS postMessage-моста | `WKWebView` + `WKScriptMessageHandler` |
| Java `javax.crypto` / `java.security` | RSA-OAEP | `Security.framework` / `SecKeyCreateEncryptedData` |
| okio (транзитивно) | base64 | `Data.base64EncodedString()` |

Тестовые: JUnit4, MockWebServer, coroutines-test.

---

## 4. Модель потоков и конкурентности

Критично для корректного порта:

1. **Сериализация операций.** Все мутирующие/сетевые операции над одним интентом проходят
   через `OperationQueue` (Mutex-based). Одновременный `pay` и `update` **не** пересекаются —
   выполняются строго последовательно (single-writer). В Swift это `actor` или
   последовательная очередь задач.

2. **Горячий поток событий.** `Intent.events` — `MutableSharedFlow<IntentEvent>` с
   `extraBufferCapacity = 32`, эмиссия через `tryEmit` (не suspend, не блокирует).
   В Swift — `AsyncStream`/`PassthroughSubject` с буфером; эмиссия неблокирующая.

3. **Диспетчеры.** Ядро сессии по умолчанию работает на `Dispatchers.IO + SupervisorJob()`.
   Java-фасад имеет отдельный `facadeScope = SupervisorJob() + Dispatchers.Default`.
   ⚠️ Доставка событий/колбэков **не** маршалится на main-thread (это отмечено в коде как
   «будущий рефайн»). В iOS-порте решите явно, на каком потоке доставлять колбэки.

4. **Отмена.** Каждая async-обёртка Java-фасада возвращает `Cancellable`, чей `cancel()`
   отменяет корутину. `CancellationException` всюду перепрокидывается (не превращается в
   `Error`-событие). В Swift — `Task` + `Task.cancel()`, проброс `CancellationError`.

5. **`@Volatile`-поля** сессии (`data`, `pendingUpdate`, `publicKey`, `handle`) читаются/пишутся
   из разных корутин. В Swift изоляция достигается через `actor`.

---

## 5. Публичный API — точка входа `YP`

Файл: `YP.kt`. Kotlin `object` (синглтон без состояния) — все методы статические.

```kotlin
object YP {
    suspend fun createIntent(request: CreateIntentRequest, config: YpConfig): Intent
    suspend fun getIntent(id: String, config: YpConfig, secret: String? = null): Intent
    fun wrapIntent(data: IntentResponse, config: YpConfig): Intent
}
```

### `createIntent(request, config): Intent`
- **Suspend.** Создаёт новый интент на бэкенде.
- Шаги: `ApiClient.create(config)` → `POST api/intent/` с телом `request` → получает `IntentResponse`
  → строит `IntentSession(data, data.secret, config, api)` → возвращает хэндл через `newHandle`.
- Секрет берётся из ответа (`data.secret`).
- Ошибки сети/парсинга → `YpException` (см. §21).

### `getIntent(id, config, secret? = null): Intent`
- **Suspend.** Загружает существующий интент по `id`.
- `GET api/intent/{id}/` → `IntentResponse`. Секрет сессии = `secret ?: data.secret`
  (переданный аргумент имеет приоритет над секретом из ответа).

### `wrapIntent(data, config): Intent`
- **НЕ suspend.** Оборачивает уже полученный `IntentResponse` в хэндл **без сетевого вызова**.
  Секрет = `data.secret`. Используется, когда мерчант уже получил интент другим путём.

> Каждый из трёх методов создаёт **новый** `YpApiService` (новый OkHttp/Retrofit) под конкретный
> `config`. То есть один `config` → один клиент на интент.

---

## 6. Публичный API — `Intent`

Файл: `Intent.kt`. Интерфейс — тонкий иммутабельный фасад над `internal.IntentSession`.
Реализация — `internal.IntentHandle`.

```kotlin
interface Intent {
    val id: String
    val status: IntentStatus
    val data: IntentResponse
    val events: Flow<IntentEvent>

    suspend fun update(changes: UpdateChanges)
    suspend fun getStatus(): IntentStatus
    suspend fun pay(input: PayInput): PayResult
    fun create3dsView(result: PayResult.ThreeDsRequired): ThreeDsView
    suspend fun getPaymentMethod(method: AltPayMethod, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow
    suspend fun getPaymentMethod(method: AltPayMethod.FasterPayments, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow.FasterPaymentsFlow
    suspend fun getPaymentMethod(method: AltPayMethod.SberPay, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow.SberPayFlow
    suspend fun waitForResult(opts: WaitForResultOpts = WaitForResultOpts()): IntentStatus

    fun addEventListener(listener: IntentEventListener): Cancellable
    fun removeEventListener(listener: IntentEventListener)
}
```

### Свойства (read-only, из текущего снапшота сессии)
| Свойство | Тип | Смысл |
|---|---|---|
| `id` | `String` | `session.data.id` |
| `status` | `IntentStatus` | `IntentStatus.from(session.data.status)` — маппинг строки в enum |
| `data` | `IntentResponse` | полный текущий снапшот интента (обновляется после `update`/статусов) |
| `events` | `Flow<IntentEvent>` | горячий `SharedFlow` событий сессии |

### Методы

- **`suspend update(changes: UpdateChanges)`** — частичное обновление (`receiptEmail`/`tokenize`)
  через JSON Patch. Проходит через коалесинг (см. §14, §15). Приостанавливается до применения
  батча; при ошибке пробрасывает `YpException`.
- **`suspend getStatus(): IntentStatus`** — `GET .../status/`, возвращает и синхронизирует статус
  в сессии (эмитит `StatusChange`, если изменился).
- **`suspend pay(input: PayInput): PayResult`** — оплата картой или готовой криптограммой.
  Возвращает `Authorized` (успех) или `ThreeDsRequired(url)` (нужен 3DS). См. §15.
- **`create3dsView(result): ThreeDsView`** — фабрика View-обёртки 3DS из результата `pay`.
  **НЕ suspend**, сеть не трогает. `result.threeDsUrl` грузится в WebView при `mount`.
- **`suspend getPaymentMethod(method, opts): AltPayFlow`** — строит флоу альт-метода из встроенных
  `paymentMethods` интента. Три перегрузки: базовая (`AltPayMethod`) → `AltPayFlow`; литерал
  `FasterPayments` → `FasterPaymentsFlow`; литерал `SberPay` → `SberPayFlow` (сужение типа без каста,
  аналог string-literal overloads web-sdk).
- **`suspend waitForResult(opts): IntentStatus`** — поллинг `getStatus` до терминального статуса.
- **`addEventListener(listener): Cancellable` / `removeEventListener(listener)`** — императивная
  подписка для Java (мост `Flow` → listener). См. §12.

---

## 7. Публичные типы (доменная модель)

Файл: `Types.kt` (+ `ThreeDsView.kt` для `ThreeDsResult`).

### `IntentStatus` — enum статуса
```kotlin
enum class IntentStatus { Pending, Expired, Failed, Success;
    companion object { fun from(raw: String): IntentStatus = entries.firstOrNull { it.name == raw } ?: Pending }
}
```
- `from(raw)`: точное сопоставление по имени; **неизвестная строка → `Pending`** (не бросает).
- **Терминальные статусы** (используются в `waitForResult` и 3DS-поллинге):
  `{ Success, Failed, Expired }`. `Pending` — нетерминальный.

### `AltPayMethod` — sealed-иерархия альт-методов
```kotlin
sealed class AltPayMethod(val type: String) {
    data object FasterPayments : AltPayMethod("FasterPayments")   // СБП
    data object SberPay        : AltPayMethod("SberPay")
    data object AlfaPay        : AltPayMethod("AlfaPay")
    data object MirPay         : AltPayMethod("MirPay")
    data object TPay           : AltPayMethod("TPay")
    data object BNPL           : AltPayMethod("BNPL")

    companion object {
        @JvmStatic val entries: List<AltPayMethod> get() = listOf(FasterPayments, SberPay, AlfaPay, MirPay, TPay, BNPL)
        @JvmStatic fun fromType(type: String): AltPayMethod? = entries.firstOrNull { it.type == type }
    }
}
```
- `type` — строка, совпадающая с `PaymentMethodDto.type` бэкенда.
- `entries` — **геттер, а не поле** (намеренно: поле поймало бы цикл инициализации
  companion ↔ data object → `null` в списке). В Swift это неважно (нет `<clinit>`), но список
  должен возвращать все 6 методов.
- `fromType(type)` — обратный маппинг; неизвестный тип → `null`.

Swift-эквивалент: `enum AltPayMethod: String { case fasterPayments = "FasterPayments"; ... }`.

### `YpConfig`
```kotlin
data class YpConfig(val baseUrl: String, val headers: Map<String, String> = emptyMap())
```
- `baseUrl` — базовый URL API (напр. `https://ypmn.ru`). Нормализуется (см. §19: добавляется `/`).
- `headers` — произвольные заголовки, добавляются к **каждому** запросу.

### `CardData`
```kotlin
data class CardData(val pan: String, val expMonth: String, val expYear: String, val cvv: String) {
    override fun toString(): String  // маскирует: CardData(pan=****7037, exp=**/**, cvv=***)
}
```
- Поля — «сырые» строки (могут содержать пробелы/дефисы; нормализуются в криптограмме — только цифры).
- `expYear` — 2 цифры (напр. `"25"`), `expMonth` — 2 цифры (`"12"`).
- **`toString()` маскирует** PAN/exp/CVV — важно повторить в Swift (`CustomStringConvertible`),
  чтобы карта не утекала в логи. Логика: последние 4 цифры PAN (после фильтра нецифр), либо `****`.

### `UpdateChanges`
```kotlin
data class UpdateChanges(val receiptEmail: String? = null, val tokenize: Boolean? = null)
```
- Оба поля опциональны; редактируемые поля интента. `null` = «не менять».
- Если оба `null` при применении → `YpException("update: no editable fields provided")` (см. §15).

### `PayInput` — вход для `pay`
```kotlin
sealed interface PayInput {
    data class Card(val card: CardData) : PayInput           // SDK сам строит криптограмму
    data class Cryptogram(val cryptogram: String) : PayInput // готовая криптограмма (base64-конверт)
}
```

### `PayResult` — результат `pay`
```kotlin
sealed interface PayResult {
    data class Authorized(val data: IntentStatusResponse) : PayResult
    data class ThreeDsRequired(val threeDsUrl: String) : PayResult
}
```

### Опции
```kotlin
data class AltRequestOpts(val webview: Boolean = false, val puid: String? = null)
data class AltLinkOpts(val webview: Boolean = false, val puid: String? = null, val schema: String? = null)
data class WaitForResultOpts(val intervalMs: Long? = null, val timeoutMs: Long? = null)
```
- `WaitForResultOpts`: `null` → дефолты `intervalMs = 3000`, `timeoutMs = 600_000` (10 мин).

### `AltPayFlow` — флоу альт-метода
```kotlin
sealed interface AltPayFlow {
    val method: AltPayMethod
    val link: String?
    fun getImage(): String                                       // URL QR-картинки (синхронно)
    suspend fun getLink(opts: AltLinkOpts = AltLinkOpts()): String

    interface SberPayFlow : AltPayFlow { suspend fun sendSms(phone: String) }
    interface FasterPaymentsFlow : AltPayFlow { val banks: List<SbpBank> }
    interface GenericAltFlow : AltPayFlow
}
```
- `method` — какой метод.
- `link` — базовый deeplink из встроенных данных (`pm.link ?: pm.deeplink`), может быть `null`.
- `getImage()` — синхронный, отдаёт `pm.image ?: ""` (URL картинки QR; загрузку делает UI-слой).
- `getLink(opts)` — **suspend**, идёт в сеть за свежим deeplink (см. §15).
- `SberPayFlow.sendSms(phone)` — отправить push/SMS в приложение SberPay.
- `FasterPaymentsFlow.banks` — список банков СБП (`pm.banks ?: emptyList()`), у каждого `schema`
  для `getLink`.
- `GenericAltFlow` — для остальных методов (AlfaPay/MirPay/TPay/BNPL), без спец-функций.

---

## 8. Контрактные типы (wire-модель)

Файл: `Contract.kt`. Все `@Serializable` (kotlinx). Это публичные типы (мерчант их видит),
но по смыслу они — модель ответов бэкенда. В Swift — `Codable`.

### `CreateIntentRequest` (тело `POST api/intent/`)
```kotlin
@Serializable data class CreateIntentRequest(
    val merchantCode: String,
    val amount: Long,                       // минорные единицы (копейки): 100000 = 1000.00 RUB
    val currency: String,                   // "RUB"
    val messageScheme: String,              // "SMS" | "DMS"
    val culture: String? = null,
    val description: String? = null,
    val merchantPaymentReference: String? = null,
    val receiptEmail: String? = null,
    val accountId: String? = null,
    val tokenize: Boolean? = null,
    val restrictedPaymentMethods: List<String>? = null,
)
```
- Сериализация с `encodeDefaults = false` (см. §19) → **`null`-поля не попадают в JSON**.
  Swift: `encodeIfPresent` / пропуск `nil`.

### `IntentResponse` (ответ create/get/patch)
```kotlin
@Serializable data class IntentResponse(
    val id: String,
    val status: String,
    val secret: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val expiredAt: Long = 0,
    val merchantCode: String = "",
    val amount: Long = 0,
    val currency: String = "",
    val messageScheme: String = "",
    val description: String? = null,
    val receiptEmail: String? = null,
    val tokenize: Boolean? = null,
    val paymentMethods: List<PaymentMethodDto>? = null,
    val transactions: List<JsonObject>? = null,   // непрозрачные объекты, не типизированы
)
```
- Клиент десериализует с `ignoreUnknownKeys = true` — лишние поля игнорируются.
- `transactions` — список произвольных JSON-объектов (не разбираются SDK).

### `PaymentMethodDto`
```kotlin
@Serializable data class PaymentMethodDto(
    val type: String,           // "FasterPayments" | "SberPay" | ...
    val group: String? = null,
    val link: String? = null,
    val deeplink: String? = null,
    val image: String? = null,  // URL QR
    val banks: List<SbpBank>? = null,
)
```

### `SbpBank`
```kotlin
@Serializable data class SbpBank(
    val bankName: String,
    @SerialName("logoURL") @JsonNames("logoUrl") val logoUrl: String? = null,
    val schema: String,                                        // → query ?schema= в getLink
    val webClientUrl: String? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val isWebClientActive: Boolean? = null,                    // приходит и булевым, и строкой
    @JsonNames("package_name") val packageName: String? = null,
)
```
- ⚠️ Ключи банка расходятся между openapi и боевым ответом: спека обещает `logoUrl`,
  `package_name` и строковый `isWebClientActive`, а бэкенд отдаёт `logoURL`, `packageName`
  и JSON-булево. Принимаем оба написания — строгий разбор ронял весь `createIntent`
  («invalid response») на любом интенте с СБП. В Swift/других SDK предусмотрите то же.

### Статус
```kotlin
@Serializable data class IntentStatusData(val status: String)
@Serializable data class IntentStatusResponse(
    val intent: IntentStatusData,
    val transactions: List<JsonObject> = emptyList(),
)
```
- `GET .../status/` возвращает `IntentStatusResponse`. Успешный `pay` (без 3DS) — тоже.

---

## 9. Событийная модель `IntentEvent`

Файл: `IntentEvent.kt`.
```kotlin
sealed interface IntentEvent {
    data class Update(val intent: Intent) : IntentEvent          // поля обновлены (после update)
    data class StatusChange(val status: IntentStatus) : IntentEvent
    data class Success(val result: PayResult) : IntentEvent      // успех (в т.ч. после 3DS/waitForResult)
    data class Error(val error: Throwable) : IntentEvent
}
```

Когда что эмитится (сводка; детали — в §15, §22):

| Событие | Триггеры |
|---|---|
| `Update` | успешный `update()` (после применения PATCH и обновления `data`) |
| `StatusChange` | изменение статуса в сессии: `getStatus`, `pay`(authorized), 3DS-success |
| `Success` | `pay` → Authorized; `waitForResult` → Success; 3DS → SUCCESS |
| `Error` | ошибка в `pay`; `waitForResult` с не-Success терминалом; 3DS FAILURE |

Подписка (Kotlin): `intent.events.collect { ... }` в нужном scope.
Java: `intent.addEventListener { event -> ... }`.

`Intent.events` — горячий поток: события, эмитнутые до подписки, подписчик **не** получит
(кроме буфера `extraBufferCapacity = 32` через `tryEmit`). В Swift учтите это (hot stream).

---

## 10. `ThreeDsView` — 3DS через WebView

Файлы: `ThreeDsView.kt` (публичный контракт), `internal/ThreeDsViewImpl.kt` (реализация).

```kotlin
data class ThreeDsResult(val status: Status, val code: String? = null, val intentStatus: IntentStatus? = null) {
    enum class Status { SUCCESS, FAILURE }
}

interface ThreeDsView {
    val webView: WebView?
    fun mount(container: ViewGroup): ThreeDsView
    fun unmount()
    fun destroy()
    val results: Flow<ThreeDsResult>
}
```

Контракт:
- Создаётся через `intent.create3dsView(result: PayResult.ThreeDsRequired)`.
- `mount(container)`: создаёт `WebView`, включает JS, грузит `threeDsUrl`, добавляет во `container`,
  ставит postMessage-мост и запускает fallback-поллинг статуса. Возвращает `this` (chaining).
- `unmount()`: отменяет поллинг, снимает WebView с родителя (WebView не уничтожается).
- `destroy()`: помечает `settled = true`, `unmount()`, уничтожает WebView.
- `results` — `SharedFlow<ThreeDsResult>` с `replay = 1` (последний результат доступен поздним
  подписчикам). Эмитится ровно один раз (`settled`-guard).

Механика завершения — два независимых сигнала (см. §22):
1. **postMessage-мост** через `androidx.webkit` (быстрый путь).
2. **Поллинг `getStatus`** каждые 3000 мс (надёжный fallback).

Swift-эквивалент: `WKWebView` + `WKUserContentController.add(handler, name: "YpBridge")` +
инъекция document-start скрипта + параллельный поллинг статуса. `ThreeDsResult` через
`AsyncStream` с сохранением последнего значения.

---

## 11. Ошибки — `YpException`

Файл: `YpException.kt`.
```kotlin
class YpException(message: String, cause: Throwable? = null) : Exception(message, cause)
```
Единственный тип ошибок, который SDK бросает наружу из своих операций (сетевые/парсинг/валидация
мапятся в него, см. §21). Swift-эквивалент: `struct YpError: Error { let message: String; let cause: Error? }`.

---

## 12. Java-фасад

Пакет `ru.ypmn.sdk.java`. **Для iOS не портируется** (это Java-совместимость для Java-only мерчей),
но показывает, какие async-обёртки существуют и семантику отмены/подписки. Приведено для полноты.

### Интерфейсы
```kotlin
fun interface Cancellable { fun cancel() }                                     // Cancellable.kt
fun interface IntentEventListener { fun onEvent(event: IntentEvent) }          // IntentEventListener.kt
interface YpCallback<T> { fun onSuccess(result: T); fun onError(error: Throwable) }  // YpCallback.kt
```

### `YPJava` (YPJava.kt)
```kotlin
object YPJava {
    @JvmStatic fun createIntent(request, config, cb: YpCallback<Intent>): Cancellable
    @JvmStatic fun createIntentFuture(request, config): ListenableFuture<Intent>
}
```

### Async-обёртки-расширения над `Intent`/`AltPayFlow`
```kotlin
fun Intent.payAsync(input, cb): Cancellable
fun Intent.updateAsync(changes, cb): Cancellable
fun Intent.getStatusAsync(cb): Cancellable
fun Intent.waitForResultAsync(opts, cb): Cancellable
fun Intent.getPaymentMethodAsync(method, cb): Cancellable            // + сужающие перегрузки Faster/Sber
fun AltPayFlow.getLinkAsync(opts, cb): Cancellable
fun AltPayFlow.SberPayFlow.sendSmsAsync(phone, cb): Cancellable
```

Механика (`dispatchCallback`): запускает корутину в `facadeScope`, при успехе → `cb.onSuccess`,
при ошибке → `cb.onError` (кроме `CancellationException` — она перепрокидывается). Возвращает
`Cancellable { job.cancel() }`.

### Listener-мост (Bridge.kt)
```kotlin
internal val facadeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
internal fun bridgeListener(s: IntentSession, l: IntentEventListener): Cancellable
internal fun unbridgeListener(l: IntentEventListener)
```
- `bridgeListener`: подписывает listener на `session.events` (через `onEach{}.launchIn(scope)`),
  хранит job в `ConcurrentHashMap<listener, job>`. Повторная регистрация того же listener
  **заменяет** прежнюю (старый job отменяется). Возвращаемый `Cancellable` отменяет именно
  этот job и снимает из map только если он всё ещё замаплен на него.
- Это реализация `Intent.addEventListener/removeEventListener`.

---

## 13. Внутренняя реализация — сессия и хэндл

### `IntentSession` (internal/IntentSession.kt) — изменяемое состояние
```kotlin
internal class IntentSession(
    @Volatile var data: IntentResponse,
    val secret: String,
    val config: YpConfig,
    val api: YpApiService,
    context: CoroutineContext = Dispatchers.IO + SupervisorJob(),
) {
    val id: String get() = data.id
    val events = MutableSharedFlow<IntentEvent>(extraBufferCapacity = 32)
    val queue = OperationQueue()
    val scope = CoroutineScope(context)
    val pendingMutex = Mutex()
    @Volatile var pendingUpdate: PendingUpdate? = null
    @Volatile var publicKey: String? = null           // кэш PEM-ключа на сессию
    @Volatile var handle: Intent? = null
}

internal class PendingUpdate(
    var changes: UpdateChanges,
    val waiters: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
)
```

Мутатор статуса (single-writer, вызывать только внутри `queue.enqueue`):
```kotlin
internal fun IntentSession.setStatus(raw: String) {
    if (data.status == raw) return                    // no-op если не изменилось
    data = data.copy(status = raw)
    events.tryEmit(IntentEvent.StatusChange(IntentStatus.from(raw)))
}
```

### `IntentHandle` (internal/IntentHandle.kt) — реализация `Intent`
Тонкий делегат: все свойства читают `session.data`; все методы вызывают функции
`internal.operations.*`. Перегрузки `getPaymentMethod(Faster/Sber)` кастуют результат базовой
операции к нужному подтипу (`as FasterPaymentsFlow` / `as SberPayFlow`) — безопасно, т.к. ядро
по этому `method` гарантированно строит соответствующий объект.

```kotlin
internal fun newHandle(s: IntentSession): Intent {
    val h = IntentHandle(s); s.handle = h; return h    // связывает handle обратно в сессию
}
```
`session.handle` нужен, чтобы эмитить `IntentEvent.Update(intent)` после `update`.

Swift-эквивалент всей связки: `actor IntentSession` (хранит состояние) + `final class Intent`
(публичный фасад, держит ссылку на actor).

---

## 14. Очередь операций и коалесинг

### `OperationQueue` (internal/OperationQueue.kt)
```kotlin
internal class OperationQueue {
    private val mutex = Mutex()
    suspend fun <T> enqueue(block: suspend () -> T): T = mutex.withLock { block() }
}
```
- Гарантирует **строго последовательное** выполнение блоков в порядке захвата lock (FIFO-fairness
  Kotlin `Mutex`). Блок держит lock **на всё время своего выполнения**, включая внутренние сетевые
  вызовы — следующая операция не стартует, пока текущая не завершилась (доказано
  `OperationQueueTest.serializes_overlapping_operations`).
- Swift-эквивалент: `actor` метод, или `AsyncSemaphore(value: 1)`, или последовательный `Task`-канал.

> ⚠️ Реентрантность: `getPaymentMethod` выполняется внутри `enqueue`, а `getLink`/`sendSms` этого
> флоу — тоже `enqueue`, но вызываются **позже** (после возврата `getPaymentMethod`), поэтому
> дедлока нет. Kotlin `Mutex` **не** реентрантен — не оборачивайте вложенный `enqueue` внутри
> уже удерживающего lock блока. В Swift-порте соблюдайте тот же инвариант.

### Коалесинг `update` (детали в §15)
Пока батч PATCH не начал исполняться, конкурентные `update()` **мёржатся** в один
`pendingUpdate.changes`, и все ждущие резолвятся вместе — итог: один сетевой PATCH на пачку
(доказано `CreateAndUpdateTest.coalesces_concurrent_updates_into_one_patch`).

---

## 15. Операции (`internal.operations`)

Файл: `internal/operations/Operations.kt`. Это ядро поведения — Swift-порт должен воспроизвести
каждую функцию точно.

### `pay(s, input): PayResult`
```
enqueue {
  try {
    cryptogram =
      when input:
        Cryptogram -> input.cryptogram
        Card       -> pk = s.publicKey ?: getPublicKey().publicKey.also { cache }
                      Cryptogram.build(pk, input.card, BrowserInfo.collectBase64())
    raw: JsonObject = POST api/intent/pay/  { id, paymentMethod = "Card", cryptogram }
    threeDsUrl = raw["threeDsUrl"] as? String
    if (threeDsUrl != null)
        return ThreeDsRequired(threeDsUrl)
    else
        parsed = decode<IntentStatusResponse>(raw)
        setStatus(parsed.intent.status)        // эмитит StatusChange
        result = Authorized(parsed)
        events.tryEmit(Success(result))        // эмитит Success
        return result
  } catch (e) {
    if (e is CancellationException) throw e
    events.tryEmit(Error(e)); throw e          // ошибка → и событие, и проброс
  }
}
```
Важные детали для порта:
- **`paymentMethod` всегда `"Card"`** и передаётся явно (бэкенд требует поле; при
  `encodeDefaults=false` дефолт бы выпал — потому поле без дефолта в DTO).
- Ответ `pay` парсится **как сырой `JsonObject`**, затем ветвление по наличию `threeDsUrl`.
  Успех → `IntentStatusResponse`.
- Публичный ключ **кэшируется** в сессии (`s.publicKey`) — второй `pay` картой ключ не тянет.
- Для `Cryptogram`-входа ключ/криптограмма/BrowserInfo вообще не нужны.

### `getStatus(s): IntentStatus`
```kotlin
val res = apiCall { s.api.getStatus(s.id) }   // СЕТЬ вне очереди
s.queue.enqueue { s.setStatus(res.intent.status) }   // синхронизация статуса — в очереди
return IntentStatus.from(res.intent.status)
```
⚠️ Тонкость: сам GET выполняется **вне** очереди (чтобы поллинг не блокировал операции), а мутация
статуса — внутри очереди (single-writer). Возврат — из свежего ответа, не из `s.data`.

### `update(s, changes)` — коалесинг
```
deferred = CompletableDeferred()
pendingMutex.withLock {
  if (pendingUpdate != null) {
      pendingUpdate.changes = merge(pendingUpdate.changes, changes)   // домёржить
      pendingUpdate.waiters += deferred
  } else {
      batch = PendingUpdate(changes); batch.waiters += deferred
      pendingUpdate = batch
      scope.launch { queue.enqueue {
          pendingMutex.withLock { pendingUpdate = null }   // закрыть окно коалесинга ДО сети
          try {
              ops = buildPatchOps(batch.changes)
              updated = PATCH api/intent/{id}/ (secret header, ops)
              s.data = updated
              s.handle?.let { events.tryEmit(Update(it)) }   // эмитит Update
              batch.waiters.forEach { it.complete(Unit) }
          } catch (e) {
              batch.waiters.forEach { it.completeExceptionally(e) }
              if (e is CancellationException) throw e
          }
      } }
  }
}
deferred.await()     // suspend до применения батча
```
- `merge(a, b)`: поле берётся из `b`, если не `null`, иначе из `a`
  (`receiptEmail = b.receiptEmail ?: a.receiptEmail`, аналогично `tokenize`).
- `buildPatchOps(changes)`: для непустых полей — `PatchOp("replace", "/receiptEmail", <value>)` и/или
  `/tokenize`. Если ops пуст → `YpException("update: no editable fields provided")`.
- Окно коалесинга закрывается (`pendingUpdate = null`) **внутри** `enqueue` **до** сетевого вызова:
  всё, что пришло до старта батча, попадёт в него; всё, что после — начнёт новый батч.

### `getPaymentMethod(s, method, opts): AltPayFlow`
```
enqueue {
  pm = s.data.paymentMethods?.firstOrNull { it.type == method.type }
       ?: throw YpException("getPaymentMethod: метод ${method.type} отсутствует в интенте")
  image = pm.image ?: ""
  baseLink = pm.link ?: pm.deeplink

  fun fetchLink(linkOpts): String = enqueue {              // отдельный enqueue при getLink
      GET alt/{id}/link/{method.type}/?webview=&puid=&schema=
        webview = if (linkOpts.webview || opts.webview) "true" else null
        puid    = linkOpts.puid ?: opts.puid
        schema  = linkOpts.schema
  }

  when (method) {
    SberPay        -> SberPayFlow  { method, link=baseLink, getImage()=image, getLink=fetchLink,
                                     sendSms(phone) = enqueue { POST .../send-sms/ {phone} } }
    FasterPayments -> FasterPaymentsFlow { method, link=baseLink, banks = pm.banks ?: [], getImage, getLink }
    else           -> GenericAltFlow { method, link=baseLink, getImage, getLink }
  }
}
```
- Метод должен присутствовать во встроенных `paymentMethods` интента, иначе `YpException`.
- `getLink`/`sendSms` — **каждый — отдельный `enqueue`** (сериализуются с прочими операциями).
- `webview`: булев флаг из `linkOpts` **или** `opts` → строка `"true"` либо `null` (при `null`
  Retrofit опускает query-параметр). `puid`: `linkOpts` приоритетнее `opts`. `schema`: только из
  `linkOpts`.

### `waitForResult(s, opts): IntentStatus`
```
status = pollUntil(
  fn = { getStatus(s) },                    // operations.getStatus (синхронизирует статус)
  isDone = { it in {Success,Failed,Expired} },
  intervalMs = opts.intervalMs ?: 3000,
  timeoutMs  = opts.timeoutMs  ?: 600_000,
)
if (status == Success)
    events.tryEmit(Success(Authorized(IntentStatusResponse(IntentStatusData("Success"), s.data.transactions ?: []))))
else
    events.tryEmit(Error(YpException("payment ${status.name.lowercase()}")))   // "payment failed" | "payment expired"
return status
```
- Таймаут поллинга → `pollUntil` бросает `YpException("pollUntil: timeout")` (см. §16).

---

## 16. Поллер (`pollUntil`)

Файл: `internal/Poller.kt`.
```kotlin
internal suspend fun <T> pollUntil(
    fn: suspend () -> T,
    isDone: (T) -> Boolean,
    intervalMs: Long = 3000,
    timeoutMs: Long = 600_000,
): T {
    var elapsed = 0L
    var value = fn()                          // первый вызов — сразу, без задержки
    while (!isDone(value)) {
        if (elapsed >= timeoutMs) throw YpException("pollUntil: timeout")
        delay(intervalMs)
        elapsed += if (intervalMs <= 0L) 1L else intervalMs   // защита от бесконечного цикла при interval<=0
        value = fn()
    }
    return value
}
```
Контракт (доказан `PollerTest`):
- `fn` вызывается **сразу** (до первой задержки), затем после каждого `intervalMs`.
- Проверка таймаута — **перед** задержкой, на входе в итерацию цикла.
- `intervalMs <= 0` → `elapsed` инкрементится на `1` (иначе таймаут по времени никогда не наступит).
- Таймаут → `YpException`.

---

## 17. Криптограмма — точный формат

Файл: `internal/crypto/Cryptogram.kt`. **Самая критичная часть для паритета** — Swift обязан
выдать байт-в-байт совместимый конверт.

### Алгоритм `build(publicKeyPem, card, browserInfoBase64 = "", keyVersion = 1): String`

1. Нормализация: `pan`, `cvv`, `expMonth`, `expYear` — **только цифры** (`filter(Char::isDigit)`).
2. Валидация: `pan.length < 13` → `YpException("cryptogram: invalid PAN")`.
3. Шифруемый payload (строго такой JSON, только PAN и CVV):
   ```json
   {"PAN":"<pan_digits>","cvv":"<cvv_digits>"}
   ```
4. `value = base64( RSA-OAEP-SHA256( payload_utf8 ) )` (см. ниже параметры).
5. Конверт:
   ```json
   {
     "Type": "Card",
     "BrowserInfoBase64": "<browserInfoBase64>",
     "CardInfo": {
       "FirstSixDigits": "<pan[0..6)>",
       "LastFourDigits": "<pan[-4..]>",
       "ExpDateYear": "<expYear_digits>",
       "ExpDateMonth": "<expMonth_digits>"
     },
     "KeyVersion": <keyVersion>,
     "Value": "<value>"
   }
   ```
   Сериализация конверта — `Json { encodeDefaults = true }` (все поля присутствуют).
   Порядок ключей — как в DTO (`Type, BrowserInfoBase64, CardInfo, KeyVersion, Value`),
   а `CardInfo`: `FirstSixDigits, LastFourDigits, ExpDateYear, ExpDateMonth`.
6. Результат: `base64( utf8( JSON(конверт) ) )` — это и есть строка криптограммы,
   которая уходит как `PayInput.Cryptogram` / поле `cryptogram` тела `pay`.

### Параметры RSA-OAEP (обязаны совпасть на iOS)
```
Transformation:  RSA/ECB/OAEPWithSHA-256AndMGF1Padding
OAEPParameterSpec: digest = SHA-256, MGF = MGF1(SHA-256), PSource = PSpecified.DEFAULT (label = пусто)
Ключ:            X.509 / SPKI DER, из PEM (снимаются заголовки BEGIN/END PUBLIC KEY и все whitespace, base64-decode)
```
Swift (`Security.framework`): `SecKeyCreateEncryptedData` с алгоритмом
`.rsaEncryptionOAEPSHA256`. Ключ: `SecKeyCreateWithData` из DER (снять PEM-обёртку). Убедитесь,
что это **OAEP c SHA-256 и MGF1-SHA-256, без label** — иначе бэкенд не расшифрует.

### Проверочный вектор (из `CryptogramTest`)
- Вход: `CardData(pan="4652 0354 4066 7037", expMonth="12", expYear="25", cvv="123")`.
- Конверт после декода base64 → JSON: `Type="Card"`, `BrowserInfoBase64=""` (в тесте передан дефолт),
  `KeyVersion=1`, `CardInfo.FirstSixDigits="465203"` (пробелы вычищены), `LastFourDigits="7037"`,
  `ExpDateYear="25"`, `ExpDateMonth="12"`, `Value` — непустой base64.
- Расшифровка `Value` приватным ключом → строго `{"PAN":"4652035440667037","cvv":"123"}`.

> ⚠️ **Отличие от CloudPayments** (не копировать их формат): у CP `RSA/ECB/PKCS1Padding`,
> payload = строка `PAN@exp@cvv@publicId`, `Type="CloudCard"`, есть поле `Format`. Наш формат —
> OAEP + JSON `{PAN,cvv}` + `Type="Card"`. Используйте **наш**.

---

## 18. BrowserInfo

Файл: `internal/crypto/BrowserInfo.kt`.

> ⚠️ **Отличие от DESIGN.md.** Дизайн предполагал `BrowserInfoBase64 = ""` (как CloudPayments).
> Фактический код (коммит `0aa043e`, «backend rejects empty BrowserInfoBase64») **заполняет**
> структуру — пустое значение бэкенд отвергает: `"BrowserInfoBase64 contains invalid browser info
> structure"`. **Порт обязан заполнять BrowserInfo**, а не слать пустую строку.

`collectBase64(): String` возвращает `base64( utf8( JSON ) )` объекта:
```json
{
  "AcceptHeader": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "ColorDepth": 24,
  "IpAddress": "0.0.0.0",
  "Language": "<Locale.getDefault().toLanguageTag()>",
  "ScreenHeight": <displayMetrics.heightPixels | 0>,
  "ScreenWidth": <displayMetrics.widthPixels | 0>,
  "TimeZone": <offset_minutes>,
  "UserAgent": "<System.getProperty(\"http.agent\") | \"Android\">",
  "JavaEnabled": false,
  "JavaScriptEnabled": true
}
```
Детали для порта:
- `TimeZone` = смещение текущей TZ в **минутах** (`getOffset(now) / 60000`).
- `ScreenWidth/Height` — из системных метрик экрана; при недоступности → `0`.
- `Language` — BCP-47 language tag (напр. `ru-RU`).
- `UserAgent` — на iOS подставьте разумный аналог (напр. строку с версией iOS/устройства).
- Сериализация `Json { encodeDefaults = true }`.
- Собирается только для `PayInput.Card` (для готовой криптограммы не вызывается).

Эта структура — зеркало web-sdk `collectBrowserInfo` с device-эквивалентами.

---

## 19. Сетевой слой

Файл: `internal/api/ApiClient.kt`.

```kotlin
internal object ApiClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    fun create(config: YpConfig): YpApiService { ... }
    val sharedJson: Json get() = json
}
```

Сборка клиента `create(config)`:
1. **headerInterceptor** — добавляет каждый заголовок из `config.headers` к каждому запросу.
2. **contentTypeFix** (network-интерсептор) — если тело запроса имеет subtype `json`, принудительно
   ставит `Content-Type: application/json` **без** `; charset=utf-8`.
   ⚠️ Причина: бэкенд принимает строго `application/json`; OkHttp сам дописывает `; charset=utf-8`
   → `400 "Request body must be valid JSON"`. Network-интерсептор выполняется после
   BridgeInterceptor и перетирает заголовок на голый. **В iOS-порте ставьте `Content-Type:
   application/json` вручную на всех JSON-запросах.**
3. **baseUrl** нормализуется: если не заканчивается на `/`, добавляется `/`.
4. Конвертеры Retrofit в порядке: `ScalarsConverterFactory` (для plain-text ответа `altLink`),
   затем kotlinx-serialization для `application/json`.

Конфиг JSON:
- Десериализация: `ignoreUnknownKeys = true` (лишние поля игнорируются).
- Сериализация: `encodeDefaults = false` (поля со значением-дефолтом **не** попадают в JSON —
  отсюда трюк с `paymentMethod` без дефолта в `CardPaymentRequest`).

> `sharedJson` тем же инстансом используется в `apiCall` (для парсинга error-body) и в `pay`
> (для `decodeFromJsonElement` сырого ответа).

---

## 20. HTTP-контракт эндпоинтов

Файл: `internal/api/YpApiService.kt` (Retrofit-интерфейс). Пути **относительно** нормализованного
`baseUrl` (с завершающим `/`). Ниже — точный контракт для ручной реализации на iOS.

| Операция | Метод | Путь | Заголовки | Тело запроса | Ответ |
|---|---|---|---|---|---|
| create | `POST` | `api/intent/` | config.headers, `Content-Type: application/json` | `CreateIntentRequest` (JSON) | `IntentResponse` |
| get | `GET` | `api/intent/{id}/` | config.headers | — | `IntentResponse` |
| patch (update) | `PATCH` | `api/intent/{id}/` | + `X-Intent-Secret: <secret>` | `List<PatchOp>` (JSON Patch) | `IntentResponse` |
| status | `GET` | `api/intent/{id}/status/` | config.headers | — | `IntentStatusResponse` |
| public key | `GET` | `api/intent/pay/key/` | config.headers | — | `PublicKeyResponse` `{publicKey}` |
| pay | `POST` | `api/intent/pay/` | `Content-Type: application/json` | `CardPaymentRequest` `{id,paymentMethod,cryptogram}` | сырой `JsonObject` (→ `IntentStatusResponse` или `{threeDsUrl}`) |
| alt link | `GET` | `alt/{id}/{view}/{method}/` | config.headers | — (query: `webview`,`puid`,`schema`) | **plain text** (строка-deeplink) |
| send SMS | `POST` | `api/intent/{id}/send-sms/` | `Content-Type: application/json` | `SendSmsRequest` `{phone}` | — (пусто) |

Заметки:
- **Все пути с завершающим `/`** — важно (бэкенд чувствителен).
- `alt link`: `view` всегда `"link"`, `method` = `AltPayMethod.type`. Query-параметры добавляются
  только если не `null`. Пример из теста: `/alt/i1/link/FasterPayments/?schema=bank100000`.
- `alt link` возвращает **plain text**, не JSON (отсюда `ScalarsConverterFactory`).
- Секрет уходит **только** в PATCH, в заголовке `X-Intent-Secret` (см. §ниже).

### Wire-only DTO (internal/api/Dto.kt)
```kotlin
@Serializable data class PublicKeyResponse(val publicKey: String)
@Serializable data class PatchOp(val op: String, val path: String, val value: JsonElement)
@Serializable data class CardPaymentRequest(val id: String, val paymentMethod: String, val cryptogram: String)
@Serializable data class ThreeDsResponseDto(val threeDsUrl: String)
@Serializable data class SendSmsRequest(val phone: String)
@Serializable data class ErrorResponseDto(val message: String = "")
```
- `PatchOp.value` — произвольный JSON (`JsonPrimitive` для email/tokenize).
- `CardPaymentRequest.paymentMethod` — без дефолта (см. §15/§19).

### Заголовок секрета (internal/Headers.kt)
```kotlin
internal object Headers { const val INTENT_SECRET = "X-Intent-Secret" }
```
> ⚠️ **Открытый вопрос** (в коде помечен): web-sdk шлёт `X-Intent-Secret`, CloudPayments — `secret`.
> Текущий выбор — `X-Intent-Secret`. Подтвердите с бэкендом перед релизом iOS.

---

## 21. Обработка ошибок API (`apiCall`)

Файл: `internal/api/ApiCall.kt`. Обёртка вокруг каждого сетевого вызова, мапящая исключения в
`YpException`:
```kotlin
internal suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (CancellationException) { throw это }                                  // отмену не глотаем
} catch (HttpException e) {                                                     // 4xx/5xx
    serverMsg = try { errorBody → ErrorResponseDto.message (если непустой) } else null
    throw YpException(serverMsg ?: "HTTP ${e.code()}", e)
} catch (IOException e)               { throw YpException("network error", e) }
} catch (SerializationException e)    { throw YpException("invalid response", e) }
```
Порядок и семантика для порта:
1. **Отмена** (`CancellationError`) — пробрасывается как есть, не оборачивается.
2. **HTTP-ошибка** — пытаемся достать `message` из тела ошибки (`{"message": "..."}`); если пусто/не
   распарсилось — `"HTTP <code>"`. Оригинал прикладываем как `cause`.
3. **Сетевая ошибка** (I/O) — `YpException("network error", e)`.
4. **Ошибка десериализации** — `YpException("invalid response", e)`.

Swift: обернуть `URLSession`-вызовы аналогично; извлекать `message` из error-body для читаемого
текста ошибки от бэкенда.

---

## 22. 3DS — парсинг сообщений и поллинг

Файл: `internal/ThreeDsViewImpl.kt`.

### Парсер postMessage
```kotlin
internal fun parseThreeDsMessage(raw: String?): ThreeDsResult? {
    if (raw.isNullOrBlank()) return null
    code = try { JSON(raw)["code"] as string } catch { null } ?: return null
    return if (code == "0") ThreeDsResult(SUCCESS, "0", IntentStatus.Success)
           else            ThreeDsResult(FAILURE, code, IntentStatus.Failed)
}
```
Контракт (доказан `ThreeDsMessageTest`):
- `{"code":"0"}` → **SUCCESS**, `intentStatus = Success`.
- `{"code":"<любое иное>"}` → **FAILURE**, `intentStatus = Failed`, `code` сохраняется.
- Нет ключа `code` / не-JSON / `null` / пусто → **`null`** (сообщение игнорируется).

### `mount(container)` — жизненный цикл
1. `unmount()` + уничтожить прежний WebView (идемпотентность).
2. Создать `WebView`, `javaScriptEnabled = true`, `WebViewClient()` (навигация остаётся внутри).
3. **postMessage-мост** (если поддержаны `WEB_MESSAGE_LISTENER` и `DOCUMENT_START_SCRIPT`):
   - `addWebMessageListener(wv, "YpBridge", {"*"}) { message -> parseThreeDsMessage(message.data)?.let { settle(...) } }`
   - `addDocumentStartJavaScript(wv, "<форвардер>", {"*"})`, где форвардер:
     ```js
     window.addEventListener('message', function(e){
       try { YpBridge.postMessage(typeof e.data==='string' ? e.data : JSON.stringify(e.data)); } catch(_){}
     })
     ```
4. `loadUrl(threeDsUrl)`, добавить WebView в контейнер.
5. Если ещё не `settled` — запустить `beginPolling()`.

### `beginPolling()` — fallback-сигнал
```
scope.launch {
  while (!settled) {
    delay(3000)
    if (settled) break
    status = try { getStatus(session) } catch { null }
    if (status in {Success, Failed, Expired}) {
        settle(if Success then SUCCESS else FAILURE, intentStatus = status); break
    }
  }
}
```
Работает даже если postMessage недоступен (старые WebView / iOS без моста).

### `settle(status, code?, intentStatus?)` — терминация (ровно один раз)
```
if (settled) return
settled = true; отменить pollJob
results.tryEmit(ThreeDsResult(status, code, intentStatus))
scope.launch {
  if (status == SUCCESS) {
      queue.enqueue { setStatus("Success") }               // синхронизировать статус
      events.tryEmit(Success(Authorized(IntentStatusResponse(IntentStatusData("Success"), data.transactions ?: []))))
  } else {
      events.tryEmit(Error(YpException("3DS failed")))
  }
}
```

### `unmount()` / `destroy()`
- `unmount()`: отменить `pollJob`, снять WebView с родителя (не уничтожать).
- `destroy()`: `settled = true`, `unmount()`, `webView.destroy()`, `webView = null`.

Swift-порт: `WKWebView`; мост через `WKUserContentController` (`add(self, name: "YpBridge")` +
`WKUserScript` at `.atDocumentStart` с тем же JS-форвардером; сообщения приходят в
`userContentController(_:didReceive:)`). Параллельно — тот же поллинг статуса. `results` — через
`AsyncStream` (буфер последнего значения = аналог `replay = 1`). Guard `settled` — обязателен, чтобы
результат эмитился один раз.

---

## 23. Поведенческие контракты из тестов

Сводка проверяемых инвариантов (для чек-листа паритета iOS). Источник — `sdk/src/test/...`.

| Тест | Инвариант |
|---|---|
| `CreateAndUpdateTest.create_then_getStatus` | create возвращает `id`; getStatus читает статус из ответа `status` |
| `CreateAndUpdateTest.update_sends_json_patch_with_secret` | PATCH на `/api/intent/{id}/`, заголовок `X-Intent-Secret`, тело содержит `"/receiptEmail"` |
| `CreateAndUpdateTest.coalesces_concurrent_updates_into_one_patch` | два конкурентных `update` → **один** PATCH с обеими операциями (`/receiptEmail` и `/tokenize`) |
| `PayTest.pay_with_cryptogram_authorized` | тело `pay` содержит `"paymentMethod":"Card"`; успех → `Authorized` |
| `PayTest.pay_returns_3ds_required` | ответ `{threeDsUrl}` → `ThreeDsRequired(url)` |
| `AltPayTest.fasterPayments_exposes_banks_and_getLink` | `banks` заполнен; `getImage()` = URL; `getLink(schema=...)` → GET `/alt/i1/link/FasterPayments/?schema=bank100000`, возвращает plain-text deeplink |
| `AltPayTest.fromType_maps_known_and_unknown` | `fromType("SberPay")`/`"FasterPayments"` → метод; неизвестный → `null` |
| `AltPayTest.generic_method_returns_base_flow` | `AlfaPay` → `GenericAltFlow` |
| `AltPayTest.waitForResult_polls_until_success` | поллинг Pending→Success завершает `waitForResult` со `Success` |
| `OperationQueueTest.runs_serially_in_order` | очередь сохраняет порядок FIFO |
| `OperationQueueTest.serializes_overlapping_operations` | операция держит lock на всё время (включая delay) — нет чередования |
| `PollerTest.polls_until_done` | `fn` зовётся до `isDone` (здесь 3 раза) |
| `PollerTest.throws_on_timeout` | таймаут → `YpException` |
| `ThreeDsMessageTest.*` | `code=="0"`→SUCCESS; иное→FAILURE; нет/мусор→`null` |
| `CryptogramTest.envelope_has_web_sdk_structure` | структура конверта (см. §17) |
| `CryptogramTest.value_decrypts_to_pan_cvv_json` | `Value` расшифровывается в `{"PAN":...,"cvv":...}` |

Прочие тесты в репозитории: `ApiClientTest`, `BrowserInfoTest`, `CardDataMaskTest`,
`DtoSerializationTest`, `ThreeDsPollingTest`, `JavaFacadeTest` — покрывают, соответственно, сборку
клиента/Content-Type, структуру BrowserInfo, маскирование `CardData.toString()`, сериализацию DTO,
поллинг 3DS-fallback и компиляцию/работу Java-фасада.

---

## 24. Карта портирования Kotlin → Swift

| Kotlin (Android) | Swift (iOS) |
|---|---|
| `object YP` | `enum YP` со `static func` (или `struct` без инстансов) |
| `suspend fun … : T` | `func … async throws -> T` |
| `Flow<IntentEvent>` | `AsyncStream<IntentEvent>` (или Combine `AnyPublisher`) |
| `MutableSharedFlow(extraBufferCapacity=32)` | `AsyncStream` с буфером / `PassthroughSubject` |
| `SharedFlow(replay=1)` (results) | `AsyncStream` с сохранением последнего / `CurrentValueSubject` |
| `OperationQueue` (Mutex) | `actor` или последовательная очередь / `AsyncSemaphore(1)` |
| `@Volatile var` в сессии | состояние внутри `actor` |
| `CompletableDeferred<Unit>` (коалесинг) | `CheckedContinuation` / `Task` + await |
| `YpException` | `struct YpError: Error` |
| `CancellationException` проброс | `CancellationError` / `Task.checkCancellation()` |
| `sealed interface PayInput/PayResult/AltPayFlow` | `enum` с associated values / протокол + конформансы |
| `data class` | `struct: Codable/Equatable` |
| `@SerialName("logoURL")` | `CodingKeys` |
| kotlinx.serialization `JsonObject` (transactions) | `[String: AnyCodable]` или сырой `Data` |
| Retrofit + ScalarsConverter | `URLSession`; для `altLink` — читать тело как `String`, не JSON |
| OkHttp Content-Type fix | вручную `request.setValue("application/json", forHTTPHeaderField: "Content-Type")` |
| `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` | `SecKeyCreateEncryptedData(key, .rsaEncryptionOAEPSHA256, data)` |
| `WebViewCompat.addWebMessageListener` + document-start JS | `WKWebView` + `WKScriptMessageHandler` + `WKUserScript(.atDocumentStart)` |
| Java-фасад (`YPJava`, callbacks) | не нужен (Swift async/await — первичный API) |

### Минимальный сценарий использования (целевой iOS-эквивалент)
```swift
// Карта + 3DS
let intent = try await YP.createIntent(
    CreateIntentRequest(merchantCode: "term", amount: 100000, currency: "RUB", messageScheme: "SMS"),
    config: YpConfig(baseUrl: "https://ypmn.ru"))

switch try await intent.pay(.card(CardData(pan: pan, expMonth: mm, expYear: yy, cvv: cvv))) {
case .authorized(let data): // успех
case .threeDsRequired(let res):
    let view = intent.create3dsView(res)   // WKWebView-обёртка
    view.mount(into: container)
    for await result in view.results { /* SUCCESS/FAILURE */ }
}

// СБП
let flow = try await intent.getPaymentMethod(.fasterPayments)   // → FasterPaymentsFlow
let qrUrl = flow.getImage()
let deeplink = try await flow.getLink(AltLinkOpts(schema: flow.banks.first?.schema))
let status = try await intent.waitForResult()
```

---

## Приложение A. Полный список публичных символов

**`ru.ypmn.sdk`:**
`YP` (`createIntent`, `getIntent`, `wrapIntent`) · `Intent` (интерфейс, все методы §6) ·
`IntentStatus` (+`from`) · `AltPayMethod` (+6 объектов, `entries`, `fromType`) · `YpConfig` ·
`CardData` · `UpdateChanges` · `PayInput` (`Card`,`Cryptogram`) · `PayResult`
(`Authorized`,`ThreeDsRequired`) · `AltRequestOpts` · `AltLinkOpts` · `WaitForResultOpts` ·
`AltPayFlow` (+`SberPayFlow`,`FasterPaymentsFlow`,`GenericAltFlow`) · `CreateIntentRequest` ·
`IntentResponse` · `PaymentMethodDto` · `SbpBank` · `IntentStatusData` · `IntentStatusResponse` ·
`IntentEvent` (`Update`,`StatusChange`,`Success`,`Error`) · `ThreeDsView` · `ThreeDsResult`
(+`Status`) · `YpException`.

**`ru.ypmn.sdk.java`:**
`YPJava` (`createIntent`,`createIntentFuture`) · `YpCallback<T>` · `IntentEventListener` ·
`Cancellable` · extension-функции `payAsync`/`updateAsync`/`getStatusAsync`/`waitForResultAsync`/
`getPaymentMethodAsync`/`getLinkAsync`/`sendSmsAsync`.

**internal (не публично, но воспроизводится в порте по поведению):**
`IntentSession` · `IntentHandle` · `OperationQueue` · `pollUntil` · `ThreeDsViewImpl`
(+`parseThreeDsMessage`) · `Headers` · `ApiClient` · `YpApiService` · `apiCall` · `Cryptogram` ·
`BrowserInfo` · операции `pay`/`update`/`getStatus`/`getPaymentMethod`/`waitForResult`/`setStatus`
· wire-DTO (`PublicKeyResponse`,`PatchOp`,`CardPaymentRequest`,`ThreeDsResponseDto`,
`SendSmsRequest`,`ErrorResponseDto`).
