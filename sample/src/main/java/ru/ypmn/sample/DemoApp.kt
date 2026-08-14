package ru.ypmn.sample

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

/**
 * Coil по умолчанию не декодирует SVG. QR-код СБП и часть логотипов банков
 * сервер отдаёт как image/svg+xml, поэтому регистрируем SvgDecoder в общем
 * ImageLoader — тогда все AsyncImage (QR + логотипы) рисуются корректно.
 */
class DemoApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
}
