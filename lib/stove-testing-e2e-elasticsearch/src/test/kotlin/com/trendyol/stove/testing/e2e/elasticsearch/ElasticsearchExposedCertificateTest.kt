package com.trendyol.stove.testing.e2e.elasticsearch

import arrow.integrations.jackson.module.registerArrowModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.trendyol.stove.functional.get
import com.trendyol.stove.testing.e2e.serialization.StoveObjectMapper
import com.trendyol.stove.testing.e2e.system.abstractions.StateWithProcess
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ElasticsearchExposedCertificateTest : FunSpec({
    test("ser/de") {
        val state =
            """
            {
              "state": {
                "host": "localhost",
                "port": 50543,
                "password": "password",
                "certificate": {
                  "bytes": "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUZXakNDQTBLZ0F3SUJBZ0lWQU4wclloSXpaMS90Rmg5NmR3WGI1b1lWWnl4UU1BMEdDU3FHU0liM0RRRUIKQ3dVQU1Ed3hPakE0QmdOVkJBTVRNVVZzWVhOMGFXTnpaV0Z5WTJnZ2MyVmpkWEpwZEhrZ1lYVjBieTFqYjI1bQphV2QxY21GMGFXOXVJRWhVVkZBZ1EwRXdIaGNOTWpNd09ERTFNVGd5TWpJNFdoY05Nall3T0RFME1UZ3lNakk0CldqQThNVG93T0FZRFZRUURFekZGYkdGemRHbGpjMlZoY21Ob0lITmxZM1Z5YVhSNUlHRjFkRzh0WTI5dVptbG4KZFhKaGRHbHZiaUJJVkZSUUlFTkJNSUlDSWpBTkJna3Foa2lHOXcwQkFRRUZBQU9DQWc4QU1JSUNDZ0tDQWdFQQppcDVGaWwySUMyRGhYbHkyV3RYTFliTnJUbHNkQklZQ3JvK0N1QU40djJHM3RuMkNQTmVoMnM2V0ovRUNrRitVCndJUVVKWEN0Mm43aEJDWkY4M1BlQ1JabWZyWkE0VXNtdzBYWS9OWWpTcnJRQXVtODYvamFZM0lMVGYzU1Jnei8KaWNFVEJCRVM1eTdmSFZlU0xmTjl1ME9hSC9tTnN5Q3FoMERMRFZrWXR5MHNJZXorb1paNmtxN2UrRE1OeHB5Sgp1cjRvUGJ5ekdmY1dnZDdnMll5T2RxNEd1TmFOck8ySjFVZG5BV3B5TVdnME5TSzd1TGlEZ0w5a25ZZlBnMmhQCisrWVpnVkJNNXJBMXJhY2x3c0U0NWJNVlErKzRyWEhhbDUwdS83VmN6a3M5QTREVDc3ZHVyOC9aM1hONWtpMm0KaWNTemJDTHlLdTZwdUhLQWhCdFMyWnlMMXBYN09RSVA2aWZLaVpaU1ZYUGZnMGk5cjVQL3ZFZTJoVXpTTDRVLwpsSWQvaWJUQWtIcmZGbEErN2FreFNzcFJoalMra1ZTQndyR05KQ3BDbWsraitxSnB5Sis5aTNWb0pkanVvemprClk2bS9EZG9kc21LdUZFYUdZNytBT1RVMjAwN0ZjZWdXUEJWejgzU051WmpwbURCczMzS25oeG5WM0RBb0QzUm4KbkNoV2ZQTGo4TUl1OG9tMll3RUpZMUtLR1hzUzU5TWtyclpuQnNjdzl0S0prMFQyRHlLM2dWckY5UnJpMk1mTwpKY3FCWUhBSHRQTHRQZU5STHJLUmxtYkh4NXJFMkNCWEJWQWJ1bU1EaGRIbE1lTWtwT1p3WnoyQWljWUV1anhlClU0TUl5LzczU1RHakhtcGpVT3dKcjNMdVdqVlBMNDlZeTZZWmNPbENsTThDQXdFQUFhTlRNRkV3SFFZRFZSME8KQkJZRUZNUTlobHVXN3VzdGQwZkZDNU5zSkNyTDhaTStNQjhHQTFVZEl3UVlNQmFBRk1ROWhsdVc3dXN0ZDBmRgpDNU5zSkNyTDhaTStNQThHQTFVZEV3RUIvd1FGTUFNQkFmOHdEUVlKS29aSWh2Y05BUUVMQlFBRGdnSUJBSEdOCjQ1Nm1iYXdKUHNLTVgvQlowanB2LytTbCtMTTB6U2gxeEF1YXlmbDk3WnBlbS80QkhGcU5vTUxGOEVqczhXcHoKUHU1Y3Y5VVFaZXNaWVVsNHE4ODY5TW03QnQ5UHVRcUJBR25VbTU3alhMRkRsdDRvVTFvZmVpalF1YkZ3M0wwMwpGa2NsQ3psZ2JhV21vb2ZKRTdKK2FEMGo5bHNOWllKem9tQlN6QnZGTC9uK0ptS0poQVk4SDNwTkNqdExtbXZjClZPbmluQWFScGxLQndSS1RRYm1ZVE53QXVTcEhvSUk4empqK3pGWm54MzVqSitJY0YwblQ1Q3FQT0tCcllxTmwKN21kTnU0OGs0eUpiY0JtYXNoa3BRdkQra2Q1RFJBWmZXZ2tjZzVZUk1RUnE3RnVpWkhxcmFVdWV2WmZ3dnB2UApqMmV5M0QwMG5aSUVIN3I0alVpVnl0SGNGejVQU29zRmIwZDlmWkRJYmJGanRQblpSTEVxbS8wd3N1V25VSVdRCnlSWTNvclNiMUZIYjdYQUlUdHlnZlZQZnlUV0lnemdtbjFCR3Z2eE5sYjIyVnB4TXcvaEpLWTU0WDRjc2s1RzkKbHZMNUVzT3BvYnZvWVJRNU9taHlJT1ZGSHUwcjRKZWkzcGJ3dTczWmlnLzNFanJLY0lRS0ttYzdhQUFkbGREeQpid0dRWDdvYzRLS1lra2JPNFNNQTRTZzUxQjJFZFEzVGYrSHJlUjFTcHN1TlB1U2p0aGY5MGY2eWYrU1d0NU04CnY2RmpVRy9sR0NGTndJTTd2N1o3SHhMVnIvbVg4MTRKVzBGREdLUmhHRHd3SDUzcTJYSmRaaEl5RlNaeWtuc1UKdmJLeW51Vm43czZrU1pYbnh2NnYyTTNsL09ZMjdpNHdUVnB6bzhXbgotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCg=="
                }
              },
              "processId": 10496
            }
            """.trimIndent()
        val j = StoveObjectMapper.byConfiguring { this.registerArrowModule() }
        val stateWithProcess = j.readValue<StateWithProcess<ElasticSearchExposedConfiguration>>(state)
        val serialize = j.writeValueAsString(stateWithProcess)
        val stateWithProcess2 = j.readValue<StateWithProcess<ElasticSearchExposedConfiguration>>(serialize)

        val cert = stateWithProcess2.state.certificate.get()
        cert.bytes.size shouldBeGreaterThan 0
        cert.sslContext shouldNotBe null
        cert.sslContext.protocol shouldBe "TLSv1.3"
    }
})
