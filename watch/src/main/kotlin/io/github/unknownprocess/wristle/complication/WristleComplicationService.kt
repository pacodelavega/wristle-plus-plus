package io.github.unknownprocess.wristle.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.github.unknownprocess.wristle.R
import io.github.unknownprocess.wristle.ui.DictateActivity

// @keep(pacodelavega): Modern AndroidX Wear Watchface Complication Data Source Service

class WristleComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return createComplicationData(type)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        return createComplicationData(request.complicationType)
    }

    private fun createComplicationData(type: ComplicationType): ComplicationData? {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DictateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val micIcon = Icon.createWithResource(this, R.drawable.ic_complication)
        val monochromaticIcon = MonochromaticImage.Builder(micIcon).build()

        return when (type) {
            ComplicationType.SMALL_IMAGE -> {
                val smallImage = SmallImage.Builder(
                    micIcon,
                    SmallImageType.ICON
                ).build()

                SmallImageComplicationData.Builder(
                    smallImage = smallImage,
                    contentDescription = PlainComplicationText.Builder("Wristle Dictate").build()
                )
                .setTapAction(tapIntent)
                .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("Dictate").build(),
                    contentDescription = PlainComplicationText.Builder("Wristle Dictate").build()
                )
                .setMonochromaticImage(monochromaticIcon)
                .setTapAction(tapIntent)
                .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("Wristle Dictate").build(),
                    contentDescription = PlainComplicationText.Builder("Wristle Dictate").build()
                )
                .setMonochromaticImage(monochromaticIcon)
                .setTapAction(tapIntent)
                .build()
            }

            else -> null
        }
    }
}
