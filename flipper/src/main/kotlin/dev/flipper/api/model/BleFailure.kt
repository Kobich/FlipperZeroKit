package dev.flipper.api.model

sealed interface BleFailure {
    data object BluetoothUnsupported : BleFailure
    data object BluetoothDisabled : BleFailure
    data object PermissionMissing : BleFailure
    data object LocationServicesDisabled : BleFailure
    data object PairingFailed : BleFailure
    data object TimedOut : BleFailure
    data class ScanFailed(val code: Int) : BleFailure
    data class ConnectionFailed(val code: Int) : BleFailure
    data class ServiceDiscoveryFailed(val code: Int) : BleFailure
    data object InvalidGattProfile : BleFailure
    data object EnableCCCDProblem : BleFailure
    data object RPCError : BleFailure
    data class RPCReadError(val message: String) : BleFailure
    data object RPCSessionInactive : BleFailure
    data class Unknown(val message: String? = null) : BleFailure
}
