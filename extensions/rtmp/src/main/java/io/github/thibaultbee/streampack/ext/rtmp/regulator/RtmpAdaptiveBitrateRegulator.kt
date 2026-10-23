/*
 * Copyright (C) 2026 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.ext.rtmp.regulator

import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.regulator.BitrateRegulator
import io.github.thibaultbee.streampack.core.regulator.IBitrateRegulator
import io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.RtmpEndpointMetrics
import kotlin.math.max
import kotlin.math.min

/**
 * A bitrate regulator for [io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints.RtmpEndpoint],
 * driven by [RtmpEndpointMetrics] (write latency + recent write failures —
 * see that class for why a plain RTMP/TCP socket has no richer stats
 * available, unlike SRT's native stats).
 *
 * Reacts quickly (a large cut) to any recent write failures/timeouts,
 * more gently (a smaller cut) to write latency rising above normal, and
 * slowly ramps back up toward the configured ceiling once both signals
 * are healthy again. Meant to be attached only to a streaming output —
 * never a local-recording one, which should stay at a fixed quality.
 *
 * @param bitrateRegulatorConfig bitrate regulation configuration (min/max bounds)
 * @param onVideoTargetBitrateChange call when you have to change video bitrate
 * @param onAudioTargetBitrateChange not used in this implementation
 */
open class RtmpAdaptiveBitrateRegulator(
    bitrateRegulatorConfig: BitrateRegulatorConfig,
    onVideoTargetBitrateChange: ((Int) -> Unit),
    onAudioTargetBitrateChange: ((Int) -> Unit)
) : BitrateRegulator(
    bitrateRegulatorConfig,
    onVideoTargetBitrateChange,
    onAudioTargetBitrateChange
) {
    override fun update(stats: Any, currentVideoBitrate: Int, currentAudioBitrate: Int) {
        val metrics = stats as? RtmpEndpointMetrics ?: return
        val lower = bitrateRegulatorConfig.videoBitrateRange.lower
        val upper = bitrateRegulatorConfig.videoBitrateRange.upper

        val failureRatio = if (metrics.recentWriteCount > 0) {
            metrics.recentWriteFailures.toDouble() / metrics.recentWriteCount
        } else {
            0.0
        }

        val newBitrate = when {
            failureRatio > FAILURE_RATIO_THRESHOLD -> currentVideoBitrate - max(
                currentVideoBitrate * SEVERE_DECREASE_PERCENT / 100,
                MINIMUM_DECREASE_THRESHOLD,
            )

            metrics.lastWriteLatencyMs > HIGH_LATENCY_THRESHOLD_MS -> currentVideoBitrate - max(
                currentVideoBitrate * MILD_DECREASE_PERCENT / 100,
                MINIMUM_DECREASE_THRESHOLD,
            )

            currentVideoBitrate < upper -> currentVideoBitrate + min(
                (upper - currentVideoBitrate) * INCREASE_PERCENT / 100,
                MAXIMUM_INCREASE_THRESHOLD,
            )

            else -> return
        }

        onVideoTargetBitrateChange(newBitrate.coerceIn(lower, upper))
    }

    companion object {
        /** More than this fraction of recent writes failing/timing out means the socket can't keep up. */
        private const val FAILURE_RATIO_THRESHOLD = 0.05
        private const val HIGH_LATENCY_THRESHOLD_MS = 150L
        private const val SEVERE_DECREASE_PERCENT = 25
        private const val MILD_DECREASE_PERCENT = 10
        private const val INCREASE_PERCENT = 15
        private const val MINIMUM_DECREASE_THRESHOLD = 150_000 // b/s
        private const val MAXIMUM_INCREASE_THRESHOLD = 300_000 // b/s
    }

    /**
     * Factory that creates a [RtmpAdaptiveBitrateRegulator].
     */
    class Factory : IBitrateRegulator.Factory {
        override fun newBitrateRegulator(
            bitrateRegulatorConfig: BitrateRegulatorConfig,
            onVideoTargetBitrateChange: ((Int) -> Unit),
            onAudioTargetBitrateChange: ((Int) -> Unit)
        ): RtmpAdaptiveBitrateRegulator {
            return RtmpAdaptiveBitrateRegulator(
                bitrateRegulatorConfig,
                onVideoTargetBitrateChange,
                onAudioTargetBitrateChange,
            )
        }
    }
}
