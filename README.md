# YP Android SDK

Headless Android SDK для приёма платежей (нативный Kotlin-аналог `@yp/web-sdk`):
создать интент → оплатить картой (+3DS) или альт-методами (СБП/SberPay). UI строит мерчант.

## Требования
- Android `minSdk 24`, Kotlin, Java 17.

## Подключение (JitPack)
`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```
`build.gradle.kts` модуля:
```kotlin
implementation("com.github.onemantooo.android-sdk:sdk:<tag>")
```

## Оплата картой (+3DS)
```kotlin
val intent = YP.createIntent(
    CreateIntentRequest("your_terminal", 100000, "RUB", "SMS"),
    YpConfig(baseUrl = "https://ypmn.ru"),
)
when (val res = intent.pay(PayInput.Card(CardData(pan, mm, yy, cvv)))) {
    is PayResult.Authorized -> { /* успех */ }
    is PayResult.ThreeDsRequired -> {
        intent.create3dsView(res).mount(container)   // container: ViewGroup
        // подпишитесь на intent.create3dsView(...).results: Flow<ThreeDsResult>
    }
}
```

## Альт-методы (СБП / SberPay)
```kotlin
// puid — client-generated UUID попытки. Отклонённая попытка НЕ терминальна для интента:
// её видно только по DECLINED-транзакции с этим puid. Без puid отказ не детектится.
val puid = java.util.UUID.randomUUID().toString()
val flow = intent.getPaymentMethod(AltPayMethod.FasterPayments, AltRequestOpts(puid = puid))
val qrUrl = flow.getImage()                                       // с puid — /alt/-URL; без — встроенный QR
val deeplink = flow.getLink(AltLinkOpts(schema = flow.banks.first().schema))
when (intent.waitForResult(WaitForResultOpts(puid = puid))) {
    WaitForResultStatus.Success  -> { /* оплачено */ }
    WaitForResultStatus.Declined -> { /* попытка отклонена — новый puid и повтор */ }
    WaitForResultStatus.Expired  -> { /* интент истёк */ }
    else -> { /* RequiresPayment* — только при нештатном выходе из поллинга */ }
}
```
Отмена ожидания — отмена корутины (эквивалент `AbortSignal` web-sdk): уходя с экрана
ожидания, отменяйте Job/scope, из которого вызван `waitForResult`, иначе поллинг
доживёт до 10-минутного таймаута.

## События
```kotlin
intent.events.collect { event ->
    when (event) {
        is IntentEvent.StatusChange -> { /* event.status */ }
        is IntentEvent.Success -> { /* event.result */ }
        is IntentEvent.Error -> { /* event.error */ }
        is IntentEvent.Update -> { /* event.intent */ }
    }
}
```

## Ошибки
Все ошибки SDK — `YpException`; подтипы для программной обработки:
```kotlin
try { intent.pay(...) } catch (e: YpApiException) {
    // e.status (HTTP-код), e.body (сырое тело), e.message (message бэкенда)
} catch (e: YpNetworkException) { /* сеть недоступна — можно повторить */ }
```

## Java
```java
CreateIntentRequest req = CreateIntentRequest.builder("terminal", 100000L, "RUB", "SMS")
    .description("Заказ №1").build();
YPJava.createIntent(req, config, new YpCallback<Intent>() {
    public void onSuccess(Intent intent) { /* ... */ }
    public void onError(Throwable e) { /* ... */ }
});
// YPJava.getIntent(id, config, cb); YPJava.createIntentFuture(...) → CompletableFuture
// intent.addEventListener(event -> { ... });
```
**Внимание:** колбэки и события приходят на фоновом потоке (не main). Для обновления
UI переключайтесь на main самостоятельно (`runOnUiThread` / `Handler(Looper.getMainLooper())`).

## Сборка из исходников
```bash
./gradlew :sdk:assembleRelease   # библиотека
./gradlew :sdk:testReleaseUnitTest
./gradlew :sample:assembleDebug  # демо-приложение
```

## PCI
Данные карты шифруются на устройстве (RSA-OAEP SHA-256) перед отправкой; PAN/CVV уходят только в криптограмме.
