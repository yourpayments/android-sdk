# YP Android SDK — Design

> Статус: черновик на ревью. Документ живёт в монорепо рядом с `2026-06-16-yp-web-sdk-design.md`;
> при скаффолдинге будет скопирован в новый репозиторий `E:\PET\payx\android-sdk`.

## 1. Обзор и цель

Headless Android SDK для мерчантов: программное управление платёжным интентом
(создать → обновить → оплатить картой + 3DS, альт-методы СБП/SberPay), UI строит
мерчант. Это нативный Kotlin-аналог `@yp/web-sdk`.

**Главный принцип: публичный API максимально зеркалит `web-sdk`** (тот же
intent-handle, те же методы, та же событийная модель), переведённый на идиоматику
Kotlin. Мерчант, знающий web-sdk, должен узнавать API на Android без переобучения.

**Решение по размещению:** отдельный репозиторий `E:\PET\payx\android-sdk` (не в
NX-монорепо). Причины: публичный артефакт для внешних мерчантов нельзя собирать из
внутреннего монорепо без утечки `merchant-app`/`checkout-app`; Gradle/Kotlin —
чужой тулчейн для NX/Vite; iOS делается отдельной командой в своём репо (тот же
паттерн «платформа = репо»); общего кода с TS-библиотекой нет — разделяемый актив
только контракт `openapi.json`.

## 2. Объём (v1)

В объёме:
- `YP.createIntent` / `YP.getIntent` → хэндл `Intent`.
- `Intent`: `update`, `getStatus`, `pay` (карта + криптограмма), `create3dsView`
  (3DS через WebView), `getPaymentMethod` (СБП/SberPay/generic), `waitForResult`.
- Событийная модель: `Intent.events: Flow<IntentEvent>` (update/statuschange/success/error).
- Криптограмма карты на Kotlin (RSA-OAEP SHA-256), паритет с `crypto.ts`.
- Очередь операций без гонок + коалесинг `update` (как в web-sdk).
- Ядро на coroutines + `Flow`; **тонкий Java-фасад** (callback/`ListenableFuture` +
  `addEventListener`/`removeEventListener`) для Java-only мерчей — в v1 (см. §5.4).
- Sample-приложение (демо card+3DS, СБП/SberPay) — аналог `sdk-demo.html`.

Вне объёма (v1):
- Drop-in UI-модуль (`:sdk-ui`, Compose) — аналог `widget-sdk`. Заложен на будущее.
- iOS / Kotlin Multiplatform — делается отдельно, не здесь.
- Google Pay / MirPay / TPay / Dolyame — добавим после паритета с web-sdk.
- Сканер карт, BIN-info, валидаторы карт (есть у CloudPayments) — опционально позже.

## 3. Репозиторий и тулчейн

- Gradle (Kotlin DSL), version catalog `gradle/libs.versions.toml`.
- Kotlin, `minSdk 24`, `compileSdk` актуальный, Java 17 (как у CloudPayments).
- Coroutines-first. Сеть: Retrofit + OkHttp + kotlinx.serialization.
- **DI:** ручная внутренняя сборка (фабрики), без Dagger — держим зависимости
  публикуемого артефакта минимальными (CloudPayments тянет Dagger2 — нам не нужно).
- Модули:
  - `:sdk` — headless-ядро, публичный API (публикуется). = `libs/web-sdk`.
  - `:sample` — демо-приложение (НЕ публикуется). = `public/sdk-demo.html`.
  - `:sdk-ui` — опциональный Compose drop-in (позже). = `libs/widget-sdk`.

## 4. Именование и упаковка

- **Координаты (JitPack):** `com.github.onemantooo.android-sdk:sdk:<tag>` — на JitPack
  groupId выводится из хоста+владельца репо (GitHub `origin`), artifactId = имя модуля.
  Чистый `ru.ypmn:sdk` на JitPack недостижим (для него нужен Maven Central или
  self-hosted Git на ypmn-домене — как `gitpub.cloudpayments.ru` у CP).
- **Namespace/пакет кода независимы от координат** — оставляем `ru.ypmn.sdk`: брендинг
  сохраняется в коде, github-привкус только в строке зависимости.
- Точка входа: **`object YP`** — дословно как глобал `YP` в web-sdk:
  `YP.createIntent(...)`, `YP.getIntent(...)`.
- Корневой пакет: `ru.ypmn.sdk`.
  - `ru.ypmn.sdk` — публичный API (`YP`, `Intent`, типы). Сюда же входят **контрактные типы** (`CreateIntentRequest`, `IntentResponse`, `PaymentMethodDto`, `SbpBank`, `IntentStatusResponse`, `IntentStatusData`) — они публичны и видны мерчанту напрямую.
  - `ru.ypmn.sdk.internal` — session/queue/poller/emitter (не публичный).
  - `ru.ypmn.sdk.internal.api` — только wire-only DTO (`PublicKeyResponse`, `PatchOp`, `CardPaymentRequest`, `ThreeDsResponseDto`, `SendSmsRequest`, `ErrorResponseDto`) + Retrofit-сервис. Контрактные типы здесь НЕ живут.
  - `ru.ypmn.sdk.internal.crypto` — криптограмма.
  - `ru.ypmn.sdk.java` — Java-фасад (callback/`Future`-обёртки, listener-API). Публичный.
- Публикация: **JitPack** (сборка из git-тега) — `jitpack.yml` (JDK 17) + плагин
  `maven-publish` с `release`-компонентом. Без Sonatype/GPG/vanniktech — проще; так же
  публикуется CloudPayments. Dokka-доки, SemVer-теги независимо от web-sdk.

## 5. Объектная модель

Перевод идиом web-sdk → Kotlin:

| web-sdk (JS) | Android (Kotlin) |
|---|---|
| `Promise<T>` | `suspend fun(): T` |
| `EventEmitter` `on`/`off` | `Intent.events: Flow<IntentEvent>` |
| `ThreeDSFrame` (iframe) | `ThreeDsView` (обёртка над WebView) |
| `ApiClientConfig` | `YpConfig` |
| `YpError` | `YpException` |

### 5.1 Top-level — вход в SDK

```kotlin
object YP {
    suspend fun createIntent(request: CreateIntentRequest, config: YpConfig): Intent
    suspend fun getIntent(id: String, config: YpConfig, secret: String? = null): Intent
    /** Обернуть уже полученный ответ в хэндл без сетевого вызова (= wrapIntent). */
    fun wrapIntent(data: IntentResponse, config: YpConfig): Intent
}
```

### 5.2 `Intent` — иммутабельный хэндл-эмиттер

`Intent` отдаёт read-only поля `IntentResponse` (id, status, amount, currency,
paymentMethods, transactions, …) + методы + поток событий. Внутреннее изменяемое
состояние живёт в `internal.IntentSession`; `Intent` — тонкий фасад.

```kotlin
interface Intent {
    val id: String
    val status: IntentStatus
    // … остальные read-only поля IntentResponse
    val data: IntentResponse

    val events: Flow<IntentEvent>           // = intent.on(...)

    suspend fun update(changes: UpdateChanges)
    suspend fun getStatus(): IntentStatus
    suspend fun pay(input: PayInput): PayResult
    fun create3dsView(result: PayResult.ThreeDsRequired): ThreeDsView
    suspend fun getPaymentMethod(method: AltPayMethod, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow
    // перегрузки-сужения (как string-literal overloads в web-sdk): литерал метода → конкретный флоу без каста
    suspend fun getPaymentMethod(method: AltPayMethod.FasterPayments, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow.FasterPaymentsFlow
    suspend fun getPaymentMethod(method: AltPayMethod.SberPay, opts: AltRequestOpts = AltRequestOpts()): AltPayFlow.SberPayFlow
    suspend fun waitForResult(opts: WaitForResultOpts = WaitForResultOpts()): IntentStatus
}
```

### 5.3 Типы (зеркало `types.ts`)

```kotlin
data class YpConfig(
    val baseUrl: String = BuildConfig.YP_API_URL,   // прод зашит, override для теста
    val headers: Map<String, String> = emptyMap(),
)

data class UpdateChanges(
    val receiptEmail: String? = null,
    val tokenize: Boolean? = null,
)

sealed interface PayInput {
    data class Card(val card: CardData) : PayInput
    data class Cryptogram(val cryptogram: String) : PayInput
}

sealed interface PayResult {
    data class Authorized(val data: IntentStatusResponse) : PayResult
    data class ThreeDsRequired(val threeDsUrl: String) : PayResult
}

data class CardData(val pan: String, val expMonth: String, val expYear: String, val cvv: String)

// Альт-методы — sealed, перегрузки getPaymentMethod через возвращаемый подтип.
sealed interface AltPayFlow {
    val method: AltPayMethod
    val link: String?
    fun getImage(): String                                  // URL картинки QR (синхронно)
    suspend fun getLink(opts: AltLinkOpts = AltLinkOpts()): String
}
data class SberPayFlow(...) : AltPayFlow { suspend fun sendSms(phone: String) }
data class FasterPaymentsFlow(...) : AltPayFlow { val banks: List<SbpBank> }
data class GenericAltFlow(...) : AltPayFlow

data class AltRequestOpts(val webview: Boolean = false, val puid: String? = null)
data class AltLinkOpts(val webview: Boolean = false, val puid: String? = null, val schema: String? = null)
data class WaitForResultOpts(val intervalMs: Long? = null, val timeoutMs: Long? = null)
```

`AltPayMethod`, `IntentStatus`, `IntentResponse`, `CreateIntentRequest`,
`IntentStatusResponse`, `SbpBank` — из контракта (см. §11).

### 5.4 Java-фасад (v1)

Ядро — coroutines+Flow; для Java-only мерчей — тонкий **аддитивный** фасад в
`ru.ypmn.sdk.java`, мостящий на ядро через внутренний `CoroutineScope`. Kotlin-API не
дублируется и не ломается; фасад можно расширять, не трогая ядро.

`suspend`-методы → колбэк/`Future`-обёртки, возвращающие отменяемый хэндл:

```kotlin
interface Cancellable { fun cancel() }
interface YpCallback<T> { fun onSuccess(result: T); fun onError(error: Throwable) }

object YPJava {
    fun createIntent(request: CreateIntentRequest, config: YpConfig, cb: YpCallback<Intent>): Cancellable
    fun createIntentFuture(request: CreateIntentRequest, config: YpConfig): ListenableFuture<Intent>
}
// на Intent: payAsync / updateAsync / getStatusAsync / getPaymentMethodAsync / waitForResultAsync
// (колбэк-вариант + параллельно ListenableFuture через kotlinx-coroutines-guava)
```

`Flow<IntentEvent>` → listener-API (= дословный `on`/`off` web-sdk):

```kotlin
interface IntentEventListener { fun onEvent(event: IntentEvent) }
// на Intent: fun addEventListener(l: IntentEventListener): Cancellable
//            fun removeEventListener(l: IntentEventListener)
```

- 3DS (`ThreeDsView`) и так View → из Java работает без обёрток.
- `Cancellable.cancel()` = `job.cancel()` ядра (отмена корутины, см. §7).

## 6. Событийная модель

`Intent.events: Flow<IntentEvent>` — горячий `SharedFlow`. Sealed-тип зеркалит
4 события web-sdk:

```kotlin
sealed interface IntentEvent {
    data class Update(val intent: Intent) : IntentEvent          // поля обновлены после update()
    data class StatusChange(val status: IntentStatus) : IntentEvent
    data class Success(val result: PayResult) : IntentEvent      // успех (в т.ч. после 3DS)
    data class Error(val error: Throwable) : IntentEvent
}
```

Мерчант подписывается через `intent.events.collect { ... }` в нужном scope (это
естественная замена `on`/`off` + автоотписка по lifecycle). Дополнительно может быть
`Intent.statusFlow: StateFlow<IntentStatus>` как удобство.

## 7. Последовательность операций без гонок

Переносим механику `operations.ts` 1:1:
- `internal.OperationQueue` — последовательная очередь корутин (Mutex/Channel):
  `pay`, `getPaymentMethod`, `getLink`, `sendSms`, `update` сериализуются.
- **Коалесинг `update`** (§7.1 web-sdk): пока батч не начал исполняться, новые
  `update()` мёржатся в `pendingUpdate.changes`; все ждущие резолвятся вместе.
- `buildPatchOps` → JSON Patch (`replace /receiptEmail`, `replace /tokenize`).
  PATCH несёт секрет в заголовке (см. §11 — точное имя заголовка уточнить).

## 8. `ThreeDsView` — обёрнутый WebView

Аналог `ThreeDSFrame`. Грузит `threeDsUrl` в `WebView`, детектирует завершение 3DS через **`postMessage {code}`** (паритет с web-sdk `three-ds-frame.ts`): return-страница шлёт `{"code":"0"}` (успех) или иной code (сбой); мост реализован через `androidx.webkit` `addWebMessageListener` + document-start JS-форвардер. `getStatus`-поллинг служит надёжным fallback-сигналом на устройствах без `WEB_MESSAGE_LISTENER`. Механизм term-URL маркеров удалён — контракт завершения доказан реализацией web-sdk.

```kotlin
interface ThreeDsView {
    val view: WebView
    fun mount(container: ViewGroup): ThreeDsView
    fun unmount()
    fun destroy()
    val results: Flow<ThreeDsResult>          // = frame.on('result', ...)
}
data class ThreeDsResult(val status: Status, val code: String? = null, val intentStatus: IntentStatus? = null) {
    enum class Status { SUCCESS, FAILURE }
}
```

Референс — `IntentApiThreeDsDialogFragment` / `ThreeDsDialogFragment` CloudPayments
(парсинг ACS-формы через jsoup, перехват редиректа в `WebViewClient`). Предоставим
и view-обёртку, и опциональный `DialogFragment`-хелпер.

## 9. Альт-методы (СБП / SberPay)

Маппинг встроенных `paymentMethods` интента → флоу (как `getPaymentMethod` web-sdk):
- `getImage()` — URL картинки QR из встроенных данных (синхронно). Хелпер загрузки в
  `Bitmap` — на стороне sample/UI, не в ядре.
- `link` / deeplink — мерчант открывает `Intent(ACTION_VIEW, Uri.parse(link))`.
- `getLink(opts)` → **как в web-sdk** (`payWithFasterPayments`, `client.ts:158-169`):
  `GET /alt/{id}/{view}/{method}/` c `view="link"`, query `webview`/`puid`/`schema`.
  (У CP путь иной — `api/intent/alt/{id}/link/{method}` — берём НАШ.)
- `SberPayFlow.sendSms(phone)` → Push/СМС в приложение SberPay.
- `FasterPaymentsFlow.banks` — список банков СБП из встроенного метода (schema → getLink).
- `waitForResult` — поллинг `getStatus` до терминального статуса (`pollUntil`).

## 10. Криптограмма / PCI

Мерчант собирает данные карты в своём UI → шифруем на устройстве перед отправкой;
PAN/CVV уходят только в криптограмме.

**Реализуем формат `web-sdk` (`api-client/src/lib/crypto.ts`), НЕ CloudPayments:**

```
Cipher: "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"   // == crypto.subtle RSA-OAEP, SHA-256
Шифруемый payload: JSON {"PAN": "<digits>", "cvv": "<digits>"}   // только PAN+CVV
Конверт: { Type:"Card", BrowserInfoBase64:"" (как CP), CardInfo{FirstSixDigits,LastFourDigits,ExpDateYear,ExpDateMonth}, KeyVersion, Value }
Внешняя обёртка: base64(JSON(конверт))            // == strToBase64(JSON.stringify(...))
```

- Нормализация PAN/CVV/exp: только цифры (`replace(/\D/g,'')`) — как в `crypto.ts`,
  иначе `FirstSixDigits` ломается.
- `KeyVersion` — по умолчанию 1 (как web-sdk; не CP-шный "04").
- Публичный ключ берём из `getPublicKey` (API), кэшируем в сессии.
- **Cross-language test-vector** (обязательно): фикс `(publicKey, card)` → проверяем,
  что структура конверта Kotlin совпадает с TS (Type, набор полей CardInfo, что
  Value — валидный OAEP-base64, что внешняя обёртка декодится в тот же JSON).

> ⚠️ **Отличия от CloudPayments — не копировать `Card.kt`:** у CP `RSA/ECB/PKCS1Padding`,
> payload — строка `PAN@exp@cvv@publicId`, `Type="CloudCard"`, есть поле `Format`.
> Это другой бэкенд-формат; наш — OAEP + JSON + `Type="Card"`.

**BrowserInfo — как в CloudPayments (решено):** CP в нативном flow browser-info НЕ
собирает — `CardCryptogramPacket.browserInfoBase64` остаётся `""` (см.
`CardCryptogramPacket.kt:10`; `Card.createHexPacketFromData` его не задаёт), а в теле
`pay` поля нет вовсе (`CPPostIntentPayRequestBody` = `{id, paymentMethod, cryptogram,
saveCard?}`). Делаем так же: **`BrowserInfoBase64 = ""`**, без сбора
`DisplayMetrics`/`Locale`. Это единственное отклонение от `crypto.ts` (там поле несёт
браузерные данные) — в app оно пустое.

## 11. Сеть и контракт

- Endpoints (= `operations.ts` / `CloudPaymentsIntentApiService`):
  `POST api/intent`, `PATCH api/intent/{id}` (+secret header, JSON Patch),
  `POST api/intent/pay`, `GET api/intent/{id}/status`,
  `GET /alt/{id}/{view}/{method}/` (web-sdk-форма, `view="link"`, query webview/puid/schema),
  `GET .../publickey`.
- **Контракт:** вендоренная копия `contract/openapi.json` (sync — пока вручную,
  без автоматизации, по решению). Из неё генерим internal DTO+API через
  `openapi-generator` (kotlin, retrofit2 + coroutines + kotlinx.serialization).
  Альтернатива/фолбэк: ручные DTO, зеркалящие `api-client/src/lib/types.ts`, если
  кодген даёт неудобный вывод. Публичные типы (§5) в любом случае ручные.
- Retrofit + OkHttp; `suspend`-методы (не RxJava). Логирующий интерсептор только в debug.
- **Заголовок секрета:** web-sdk шлёт `X-Intent-Secret`, CloudPayments — `secret`.
  Источник правды — наш бэкенд; берём имя из `operations.ts`/openapi, помечаем как
  вопрос к бэкенду (§16).

## 12. Конфигурация и `apiUrl`

Как в web-sdk (apiUrl зашит при сборке): прод-URL в `BuildConfig.YP_API_URL`,
переопределяемый через `YpConfig.baseUrl` для тест-среды. Никакого обязательного
`init()`. По умолчанию `YpConfig` передаётся в каждый вызов `createIntent`/`getIntent`
(дословно как web-sdk передаёт `apiConfig`). Опциональный `YP.configure(config)` для
дефолтного конфига — аддитивный сахар, отложен на потом.

## 13. Сборка и публикация

- **JitPack**: публикация сборкой из git-тега. `jitpack.yml` фиксирует JDK 17; модуль
  `:sdk` применяет `maven-publish` с `release`-компонентом (JitPack его подхватывает).
- Подключение у мерча: репозиторий `maven { url 'https://jitpack.io' }` +
  `implementation("com.github.onemantooo.android-sdk:sdk:<tag>")`.
- Dokka → API-доки. `consumer-rules.pro` для R8/ProGuard (DTO/serialization).
- Java-фасад: `kotlinx-coroutines-guava` (`ListenableFuture`) — в составе того же `:sdk` артефакта.
- CI: lint + unit + (на эмуляторе) instrumented + сборка `:sample`; релиз = git-тег
  (JitPack собирает сам, отдельного publish-шага не нужно). Репо должен быть публичным.

## 14. Тестирование

- Unit: криптограмма (cross-language vector), `buildPatchOps`, коалесинг `update`,
  `OperationQueue` (сериализация), `pollUntil` (интервал/таймаут/терминальные статусы),
  маппинг `getPaymentMethod` (Sber/FasterPayments/generic).
- Instrumented: `ThreeDsView` (загрузка URL, перехват редиректа, эмит результата),
  запуск deeplink альт-метода.
- Contract: генерёный клиент компилится против пиннутой `openapi.json`.
- Java-фасад: smoke-тест из `.java`-файла (`payAsync`/`addEventListener` компилятся и работают из Java).

## 15. Скелет репозитория

```
android-sdk/
├── settings.gradle.kts            # include(":sdk", ":sample")
├── build.gradle.kts               # плагины, версии
├── jitpack.yml                    # JDK 17 для сборки на JitPack
├── gradle/libs.versions.toml
├── contract/openapi.json          # вендоренная копия (sync вручную)
├── sdk/
│   ├── build.gradle.kts           # com.android.library + maven-publish + openapi-generator
│   └── src/main/java/ru/ypmn/sdk/
│       ├── YP.kt                  # object YP (createIntent/getIntent/wrapIntent)
│       ├── Intent.kt              # interface Intent
│       ├── Types.kt               # PayInput/PayResult/AltPayFlow/UpdateChanges/...
│       ├── IntentEvent.kt         # sealed события
│       ├── ThreeDsView.kt         # 3DS WebView-обёртка
│       ├── YpException.kt
│       ├── java/                  # Java-фасад: YPJava, YpCallback, IntentEventListener, Cancellable
│       └── internal/
│           ├── IntentSession.kt   # изменяемое состояние + handle
│           ├── IntentHandle.kt    # реализация Intent поверх session
│           ├── operations/*.kt    # update/pay/getPaymentMethod/waitForResult
│           ├── OperationQueue.kt  # сериализация + коалесинг
│           ├── poller/Poller.kt   # pollUntil
│           ├── crypto/Cryptogram.kt
│           └── api/               # генерёные/ручные DTO + Retrofit-сервис
└── sample/
    └── src/main/...               # демо: card+3DS, СБП/SberPay
```

## 16. Открытые вопросы (уточнить с бэкендом)

1. **Имя заголовка секрета** (⚠️ ОСТАЁТСЯ ОТКРЫТЫМ): `X-Intent-Secret` (web-sdk) vs
   `secret` (CloudPayments) — что ждёт наш бэкенд на `PATCH api/intent/{id}` и нужен ли
   secret на `getIntent`. До уточнения берём `X-Intent-Secret` из `operations.ts`.

Решено (зафиксировано в дизайне, не открыто):
- **BrowserInfo** → как CloudPayments: `BrowserInfoBase64 = ""` (§10).
- **Путь альт-линка** → форма web-sdk `payWithFasterPayments` (§9, §11).
- **`getPublicKey`** → как web-sdk (PEM + keyVersion) — ок.
- **`userInfo.billing`** → вне зоны внимания на данном этапе.

## 17. Что берём из CloudPayments, а что — нет

| Берём как референс | НЕ копируем |
|---|---|
| Структуру intent-API (`CloudPaymentsIntentApiService`) — почти наш контракт | Криптограмму `Card.kt` (PKCS1 + строка + `CloudCard`) |
| 3DS через WebView (`IntentApiThreeDsDialogFragment`, jsoup-парсинг ACS) | RxJava2 (берём coroutines+Flow, Java через фасад) |
| Раскладку модулей (`:sdk` + `:app`), minSdk 24, Java 17, публикацию через **JitPack** | Dagger2 (делаем ручную сборку) |
| Поллинг статуса (worker) как идею для `waitForResult` | Gson (берём kotlinx.serialization) |
| Пустой `BrowserInfoBase64` в app-flow (§10) | Путь альт-линка CP (берём web-sdk-форму, §9); drop-in UI-слой (v1 вне объёма) |
