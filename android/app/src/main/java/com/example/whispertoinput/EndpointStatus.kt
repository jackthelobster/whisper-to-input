package com.example.whispertoinput

import android.content.Context

internal fun EndpointSelection.statusText(context: Context): String {
    if (selected != null) return context.getString(
        if (primary == ProbeResult.Ready) R.string.keyboard_ready else R.string.keyboard_ready_fallback
    )
    fun describe(result: ProbeResult): String = when (result) {
        ProbeResult.Ready -> context.getString(R.string.keyboard_ready)
        ProbeResult.Timeout -> context.getString(R.string.endpoint_probe_timeout)
        ProbeResult.Unreachable -> context.getString(R.string.endpoint_unreachable)
        is ProbeResult.HttpError -> context.getString(R.string.endpoint_http_error, result.code)
        is ProbeResult.ModelMissing -> context.getString(R.string.endpoint_model_missing, result.model)
        ProbeResult.InvalidResponse -> context.getString(R.string.endpoint_invalid_response)
        ProbeResult.InvalidEndpoint -> context.getString(R.string.invalid_endpoint)
    }
    return if (fallback == null || fallback == primary) describe(primary) else context.getString(
        R.string.endpoint_both_failed, describe(primary), describe(fallback)
    )
}
