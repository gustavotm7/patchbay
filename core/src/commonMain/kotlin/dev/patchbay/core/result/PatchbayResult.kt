package dev.patchbay.core.result

/**
 * Explicit result type used across core. Failures are values, never swallowed exceptions.
 * Adapters convert protocol/IO exceptions into [PatchbayError] at their boundary.
 */
public sealed interface PatchbayResult<out T> {
    public data class Ok<out T>(val value: T) : PatchbayResult<T>
    public data class Err(val error: PatchbayError) : PatchbayResult<Nothing>
}

public sealed interface PatchbayError {
    /** Human-readable, already redacted (never contains secrets, codes or full serials). */
    public val message: String

    public data class InvalidArgument(override val message: String) : PatchbayError
    public data class Unsupported(override val message: String) : PatchbayError
    public data class Unreachable(override val message: String) : PatchbayError
    public data class Unauthorized(override val message: String) : PatchbayError
    public data class Timeout(override val message: String) : PatchbayError

    /** The user backed out (cancelled a pairing step). Not a fault: nothing is reported and nothing is left on screen. */
    public data class Cancelled(override val message: String) : PatchbayError

    /** The device sent something that violates the protocol or our parser bounds. */
    public data class ProtocolViolation(override val message: String) : PatchbayError

    /** NetGuard refused a destination. The address is included; it is never a secret. */
    public data class NetGuardDenied(val address: String, override val message: String) : PatchbayError

    /** A pinned certificate or key changed. Always surfaced to the user as an alert. */
    public data class TrustChanged(val deviceName: String, override val message: String) : PatchbayError
}

public inline fun <T, R> PatchbayResult<T>.map(transform: (T) -> R): PatchbayResult<R> = when (this) {
    is PatchbayResult.Ok -> PatchbayResult.Ok(transform(value))
    is PatchbayResult.Err -> this
}

public fun <T> PatchbayResult<T>.getOrNull(): T? = (this as? PatchbayResult.Ok)?.value

public fun <T> PatchbayResult<T>.errorOrNull(): PatchbayError? = (this as? PatchbayResult.Err)?.error

public fun ok(): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)

public fun invalid(message: String): PatchbayResult<Nothing> =
    PatchbayResult.Err(PatchbayError.InvalidArgument(message))

public fun unsupported(message: String): PatchbayResult<Nothing> =
    PatchbayResult.Err(PatchbayError.Unsupported(message))
