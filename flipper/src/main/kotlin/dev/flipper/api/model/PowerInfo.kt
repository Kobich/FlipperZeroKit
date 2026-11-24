package dev.flipper.api.model

/** Raw firmware keys are retained; absent/invalid values stay unknown. */
data class PowerInfo(val fields: Map<String, String>) {
    val chargePercent: Int? get() = (fields["charge_level"] ?: fields["charge.level"])
        ?.toIntOrNull()?.takeIf { it in 0..100 }
    val chargeState: String? get() = fields["charge_state"] ?: fields["charge.state"]
}
