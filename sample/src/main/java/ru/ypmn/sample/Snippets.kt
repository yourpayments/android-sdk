package ru.ypmn.sample

val EXAMPLE_JSON = """
{
  "merchantCode": "widget",
  "amount": 100000,
  "currency": "RUB",
  "messageScheme": "SMS",
  "description": "Demo order #1"
}
""".trimIndent()

// ─────────────────────────── Создание интента ───────────────────────────

val SNIPPET_CREATE_KT = """
val config = YpConfig(baseUrl = "https://sandbox.ypmn.ru")

val intent = YP.createIntent(
    CreateIntentRequest(
        merchantCode = "widget",
        amount = 100000,            // в копейках
        currency = "RUB",
        messageScheme = "SMS",
    ),
    config,
)

// Новое API событий: реагируем на жизненный цикл интента
intent.events
    .onEach { event ->
        when (event) {
            is IntentEvent.StatusChange -> Log.d("YP", "статус → ${'$'}{event.status}")
            is IntentEvent.Success      -> Log.d("YP", "оплачено ✓")
            is IntentEvent.Error        -> Log.e("YP", "ошибка", event.error)
            is IntentEvent.Update       -> Log.d("YP", "интент обновлён")
        }
    }
    .launchIn(scope)                // scope: например viewModelScope
""".trimIndent()

val SNIPPET_CREATE_JAVA = """
YpConfig config = new YpConfig("https://sandbox.ypmn.ru", Collections.emptyMap());

CreateIntentRequest req = new CreateIntentRequest(
    "widget",      // merchantCode
    100000L,       // amount, в копейках
    "RUB",         // currency
    "SMS",         // messageScheme
    null, null, null, null, null, null, null);   // опциональные поля

YPJava.createIntent(req, config, new YpCallback<Intent>() {
    @Override public void onSuccess(Intent intent) {
        // Новое API событий (Java-фасад): слушатель + Cancellable
        Cancellable sub = intent.addEventListener(event -> {
            if (event instanceof IntentEvent.StatusChange s) {
                Log.d("YP", "статус → " + s.getStatus());
            } else if (event instanceof IntentEvent.Success) {
                Log.d("YP", "оплачено ✓");
            } else if (event instanceof IntentEvent.Error e) {
                Log.e("YP", "ошибка", e.getError());
            }
        });
        // sub.cancel(); — когда слушатель больше не нужен
    }
    @Override public void onError(Throwable e) {
        Log.e("YP", "createIntent failed", e);
    }
});
""".trimIndent()

// ───────────────────────── Отрисовка способов оплаты ─────────────────────

val SNIPPET_RENDER_KT = """
// intent.data.paymentMethods: List<PaymentMethodDto>?
val methods = intent.data.paymentMethods.orEmpty()

methods.forEach { pm ->
    when (pm.type) {
        "Card" -> {
            // pm.group == "Card" — показать форму карты (PAN / MM / YY / CVV)
        }
        "FasterPayments" -> {
            val qrUrl = pm.image                 // URL картинки QR (SVG)
            pm.banks?.forEach { bank ->
                // bank.bankName, bank.logoUrl, bank.schema, bank.packageName
            }
        }
        "SberPay", "AlfaPay", "MirPay", "TPay" -> {
            val link = pm.link                     // ссылка на оплату (web)
            val appSchemes = pm.deepLinks          // SberPay: схемы нативных приложений
        }
        else -> {
            // прочие альт-методы
        }
    }
}

// Метод выбран в рантайме (нет литерала) → сужаем флоу через when, без каста:
val method = AltPayMethod.fromType(selectedType) ?: return
when (val flow = intent.getPaymentMethod(method)) {
    is AltPayFlow.FasterPaymentsFlow -> flow.banks
    is AltPayFlow.SberPayFlow        -> flow.sendSms("79990000000")
    else                             -> flow.getLink()
}
""".trimIndent()

val SNIPPET_RENDER_JAVA = """
// intent.getData().getPaymentMethods(): List<PaymentMethodDto>
List<PaymentMethodDto> methods = intent.getData().getPaymentMethods();
if (methods != null) for (PaymentMethodDto pm : methods) {
    switch (pm.getType()) {
        case "Card":
            // форма карты (PAN / MM / YY / CVV)
            break;
        case "FasterPayments":
            String qrUrl = pm.getImage();        // URL картинки QR (SVG)
            if (pm.getBanks() != null) for (SbpBank bank : pm.getBanks()) {
                // bank.getBankName(), bank.getLogoUrl(), bank.getSchema()
            }
            break;
        case "SberPay": case "AlfaPay": case "MirPay": case "TPay":
            String link = pm.getLink();                    // ссылка на оплату (web)
            List<String> appSchemes = pm.getDeepLinks();   // SberPay: схемы приложений
            break;
        default:
            // прочие альт-методы
    }
}

// Метод выбран в рантайме → instanceof (аналог Kotlin when/is), без каста:
AltPayMethod method = AltPayMethod.fromType(selectedType);
YPJavaKt.getPaymentMethodAsync(intent, method, new YpCallback<AltPayFlow>() {
    @Override public void onSuccess(AltPayFlow flow) {
        if (flow instanceof AltPayFlow.FasterPaymentsFlow sbp) {
            sbp.getBanks();
        } else if (flow instanceof AltPayFlow.SberPayFlow sber) {
            YPJavaKt.sendSmsAsync(sber, "79990000000", smsCb);
        }
    }
    @Override public void onError(Throwable e) { Log.e("YP", "alt", e); }
});
""".trimIndent()

// ─────────────────────────── Оплата картой (+ 3DS) ───────────────────────

val SNIPPET_CARD_KT = """
// Исход также придёт в intent.events (Success / Error) — см. создание интента.
val card = CardData(pan = "4111111111111111", expMonth = "12", expYear = "30", cvv = "123")

when (val res = intent.pay(PayInput.Card(card))) {
    is PayResult.Authorized ->
        Log.d("YP", "Authorized: ${'$'}{res.data.intent.status}")

    is PayResult.ThreeDsRequired -> {
        // container: ViewGroup, куда монтируется 3DS WebView
        val view = intent.create3dsView(res).mount(container)
        view.results
            .onEach { r -> Log.d("YP", "3DS: ${'$'}{r.status}, intent=${'$'}{r.intentStatus}") }
            .launchIn(scope)
    }
}
""".trimIndent()

val SNIPPET_CARD_JAVA = """
// Исход также придёт через intent.addEventListener (Success / Error).
CardData card = new CardData("4111111111111111", "12", "30", "123");

YPJavaKt.payAsync(intent, new PayInput.Card(card), new YpCallback<PayResult>() {
    @Override public void onSuccess(PayResult res) {
        if (res instanceof PayResult.ThreeDsRequired) {
            // container: ViewGroup
            ThreeDsView view = intent
                .create3dsView((PayResult.ThreeDsRequired) res)
                .mount(container);
            // view.getResults(): Flow<ThreeDsResult> — собрать через корутины
        } else if (res instanceof PayResult.Authorized a) {
            Log.d("YP", "Authorized: " + a.getData().getIntent().getStatus());
        }
    }
    @Override public void onError(Throwable e) {
        Log.e("YP", "pay failed", e);
    }
});
""".trimIndent()

// ─────────────────────────────── СБП (FasterPayments) ────────────────────

val SNIPPET_SBP_KT = """
// перегрузка сужает результат до FasterPaymentsFlow — каст не нужен
val flow = intent.getPaymentMethod(AltPayMethod.FasterPayments)

val qrUrl = flow.getImage()                 // URL SVG-картинки QR
flow.banks.forEach { bank ->
    // bank.bankName, bank.logoUrl, bank.schema
}

// диплинк под выбранный банк:
val deeplink = flow.getLink(AltLinkOpts(schema = flow.banks.first().schema))
// приложения банка на устройстве может не быть — ACTION_VIEW тогда бросает
// ActivityNotFoundException; покажите ссылку с копированием как запасной путь
try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deeplink)))
} catch (e: ActivityNotFoundException) {
    showCopyableLink(deeplink)
}

// дождаться оплаты (поллинг до терминального статуса):
val status = intent.waitForResult()         // Success / Declined / Expired
""".trimIndent()

val SNIPPET_SBP_JAVA = """
// перегрузка фасада сужает колбэк до FasterPaymentsFlow (data object → .INSTANCE из Java)
YPJavaKt.getPaymentMethodAsync(intent, AltPayMethod.FasterPayments.INSTANCE,
    new YpCallback<AltPayFlow.FasterPaymentsFlow>() {
        @Override public void onSuccess(AltPayFlow.FasterPaymentsFlow sbp) {
            String qrUrl = sbp.getImage();               // URL SVG-картинки QR
            for (SbpBank bank : sbp.getBanks()) {
                // bank.getBankName(), bank.getLogoUrl(), bank.getSchema()
            }
            AltLinkOpts opts = new AltLinkOpts(false, null, sbp.getBanks().get(0).getSchema());
            YPJavaKt.getLinkAsync(sbp, opts, new YpCallback<String>() {
                @Override public void onSuccess(String link) {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                    } catch (ActivityNotFoundException e) {
                        showCopyableLink(link);   // приложения для схемы нет
                    }
                }
                @Override public void onError(Throwable e) { Log.e("YP", "getLink", e); }
            });
        }
        @Override public void onError(Throwable e) { Log.e("YP", "СБП", e); }
    });
""".trimIndent()

// ─────────────────────────────────── SberPay ─────────────────────────────

val SNIPPET_SBERPAY_KT = """
// перегрузка сужает результат до SberPayFlow — каст не нужен
val sber = intent.getPaymentMethod(AltPayMethod.SberPay)

sber.sendSms(phone = "79990000000")   // Push / СМС в приложение SberPay
val status = intent.waitForResult()   // ждём подтверждения оплаты (Success/Declined/Expired)
""".trimIndent()

val SNIPPET_SBERPAY_JAVA = """
YPJavaKt.getPaymentMethodAsync(intent, AltPayMethod.SberPay.INSTANCE,
    new YpCallback<AltPayFlow.SberPayFlow>() {
        @Override public void onSuccess(AltPayFlow.SberPayFlow sber) {
            YPJavaKt.sendSmsAsync(sber, "79990000000", new YpCallback<Unit>() {
                @Override public void onSuccess(Unit u) { Log.d("YP", "SMS отправлен"); }
                @Override public void onError(Throwable e) { Log.e("YP", "sendSms", e); }
            });
        }
        @Override public void onError(Throwable e) { Log.e("YP", "SberPay", e); }
    });
""".trimIndent()

// ───────────────────────────── Прочие альт-методы ────────────────────────

val SNIPPET_GENERIC_KT = """
val flow = intent.getPaymentMethod(method)       // method: AltPayMethod
val link = flow.getLink()                         // ссылка / диплинк метода
try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
} catch (e: ActivityNotFoundException) {
    showCopyableLink(link)                        // схему никто не обслуживает
}
""".trimIndent()

val SNIPPET_GENERIC_JAVA = """
YPJavaKt.getPaymentMethodAsync(intent, method, new YpCallback<AltPayFlow>() {
    @Override public void onSuccess(AltPayFlow flow) {
        YPJavaKt.getLinkAsync(flow, new AltLinkOpts(false, null, null),
            new YpCallback<String>() {
                @Override public void onSuccess(String link) {
                    try {
                        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
                    } catch (ActivityNotFoundException e) {
                        showCopyableLink(link);   // приложения для схемы нет
                    }
                }
                @Override public void onError(Throwable e) { Log.e("YP", "getLink", e); }
            });
    }
    @Override public void onError(Throwable e) { Log.e("YP", "alt", e); }
});
""".trimIndent()
