package dev.flipper.api.model

import java.io.IOException

class FlipperRpcException(
    val reason: Reason,
    message: String,
    val commandId: Int? = null,
    val statusCode: Int? = null,
    val transportFailure: BleFailure? = null,
) : IOException(message) {
    enum class Reason {
        NOT_READY,
        SESSION_CLOSED,
        SEND_FAILED,
        REMOTE_ERROR,
        UNEXPECTED_RESPONSE,
        TIMEOUT,
    }
}
