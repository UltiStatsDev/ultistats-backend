package com.github.mihanizzm.ultistats.realtime

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "ultistats.realtime")
class MatchRealtimeProperties {
    var heartbeatIntervalMs: Long = 15_000
    var reconnectTimeMs: Long = 2_000
}
