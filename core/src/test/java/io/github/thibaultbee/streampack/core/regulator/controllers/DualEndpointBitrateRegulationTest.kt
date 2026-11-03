package io.github.thibaultbee.streampack.core.regulator.controllers

import io.github.thibaultbee.streampack.core.elements.encoders.IEncoder
import io.github.thibaultbee.streampack.core.elements.endpoints.DualEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.regulator.BitrateRegulator
import io.github.thibaultbee.streampack.core.regulator.IBitrateRegulator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class DualEndpointBitrateRegulationTest {
    private fun mockEndpoint(metrics: Any) = mockk<IEndpointInternal>(relaxed = true).also {
        every { it.isOpenFlow } returns MutableStateFlow(false)
        every { it.throwableFlow } returns MutableStateFlow(null)
        every { it.metrics } returns metrics
    }

    @Test
    fun `metrics come from the main endpoint`() {
        val mainMetrics = Any()
        val endpoint = DualEndpoint(mockEndpoint(mainMetrics), mockEndpoint(Any()), Dispatchers.Unconfined)

        assertSame(mainMetrics, endpoint.metrics)
    }

    @Test
    fun `a metrics failure does not stop regulation`() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val metrics = Any()
            val endpoint = mockk<IEndpoint>()
            every { endpoint.metrics } throws IllegalStateException("Endpoint is not opened") andThen metrics
            val regulator = mockk<BitrateRegulator>(relaxed = true)
            val factory = mockk<IBitrateRegulator.Factory>()
            every { factory.newBitrateRegulator(any(), any(), any()) } returns regulator
            val controller = DummyBitrateRegulatorController(
                audioEncoder = null,
                videoEncoder = mockk<IEncoder>(relaxed = true),
                endpoint = endpoint,
                bitrateRegulatorFactory = factory,
                coroutineDispatcher = dispatcher,
                delayTimeInMs = 1000,
            )

            controller.start()
            advanceTimeBy(2500)
            controller.stop()

            verify(exactly = 1) { regulator.update(metrics, any(), any()) }
        }
    }
}
