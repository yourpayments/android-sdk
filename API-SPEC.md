# YP SDK — Контракты API

Справочник публичных контрактов SDK приёма платежей (`ru.ypmn.sdk`): методы, типы данных,
события, сетевой контракт и формат криптограммы. Достаточно для реализации эквивалентного
SDK на другой платформе (Swift/iOS).

Базовый URL по умолчанию: `https://ypmn.ru`. Валюта в минорных единицах (копейки).

---

## Точка входа — `YP`

| Метод | Описание |
|---|---|
| `suspend createIntent(request: CreateIntentRequest, config: YpConfig): Intent` | Создаёт интент (`POST api/intent/`), возвращает хэндл. Секрет берётся из ответа. |
| `suspend getIntent(id: String, config: YpConfig): Intent` | Загружает существующий интент (`GET api/intent/{id}/`). |
| `wrapIntent(data: IntentResponse, config: YpConfig): Intent` | Оборачивает уже полученный ответ в хэндл **без** сетевого вызова. |

---

## `Intent` — хэндл интента

Иммутабельный фасад: read-only поля + операции + поток событий.

### Поля
| Поле | Тип | Описание |
|---|---|---|
| `id` | `String` | Идентификатор интента. |
| `status` | `IntentStatus` | Текущий статус. |
| `data` | `IntentResponse` | Полный снапшот интента (обновляется после `update`/смены статуса). |
| `events` | `Flow<IntentEvent>` | Горячий поток событий. |

### Методы
| Метод | Описание |
|---|---|
| `suspend update(changes: UpdateChanges)` | Частичное обновление (`receiptEmail`/`tokenize`) через JSON Patch. Конкурентные вызовы объединяются в один PATCH. |
| `suspend getStatus(): IntentStatus` | Запрашивает статус (`GET .../status/`) и синхронизирует его в сессии. |
| `suspend getStatusDetails(): IntentStatusResponse` | Полный ответ `/status/` (статус + транзакции) с синхронизацией сессии — для собственного поллинга/матчинга DECLINED. |
| `suspend pay(input: PayInput): PayResult` | Оплата картой или готовой криптограммой. Возвращает `Authorized` либо `ThreeDsRequired`. |
| `create3dsView(result: PayResult.ThreeDsRequired): ThreeDsView` | Фабрика 3DS-обёртки над WebView. Без сети. |
| `suspend getPaymentMethod(method: AltPayMethod, opts: AltRequestOpts = …): AltPayFlow` | Строит флоу альт-метода из встроенных `paymentMethods`. |
| `suspend getPaymentMethod(method: AltPayMethod.FasterPayments, …): AltPayFlow.FasterPaymentsFlow` | Перегрузка-сужение: СБП. |
| `suspend getPaymentMethod(method: AltPayMethod.SberPay, …): AltPayFlow.SberPayFlow` | Перегрузка-сужение: SberPay. |
| `suspend waitForResult(opts: WaitForResultOpts = …): WaitForResultStatus` | Поллинг до терминального статуса или DECLINED-транзакции с `opts.puid` (→ `Declined`). |

---

## Типы данных

### `YpConfig`
| Поле | Тип | По умолч. | Описание |
|---|---|---|---|
| `baseUrl` | `String` | — | Базовый URL API. |
| `headers` | `Map<String,String>` | `{}` | Заголовки, добавляемые к каждому запросу. |

### `CardData`
| Поле | Тип | Описание |
|---|---|---|
| `pan` | `String` | Номер карты (нормализуется до цифр). |
| `expMonth` | `String` | Месяц, 2 цифры (`"12"`). |
| `expYear` | `String` | Год, 2 цифры (`"25"`). |
| `cvv` | `String` | CVV. |

`toString()` маскирует все поля (PAN → последние 4 цифры).

### `UpdateChanges`
| Поле | Тип | Описание |
|---|---|---|
| `receiptEmail` | `String?` | E-mail чека. `null` — не менять. |
| `tokenize` | `Boolean?` | Токенизация карты. `null` — не менять. |
| `clearReceiptEmail` | `Boolean` | `true` → PATCH шлёт `"/receiptEmail": null` (очистка; побеждает `receiptEmail`). |

Нет ни одного изменения → ошибка `"update: no editable fields provided"`.

### `PayInput` (sealed)
| Вариант | Описание |
|---|---|
| `Card(card: CardData)` | SDK сам строит криптограмму. |
| `Cryptogram(cryptogram: String)` | Готовая криптограмма (base64-конверт). |

### `PayResult` (sealed)
| Вариант | Описание |
|---|---|
| `Authorized(data: IntentStatusResponse)` | Оплата авторизована. |
| `ThreeDsRequired(threeDsUrl: String)` | Требуется 3DS по URL. |

### `IntentStatus` (enum)
`RequiresPaymentData` · `RequiresPaymentMethod` · `Expired` · `Success`.
Терминальные: `Success`, `Expired` (`isTerminal`). Неизвестная строка → `RequiresPaymentData`.
(Спека v1.0.0: статусов `Pending`/`Failed` больше нет — неуспешная попытка возвращает
интент в `RequiresPayment*`; неуспех 3DS сигналится postMessage-кодом, не статусом.)
Сессия не откатывает терминальный статус: запоздалый `/status/` не перетирает Success/Expired.

### `WaitForResultStatus` (enum)
`RequiresPaymentData` · `RequiresPaymentMethod` · `Expired` · `Success` · `Declined`.
`Declined` — клиентский синтетический исход: найдена транзакция `DECLINED` с `puid`
текущей попытки (у интента терминального статуса неуспеха нет). Зеркало web-sdk
`WaitForResultStatus = IntentStatus | 'Declined'`.

### `AltPayMethod` (sealed, поле `type: String`)
`FasterPayments` (СБП) · `SberPay` · `AlfaPay` · `MirPay` · `TPay` · `BNPL`.
`fromType(type: String): AltPayMethod?` — маппинг строки бэкенда, неизвестный → `null`.

### `AltPayFlow` (sealed)
| Член | Тип | Описание |
|---|---|---|
| `method` | `AltPayMethod` | Метод. |
| `link` | `String?` | Базовая ссылка на оплату (`PaymentMethodDto.link`). |
| `getImage(): String` | — | URL QR (синхронно, `""` если нет). С `AltRequestOpts.puid` — `/alt/{id}/image/{method}/?puid=` (встроенный QR даёт транзакцию с `puid=null` и не матчится `waitForResult`). |
| `suspend getLink(opts: AltLinkOpts = …): String` | — | Свежий deeplink из сети (`GET alt/{id}/link/{method}/`). |

Подтипы:
- `SberPayFlow` : `+ suspend sendSms(phone: String)` — push/SMS в приложение SberPay.
- `FasterPaymentsFlow` : `+ banks: List<SbpBank>` — банки СБП (`schema` для `getLink`).
- `GenericAltFlow` — прочие методы, без спец-функций.

### Опции
| Тип | Поля | По умолч. |
|---|---|---|
| `AltRequestOpts` | `webview: Boolean`, `puid: String?` | `false`, `null` |
| `AltLinkOpts` | `webview: Boolean`, `puid: String?`, `schema: String?` | `false`, `null`, `null` |
| `WaitForResultOpts` | `intervalMs: Long?`, `timeoutMs: Long?`, `puid: String?` | `null`→`3000`, `null`→`600000`, `null` |

`puid` — client-generated UUID попытки (генерирует интегратор, напр. `UUID.randomUUID()`).
Один и тот же puid обязан уходить в `getPaymentMethod`/`getLink`/`getImage` **и** в
`waitForResult` — иначе DECLINED-транзакция попытки не сматчится. Отмена ожидания —
отмена корутины (эквивалент `AbortSignal` web-sdk).

---

## Контракты запросов/ответов (wire)

### `CreateIntentRequest` → тело `POST api/intent/`
| Поле | Тип | Обяз. | Описание |
|---|---|---|---|
| `merchantCode` | `String` | да | Публичный ID терминала. |
| `amount` | `Long` | да | Сумма в копейках (`100000` = 1000.00). |
| `currency` | `String` | да | Напр. `"RUB"`. |
| `messageScheme` | `String` | да | `"SMS"` \| `"DMS"`. |
| `culture` | `String?` | нет | Локаль. |
| `description` | `String?` | нет | Описание. |
| `merchantPaymentReference` | `String?` | нет | Внешний ID мерчанта. |
| `receiptEmail` | `String?` | нет | E-mail чека. |
| `accountId` | `String?` | нет | ID аккаунта покупателя. |
| `autoClose` | `Int?` | нет | Автозакрытие (сек). |
| `orderTimeout` | `Int?` | нет | TTL заказа (сек). |
| `retryPayment` | `Boolean?` | нет | Разрешить повторные попытки. |
| `tokenize` | `Boolean?` | нет | Токенизировать карту. |
| `scenario` | `String?` | нет | `"Widget"` \| `"WebSDK"` \| `"AndroidSDK"` \| `"IoSSDK"`; `null` → `YP.createIntent` подставляет `"AndroidSDK"`. |
| `skin` | `String?` | нет | `"Classic"` \| `"Modern"` (хостед-страницы). |
| `emailBehavior` | `String?` | нет | `"Required"` \| `"Hidden"` \| `"Optional"` (хостед-страницы). |
| `paymentUrl` / `successRedirectUrl` / `failRedirectUrl` | `String?` | нет | URL'ы хостед-страниц/редиректов. |
| `restrictedPaymentMethods` | `List<String>?` | нет | Ограничение методов. |
| `items` | `List<ProductItem>?` | нет | Позиции чека: `{ name, sku, unitPrice, quantity, additionalDetails?, vat?("0"\|"5"\|"7"\|"10"\|"22"), marketplace?{merchantCode} }`. |
| `receipts` | `JsonObject?` | нет | Произвольная структура чеков. |
| `userInfo` | `UserInfo?` | нет | `{ billing?: Billing }` — 12 опц. полей (firstName…zipCode). |
| `metaData` | `JsonObject?` | нет | Произвольные метаданные мерчанта. |

Java-эргономика: `CreateIntentRequest.builder(merchantCode, amount, currency, messageScheme)` → сеттеры → `build()`.

`null`-поля в JSON не отправляются.

### `IntentResponse` — ответ create/get/patch
| Поле | Тип | Описание |
|---|---|---|
| `id` | `String` | ID интента. |
| `status` | `String` | Статус (строка). |
| `secret` | `String` | Секрет из ответа бэкенда (зарезервирован; SDK его сейчас никуда не отправляет). |
| `createdAt` / `updatedAt` / `expiredAt` | `Long` | Таймстемпы. |
| `merchantCode` | `String` | Терминал. |
| `amount` | `Long` | Сумма. |
| `currency` | `String` | Валюта. |
| `messageScheme` | `String` | Схема. |
| `description` | `String?` | Описание. |
| `receiptEmail` | `String?` | E-mail. |
| `tokenize` | `Boolean?` | Токенизация. |
| `paymentMethods` | `List<PaymentMethodDto>?` | Доступные методы оплаты. |
| `transactions` | `List<Transaction>?` | Транзакции: `{ id: Long, status: "PENDING"\|"AUTHORIZED"\|"DECLINED", puid: String? }`. `DECLINED` отсутствует в openapi (спека отстаёт), подтверждён бэкендом — по нему `waitForResult(puid)` детектит неуспех попытки. |

Неизвестные поля игнорируются при парсинге.

### `PaymentMethodDto`
| Поле | Тип | Описание |
|---|---|---|
| `type` | `String` | `"FasterPayments"` \| `"SberPay"` \| `"Podeli"` \| `"TcsInstallment"` \| … |
| `group` | `String?` | Группа (`"Card"` \| `"FastPayment"` \| `"Installments"`). |
| `link` | `String?` | Ссылка на оплату. |
| `image` | `String?` | URL QR. |
| `networks` | `List<String>?` | Card: `"Visa"` \| `"MasterCard"` \| `"Mir"`. |
| `capabilities` | `List<String>?` | Card: `"domestic"` / `"international"`. |
| `primary` | `Boolean?` | Признак основного метода. |
| `deepLinks` | `List<String>?` | SberPay: схемы нативных приложений. |
| `banks` | `List<SbpBank>?` | Банки (для СБП). |
| `data` | `PaymentMethodDataDto?` | Рассрочки: Podeli `options{minLimit,maxLimit}: Double`; TcsInstallment `periods: List<String>`, `shopId`, `showCaseId`. |

### `SbpBank`
| Поле | JSON-ключ | Тип | Описание |
|---|---|---|---|
| `bankName` | `bankName` | `String` | Название. |
| `logoUrl` | `logoURL` / `logoUrl` | `String?` | Логотип. |
| `schema` | `schema` | `String` | Схема deeplink (→ `?schema=` в `getLink`). |
| `webClientUrl` | `webClientUrl` | `String?` | Web-клиент. |
| `isWebClientActive` | `isWebClientActive` | `Boolean?` | Флаг: бэкенд шлёт булево, спека обещает строку — принимаем оба. |
| `packageName` | `packageName` / `package_name` | `String?` | Пакет приложения. |

### Статус
- `IntentStatusData` = `{ status: String }`.
- `IntentStatusResponse` = `{ intent: IntentStatusData, transactions: List<Transaction> }`.
Возвращается из `GET .../status/` и из успешного `pay` (без 3DS).

---

## События — `IntentEvent` (sealed)

| Событие | Полезная нагрузка | Когда |
|---|---|---|
| `Update` | `intent: Intent` | Поля обновлены после `update()`. |
| `StatusChange` | `status: IntentStatus` | Изменился статус (getStatus / pay / 3DS). |
| `Success` | `result: PayResult` | Успех: pay→Authorized, waitForResult→Success, 3DS→SUCCESS. Транзакции — из последнего ответа `/status/` (в 3DS-postMessage-пути — из кэша сессии). |
| `Error` | `error: Throwable` | Ошибка pay; waitForResult не-Success (`"payment declined (transaction {id})"` при Declined); 3DS FAILURE. |

Поток горячий — события до подписки не доставляются (кроме буфера).

---

## 3DS — `ThreeDsView`

```
create3dsView(result) → ThreeDsView
    mount(container): ThreeDsView   — грузит threeDsUrl в WebView, запускает детект завершения
    unmount()                        — снять WebView (не уничтожать)
    destroy()                        — уничтожить
    webView: WebView?
    results: Flow<ThreeDsResult>     — один результат (последний доступен поздним подписчикам)
```

`ThreeDsResult` = `{ status: SUCCESS|FAILURE, code: String?, intentStatus: IntentStatus? }`.

**Контракт завершения** (два сигнала):
1. `postMessage` от return-страницы: `{"code":"0"}` → SUCCESS; иной `code` → FAILURE; нет/мусор → игнор.
   Мост принимает сообщения **только от origin `baseUrl`** (web-sdk принимает с любого;
   здесь ужесточено — сторонняя ACS-страница не может подделать успех). Непарсибельный
   baseUrl → деградация до `"*"`.
2. Fallback: поллинг `/status/` каждые 3000 мс до терминального статуса — ловит и
   завершение с чужого origin; Success-событие несёт транзакции последнего ответа.

---

## Ошибки — `YpException` (иерархия)

`open class YpException(message, cause?)` — база всех ошибок SDK. Подтипы:
| Тип | Поля | Когда |
|---|---|---|
| `YpApiException` | `status: Int`, `body: String?` | HTTP 4xx/5xx; message — из тела `{"message":…}`, иначе `"HTTP <code>"` |
| `YpNetworkException` | — | I/O-ошибка, message `"network error"` |
| `YpException` (база) | — | Ошибка парсинга (`"invalid response"`), таймаут поллинга (`"pollUntil: timeout"`), доменные ошибки |

Отмена операции пробрасывается как есть (не оборачивается).

---

## HTTP-контракт

Пути относительно `baseUrl` с завершающим `/` (обязателен). Content-Type строго `application/json`
(без `; charset=utf-8`).

| Операция | Метод | Путь | Заголовки | Тело | Ответ |
|---|---|---|---|---|---|
| create | `POST` | `api/intent/` | config.headers | `CreateIntentRequest` | `IntentResponse` |
| get | `GET` | `api/intent/{id}/` | config.headers | — | `IntentResponse` |
| update | `PATCH` | `api/intent/{id}/` | config.headers | `[PatchOp]` (JSON Patch) | `IntentResponse` |
| status | `GET` | `api/intent/{id}/status/` | config.headers | — | `IntentStatusResponse` |
| public key | `GET` | `api/intent/pay/key/` | config.headers | — | `{ publicKey: String, version?: Int }` |
| pay | `POST` | `api/intent/{id}/pay/` | config.headers | `{ paymentMethod:"Card", cryptogram }` | `IntentStatusResponse` \| `{ threeDsUrl }` |
| alt link | `GET` | `alt/{id}/link/{method}/` | config.headers | query: `webview`,`puid`,`schema` | **plain text** (deeplink) |
| send SMS | `POST` | `api/intent/{id}/send-sms/` | config.headers | `{ phone: String }` | — |

- `PatchOp` = `{ op:"replace", path:"/receiptEmail"|"/tokenize", value }`.
- `pay`: uuid интента в path; поле `paymentMethod:"Card"` обязательно. Ответ парсится как сырой JSON, ветвление по `threeDsUrl`.
- `public key`: `version` (если пришёл) уходит в `KeyVersion` криптограммы (иначе 1).
- `alt link`: `webview` → строка `"true"` либо параметр опущен; параметры добавляются только если заданы.
  Пример: `/alt/i1/link/FasterPayments/?schema=bank100000`. Ответ — plain-text строка, не JSON.
- Секрет-заголовок не отправляется вовсе: контракт с бэкендом не зафиксирован
  (`X-Intent-Secret` vs `secret`) — убран, пока бэкенд его не начнёт требовать.

---

## Криптограмма карты

Данные карты шифруются на устройстве до отправки; наружу уходит только конверт.

**Параметры шифрования (обязаны совпасть):**
```
RSA/ECB/OAEPWithSHA-256AndMGF1Padding
OAEP: digest SHA-256, MGF1 SHA-256, label пустой (PSpecified.DEFAULT)
Ключ: X.509/SPKI DER из PEM (снять BEGIN/END и whitespace, base64-decode). PEM берётся из GET api/intent/pay/key/.
```

**Шаги сборки** `PayInput.Card`:
1. Нормализовать `pan`/`cvv`/`expMonth`/`expYear` — только цифры. PAN < 13 цифр → ошибка.
2. Зашифровать payload: `{"PAN":"<цифры>","cvv":"<цифры>"}` → base64 шифртекста = `Value`.
3. Собрать конверт (порядок ключей важен):
   ```json
   {
     "Type": "Card",
     "BrowserInfoBase64": "<base64(JSON BrowserInfo)>",
     "CardInfo": {
       "FirstSixDigits": "<pan[0:6]>",
       "LastFourDigits": "<pan[-4:]>",
       "ExpDateYear": "<yy>",
       "ExpDateMonth": "<mm>"
     },
     "KeyVersion": 1,
     "Value": "<base64 OAEP-шифртекста PAN+CVV>"
   }
   ```
4. Криптограмма = `base64(utf8(JSON(конверт)))` → это `cryptogram` в теле `pay`.

**BrowserInfo** (нельзя слать пустым — бэкенд отвергает), base64 от JSON:
```json
{
  "AcceptHeader": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "ColorDepth": 24,
  "IpAddress": "0.0.0.0",
  "Language": "<BCP-47, напр. ru-RU>",
  "ScreenHeight": <px|0>,
  "ScreenWidth": <px|0>,
  "TimeZone": <смещение TZ в минутах>,
  "UserAgent": "<строка UA устройства>",
  "JavaEnabled": false,
  "JavaScriptEnabled": true
}
```

**Проверочный вектор:** `pan="4652 0354 4066 7037", mm="12", yy="25", cvv="123"` →
`FirstSixDigits="465203"`, `LastFourDigits="7037"`, `Value` расшифровывается в
`{"PAN":"4652035440667037","cvv":"123"}`.
