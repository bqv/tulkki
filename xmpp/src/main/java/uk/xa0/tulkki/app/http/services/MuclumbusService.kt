package uk.xa0.tulkki.app.http.services

import java.util.Collections
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * The muclumbus directory HTTP API.
 *
 * Ported from `MuclumbusService.java`. It is *ours*, so it is
 * converted in place. The Retrofit annotations are moved verbatim - they are the wire format - and
 * the nested DTOs keep their Java-visible fields with `@JvmField` wherever Java exposed one, so
 * Gson's reflection sees exactly the field names and types it saw before.
 *
 * Tulkki: C5-C - the muclumbus wire room, declared here so this interface names no `:data` type.
 *
 * This class is the fourth kind of coupling the boundary has met: not a member, not a parameter,
 * not a construction, but a **deserialisation target**. `Rooms.items` and `Result.items` used to be
 * `List<Room>` - the `:data` model - and Gson built them by reflection. A ref there would not have
 * worked and no instrument we run would have caught it: Gson cannot instantiate an interface, so the
 * field would have deserialised to `null` and the search screen would simply have shown nothing, in
 * release, with a green build.
 *
 * So the DTO gets its own type instead of being forced to a ref, which is the first of the two shapes
 * the brief offered. The fields are exactly the ones Gson was reading off `Room` - same names, same
 * types, same absence of `@SerializedName` - so the JSON mapping is unchanged field-for-field; the
 * *conversion* to the model happens one layer up, in `:app`'s `ChannelDiscoveryService`, which is
 * allowed to name both. Nothing about the wire format moved.
 */
interface MuclumbusService {

    @GET("/api/1.0/rooms/unsafe")
    fun getRooms(@Query("p") page: Int): Call<Rooms>

    @POST("/api/1.0/search")
    fun search(@Body searchRequest: SearchRequest): Call<SearchResult>

    class MuclumbusRoom {
        @JvmField var address: String? = null
        @JvmField var name: String? = null
        @JvmField var description: String? = null
        @JvmField var language: String? = null
        @JvmField var nusers: Int = 0
    }

    class Rooms {
        @JvmField var page: Int = 0
        @JvmField var total: Int = 0
        @JvmField var pages: Int = 0
        @JvmField var items: List<MuclumbusRoom>? = null
    }

    class SearchRequest(keyword: String) {
        @JvmField val keywords: Set<String> = Collections.singleton(keyword)
    }

    class SearchResult {
        /**
         * `body.result.items` is read by `ChannelDiscoveryService.kt:221` without a safe call, so the
         * type must stay non-null; `lateinit` keeps the field null until Gson fills it, exactly as
         * the Java field was.
         */
        lateinit var result: Result
    }

    class Result {
        @JvmField var items: List<MuclumbusRoom>? = null
    }
}
