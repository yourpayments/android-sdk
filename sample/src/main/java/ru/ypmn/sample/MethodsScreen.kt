package ru.ypmn.sample

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent as AndroidIntent
import android.net.Uri
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import ru.ypmn.sdk.PaymentMethodDto
import ru.ypmn.sdk.ThreeDsView

/**
 * Способы оплаты, которые демо не показывает, даже если бэкенд прислал их
 * в интенте. В самом SDK методы остаются (см. `AltPayMethod`).
 */
private val HIDDEN_METHOD_TYPES = setOf("BNPL")

@Composable
fun MethodsScreen(vm: DemoViewModel) {
    val intent = vm.intent ?: return
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepHeader(step = "2", title = "Способы оплаты", subtitle = "Динамически из intent.data")

        // Шапка интента: id + статус + сброс
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Intent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${intent.id.take(18)}…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    StatusPill(intent.status)
                }
                OutlinedButton(onClick = { vm.resetForNewIntent() }) {
                    Text("Новый")
                }
            }
        }

        // Живая лента событий из intent.events
        if (vm.events.isNotEmpty()) {
            EventsCard(vm.events)
        }

        CodeSpoiler(
            title = "Отрисовка способов оплаты",
            code = pickCode(vm.lang, SNIPPET_RENDER_KT, SNIPPET_RENDER_JAVA),
        )

        val methods = intent.data.paymentMethods?.filterNot { it.type in HIDDEN_METHOD_TYPES }
        if (methods.isNullOrEmpty()) {
            Text("Нет доступных способов оплаты", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            methods.forEach { pm -> MethodCard(vm, pm) }
        }

        vm.lastLink?.let { link ->
            LinkCard(link = link, onOpen = { openLink(context, link) { msg -> vm.lastResult = msg } })
        }

        if (vm.lastResult.isNotEmpty()) {
            ResultBanner(vm.lastResult)
        }

        if (vm.busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Ссылка альт-оплаты с копированием. Диплинк банка открывается только там, где
 * стоит приложение банка: на эмуляторе и на «чистом» телефоне ACTION_VIEW
 * гарантированно упадёт, поэтому саму ссылку всегда показываем текстом.
 */
@Composable
fun LinkCard(link: String, onOpen: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(link) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Ссылка оплаты",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            SelectionContainer {
                Text(
                    link,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(link))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (copied) "Скопировано" else "Копировать")
                }
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text("Открыть")
                }
            }
        }
    }
}

/**
 * ACTION_VIEW по ссылке. Без обработчика схемы Android бросает
 * ActivityNotFoundException — сообщаем об этом человеческим текстом вместо
 * падения: ссылку рядом можно скопировать и открыть вручную.
 */
private fun openLink(context: Context, link: String, onError: (String) -> Unit) {
    val uri = Uri.parse(link)
    try {
        context.startActivity(AndroidIntent(AndroidIntent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        val scheme = uri.scheme
        onError(
            if (scheme == null) {
                "Не удалось открыть: ссылка без схемы — $link"
            } else {
                "Нет приложения для схемы $scheme:// — скопируйте ссылку и откройте вручную"
            },
        )
    }
}

@Composable
fun EventsCard(events: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "События · intent.events",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            events.forEach { line ->
                Text(
                    "› $line",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
fun MethodCard(vm: DemoViewModel, pm: PaymentMethodDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodAvatar(pm.type)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(methodLabel(pm.type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    pm.group?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when (pm.type) {
                "Card" -> {
                    CardMethodContent(vm)
                    CodeSpoiler("Оплата картой", pickCode(vm.lang, SNIPPET_CARD_KT, SNIPPET_CARD_JAVA))
                }
                "FasterPayments" -> {
                    FasterPaymentsContent(vm)
                    CodeSpoiler("СБП", pickCode(vm.lang, SNIPPET_SBP_KT, SNIPPET_SBP_JAVA))
                }
                "SberPay" -> {
                    SberPayContent(vm)
                    CodeSpoiler("SberPay (Push / СМС)", pickCode(vm.lang, SNIPPET_SBERPAY_KT, SNIPPET_SBERPAY_JAVA))
                }
                else -> {
                    GenericAltContent(vm, pm.type)
                    CodeSpoiler("Получить ссылку", pickCode(vm.lang, SNIPPET_GENERIC_KT, SNIPPET_GENERIC_JAVA))
                }
            }
        }
    }
}

@Composable
fun CardMethodContent(vm: DemoViewModel) {
    var pan by remember { mutableStateOf("4111111111111111") }
    var expMonth by remember { mutableStateOf("12") }
    var expYear by remember { mutableStateOf("30") }
    var cvv by remember { mutableStateOf("123") }

    OutlinedTextField(
        value = pan,
        onValueChange = { pan = it },
        label = { Text("PAN") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = expMonth,
            onValueChange = { expMonth = it },
            label = { Text("MM") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedTextField(
            value = expYear,
            onValueChange = { expYear = it },
            label = { Text("YY") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedTextField(
            value = cvv,
            onValueChange = { cvv = it },
            label = { Text("CVV") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
    }
    Button(
        onClick = { vm.payCard(pan, expMonth, expYear, cvv) },
        enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Оплатить", fontWeight = FontWeight.SemiBold)
    }

    // 3DS-секция
    val threeDsResult = vm.threeDsRequired
    if (threeDsResult != null) {
        Text("3DS верификация", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        val sdkViewRef = remember(threeDsResult) { mutableStateOf<ThreeDsView?>(null) }

        DisposableEffect(threeDsResult) {
            onDispose { sdkViewRef.value?.destroy() }
        }

        LaunchedEffect(sdkViewRef.value) {
            sdkViewRef.value?.results?.collect { r ->
                vm.lastResult = "3DS: ${r.status}" + (r.intentStatus?.let { " (intent: $it)" } ?: "")
                vm.threeDsRequired = null
            }
        }

        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).also { fl ->
                    fl.post {
                        sdkViewRef.value = vm.intent!!.create3dsView(threeDsResult).mount(fl)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .background(Color.White, RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
fun FasterPaymentsContent(vm: DemoViewModel) {
    val context = LocalContext.current

    Button(
        onClick = { vm.fetchSbp() },
        enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Получить СБП", fontWeight = FontWeight.SemiBold)
    }

    val flow = vm.fasterPaymentsFlow
    if (flow != null) {
        // QR на белой подложке (сервер отдаёт SVG — декодирует SvgDecoder)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Отсканируйте QR-код",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .size(224.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(12.dp),
            ) {
                AsyncImage(
                    model = flow.getImage(),
                    contentDescription = "QR код СБП",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text(
            "Банки (${flow.banks.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        flow.banks.take(12).forEach { bank ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AsyncImage(
                    model = bank.logoUrl,
                    contentDescription = bank.bankName,
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.White, RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = bank.bankName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    enabled = !vm.busy,
                    onClick = {
                        vm.fetchBankLink(bank.schema) { link ->
                            openLink(context, link) { msg -> vm.lastResult = msg }
                        }
                    },
                ) {
                    Text("Открыть")
                }
            }
        }
        if (flow.banks.size > 12) {
            Text(
                "…и ещё ${flow.banks.size - 12} банков",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SberPayContent(vm: DemoViewModel) {
    var phone by remember { mutableStateOf("79990000000") }

    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Номер телефона") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("79991234567") },
    )
    Button(
        onClick = { vm.sendSms(phone) },
        enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Отправить SMS", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GenericAltContent(vm: DemoViewModel, type: String) {
    Button(
        onClick = { vm.fetchGenericAlt(type) },
        enabled = !vm.busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("Получить ссылку", fontWeight = FontWeight.SemiBold)
    }
}
