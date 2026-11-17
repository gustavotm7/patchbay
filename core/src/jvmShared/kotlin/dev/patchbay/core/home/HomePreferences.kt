package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The few things the owner sets that are not about one device: the home's location (for sunrise and sunset) and which devices are favourites. */
internal class HomePreferences(private val ctx: HomeContext) {
    private val _location = MutableStateFlow<HomeLocation?>(null)
    val location: StateFlow<HomeLocation?> = _location.asStateFlow()
    private val _favourites = MutableStateFlow<Set<DeviceId>>(emptySet())
    val favourites: StateFlow<Set<DeviceId>> = _favourites.asStateFlow()

    fun load() {
        ctx.store?.setting("location")?.let { raw ->
            _location.value = try { ctx.json.decodeFromString<StoredLocation>(raw).let { HomeLocation(it.lat, it.lon) } } catch (e: Exception) { null }
        }
        ctx.store?.setting("favourites")?.let { raw ->
            _favourites.value = try { ctx.json.decodeFromString<List<String>>(raw).map(::DeviceId).toSet() } catch (e: Exception) { emptySet() }
        }
    }

    suspend fun setLocation(latitude: Double, longitude: Double): PatchbayResult<Unit> {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return PatchbayResult.Err(PatchbayError.InvalidArgument("Latitude is -90 to 90 and longitude -180 to 180"))
        _location.value = HomeLocation(latitude, longitude)
        ctx.store?.putSetting("location", ctx.json.encodeToString(StoredLocation(latitude, longitude)))
        return PatchbayResult.Ok(Unit)
    }

    suspend fun setFavourite(device: DeviceId, on: Boolean) {
        _favourites.update { if (on) it + device else it - device }
        ctx.store?.putSetting("favourites", ctx.json.encodeToString(_favourites.value.map { it.value }))
    }
}
