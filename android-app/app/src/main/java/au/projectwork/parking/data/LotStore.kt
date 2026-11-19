package au.projectwork.parking.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/** Last lot list (shown on cold start / offline) and the favourite lot ids, kept across restarts. */
interface LotStore {
    val favourites: Flow<Set<Int>>
    suspend fun toggleFavourite(id: Int)
    suspend fun saveLots(lots: List<LotSummary>, fetchedAt: Long)
    /** The last saved list and when it was fetched, or null before the first successful fetch. */
    suspend fun loadLots(): Pair<List<LotSummary>, Long>?
}

// a file left half-written (e.g. the phone died mid-save) would otherwise crash every launch; start empty instead
private val Context.dataStore by preferencesDataStore(
    "parking",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
private val FAVOURITES = stringSetPreferencesKey("favourites")
private val LOTS = stringPreferencesKey("lots")
private val LOTS_AT = longPreferencesKey("lots_at")

class DataStoreLotStore(context: Context) : LotStore {
    private val store = context.dataStore

    override val favourites = store.data.map { prefs -> prefs[FAVOURITES].orEmpty().mapNotNull(String::toIntOrNull).toSet() }

    override suspend fun toggleFavourite(id: Int) {
        store.edit { prefs ->
            val now = prefs[FAVOURITES].orEmpty()
            prefs[FAVOURITES] = if ("$id" in now) now - "$id" else now + "$id"
        }
    }

    override suspend fun saveLots(lots: List<LotSummary>, fetchedAt: Long) {
        store.edit {
            it[LOTS] = ParkingJson.encodeToString(lots)
            it[LOTS_AT] = fetchedAt
        }
    }

    override suspend fun loadLots(): Pair<List<LotSummary>, Long>? {
        val prefs = store.data.first()
        val json = prefs[LOTS] ?: return null
        // a cache written by an older app version may not decode; treat it as no cache
        val lots = runCatching { ParkingJson.decodeFromString<List<LotSummary>>(json) }.getOrNull() ?: return null
        return lots to (prefs[LOTS_AT] ?: 0L)
    }
}

private val DARK_THEME = booleanPreferencesKey("dark_theme")

/** The theme picked with the sun/moon button; null until the user picks one, which means "follow the system". */
class ThemeStore(context: Context) {
    private val store = context.dataStore

    val dark: Flow<Boolean?> = store.data.map { it[DARK_THEME] }

    suspend fun setDark(dark: Boolean) {
        store.edit { it[DARK_THEME] = dark }
    }
}
