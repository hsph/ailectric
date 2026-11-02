package app.ailectric

import app.ailectric.location.LocationSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationProvidersTest {
    @Test fun `gps stays registered next to fused, which may be disabled`() {
        assertThat(LocationSource.providersToUse(listOf("passive", "gps", "network", "fused")))
            .containsExactly("fused", "gps", "network").inOrder()
    }

    @Test fun `devices without fused or network still use gps`() {
        assertThat(LocationSource.providersToUse(listOf("gps"))).containsExactly("gps")
    }
}
