package uk.xa0.tulkki.app.services

import android.util.Log
import com.google.common.base.Strings
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uk.xa0.tulkki.app.http.HttpConnectionManager
import uk.xa0.tulkki.app.http.services.MuclumbusService
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Room
import uk.xa0.tulkki.parser.IqParser
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Finds public channels: a muclumbus server for the whole network, disco#items for the local one.
 *
 * <p>**The search crosses the boundary without an island member.** `discover` used to be
 * `ChannelDiscoveryPort`'s third member; port-13's `RoomRef` deletion moved it here, and the island
 * keeps only the two lifecycle answers its own code calls (`initializeMuclumbusService`,
 * `cleanCache`). The composition root, which builds this class, is what names it. The decision still
 * travels as a `boolean` - the `Method` enum is this file's own - and the continuation as a JDK
 * `Consumer`, an interface `:ui` already implements. Kotlin's `List` covariance is what the Java's
 * unchecked `(List<RoomRef>) (List<?>)` cast stood in for, so the callback carries `List<Room>` and
 * the composition root needs no cast at all.
 *
 * <p>**The field the cache hangs off stays nullable and is bound to a local.** `muclumbusService` is
 * assigned by `initializeMuclumbusService` and read by both `discoverChannelsJabberNetwork`
 * overloads after a null test; Kotlin cannot smart-cast a mutable property, so each reads it once
 * into `muclumbus` - the same object the Java would have re-read.
 *
 * <p>**The private statics move to the companion** (`toRooms`, `copyMatching`, `key`, `logError`),
 * Kotlin having no static method outside one; `getLocalMucServices` stays an instance method, as the
 * Java's was. `String.join("\00", mucServices.keySet())` is `joinToString` on the same key set -
 * `String`'s Java statics are not reachable through Kotlin's `String` (the `String.valueOf` family),
 * and `joinToString` prints each `Jid` through `toString()` exactly as `String.join` did.
 *
 * <p>`finishDiscoSearch` takes a `MutableList<Room>` because it sorts in place via
 * `Collections.sort`, and `discoverChannelsLocalServers`' `rooms` is the `ArrayList` it fills. The
 * `@NonNull` annotations are dropped (Kotlin's types say it) and the two `Callback` objects are
 * Java interfaces, so they become `object :` rather than lambdas - `Callback` has two methods.
 *
 * <p>Name-string audit: 0 hits for `ChannelDiscoveryService`, `Method` or
 * `OnChannelSearchResultsFound` in the manifest, `res/xml`, `res/layout*`, `preferences_*.xml` and
 * the ProGuard rules.
 */
class ChannelDiscoveryService(private val service: XmppConnectionService) :
        uk.xa0.tulkki.xmpp.services.ChannelDiscoveryPort {

    private var muclumbusService: MuclumbusService? = null

    private val cache: Cache<String, List<Room>> =
            CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build()

    /**
     * 3.7 pair 4: the discovery service's own entry point.
     *
     * <p>Not an `override` any more: `ChannelDiscoveryPort` no longer declares it, and the only
     * caller is the composition root's forward from `UiHost`. The search screen's own `Method` enum
     * stays this file's - the screen's word for "this server only" arrives as a boolean and is turned
     * back into the enum the cache key and the request use. The callback arrives as a JDK `Consumer`
     * for the same reason the boolean does: the screen implements one interface it already has
     * instead of a new island type.
     */
    fun discover(
            query: String?,
            localServerOnly: Boolean,
            // Nullable, because the Java this replaces was. `checkpoint-pre-kotlin`'s
            // `services/ChannelDiscoveryService.java:71` annotates `query` `@NonNull` and leaves
            // `mucServices` bare, so it is a platform type there and null is a legal argument - and
            // `ChannelDiscoveryActivity.onBackendConnected` really passes null when the query is not
            // for this device's own servers. Declaring it non-null here put a Kotlin intrinsic null
            // check on that path and crashed the app ("Parameter specified as non-null is null:
            // method ...discover, parameter mucServices"). Every other use in this file already took
            // it nullable, `key(method, mucServices, query)` included, whose Java answers the null map
            // with its own `"\00"` service key.
            mucServices: Map<Jid, AccountRef>?,
            onChannelSearchResultsFound: Consumer<List<Room>>
    ) {
        // The trim the island's `discoverChannels` forward used to do on the way in - the caller's
        // query is nullable on `:ui`'s side and the cache key and the request both want a string.
        discover(
                Strings.nullToEmpty(query).trim(),
                if (localServerOnly) Method.LOCAL_SERVER else Method.JABBER_NETWORK,
                mucServices,
                // Tulkki: 3.7 pair 9, part 15 - the objects are the model's (this class builds them
                // itself, from the wire), and Kotlin's `List` covariance makes the Java's unchecked
                // `(List<RoomRef>) (List<?>)` cast unnecessary rather than merely unchecked.
                OnChannelSearchResultsFound { results -> onChannelSearchResultsFound.accept(results) })
    }

    override fun initializeMuclumbusService() {
        if (Strings.isNullOrEmpty(Config.CHANNEL_DISCOVERY)) {
            this.muclumbusService = null
            return
        }
        val builder: OkHttpClient.Builder =
                HttpConnectionManager.okHttpClient(service).newBuilder()
        if (service.useTorToConnect() || service.useI2PToConnect()) {
            builder.proxy(HttpConnectionManager.getProxy(service.useI2PToConnect()))
        }
        val retrofit =
                Retrofit.Builder()
                        .client(builder.build())
                        .baseUrl(Config.CHANNEL_DISCOVERY)
                        .addConverterFactory(GsonConverterFactory.create())
                        .callbackExecutor(Executors.newSingleThreadExecutor())
                        .build()
        this.muclumbusService = retrofit.create(MuclumbusService::class.java)
    }

    override fun cleanCache() {
        cache.invalidateAll()
    }

    fun discover(
            query: String,
            method: Method,
            mucServices: Map<Jid, AccountRef>?,
            onChannelSearchResultsFound: OnChannelSearchResultsFound
    ) {
        val result = cache.getIfPresent(key(method, mucServices, query))
        if (result != null) {
            onChannelSearchResultsFound.onChannelSearchResultsFound(result)
            return
        }
        if (method == Method.LOCAL_SERVER) {
            discoverChannelsLocalServers(query, mucServices, onChannelSearchResultsFound)
        } else {
            if (query.isEmpty()) {
                discoverChannelsJabberNetwork(onChannelSearchResultsFound)
            } else {
                discoverChannelsJabberNetwork(query, onChannelSearchResultsFound)
            }
        }
    }

    private fun discoverChannelsJabberNetwork(listener: OnChannelSearchResultsFound) {
        val muclumbus = muclumbusService
        if (muclumbus == null) {
            listener.onChannelSearchResultsFound(Collections.emptyList())
            return
        }
        val call: Call<MuclumbusService.Rooms> = muclumbus.getRooms(1)
        call.enqueue(
                object : Callback<MuclumbusService.Rooms> {
                    override fun onResponse(
                            call: Call<MuclumbusService.Rooms>,
                            response: Response<MuclumbusService.Rooms>
                    ) {
                        val body = response.body()
                        if (body == null) {
                            listener.onChannelSearchResultsFound(Collections.emptyList())
                            logError(response)
                            return
                        }
                        // Tulkki: C5-C - the DTO carries its own wire type now; the conversion to the
                        // model is here, on the `:app` side, which may name both. See `MuclumbusRoom`.
                        val rooms = toRooms(body.items)
                        cache.put(key(Method.JABBER_NETWORK, null, ""), rooms)
                        listener.onChannelSearchResultsFound(rooms)
                    }

                    override fun onFailure(
                            call: Call<MuclumbusService.Rooms>,
                            throwable: Throwable
                    ) {
                        Log.d(
                                Config.LOGTAG,
                                "Unable to query muclumbus on " + Config.CHANNEL_DISCOVERY,
                                throwable)
                        listener.onChannelSearchResultsFound(Collections.emptyList())
                    }
                })
    }

    private fun discoverChannelsJabberNetwork(
            query: String,
            listener: OnChannelSearchResultsFound
    ) {
        val muclumbus = muclumbusService
        if (muclumbus == null) {
            listener.onChannelSearchResultsFound(Collections.emptyList())
            return
        }
        val searchRequest = MuclumbusService.SearchRequest(query)
        val searchResultCall: Call<MuclumbusService.SearchResult> =
                muclumbus.search(searchRequest)
        searchResultCall.enqueue(
                object : Callback<MuclumbusService.SearchResult> {
                    override fun onResponse(
                            call: Call<MuclumbusService.SearchResult>,
                            response: Response<MuclumbusService.SearchResult>
                    ) {
                        val body = response.body()
                        if (body == null) {
                            listener.onChannelSearchResultsFound(Collections.emptyList())
                            logError(response)
                            return
                        }
                        val rooms = toRooms(body.result.items)
                        cache.put(key(Method.JABBER_NETWORK, null, query), rooms)
                        listener.onChannelSearchResultsFound(rooms)
                    }

                    override fun onFailure(
                            call: Call<MuclumbusService.SearchResult>,
                            throwable: Throwable
                    ) {
                        Log.d(
                                Config.LOGTAG,
                                "Unable to query muclumbus on " + Config.CHANNEL_DISCOVERY,
                                throwable)
                        listener.onChannelSearchResultsFound(Collections.emptyList())
                    }
                })
    }

    private fun discoverChannelsLocalServers(
            query: String,
            mucServices: Map<Jid, AccountRef>?,
            listener: OnChannelSearchResultsFound
    ) {
        val localMucService = mucServices ?: getLocalMucServices()
        Log.d(Config.LOGTAG, "checking with " + localMucService.size + " muc services")
        if (localMucService.isEmpty()) {
            listener.onChannelSearchResultsFound(Collections.emptyList())
            return
        }
        if (!query.isEmpty()) {
            val cached = cache.getIfPresent(key(Method.LOCAL_SERVER, mucServices, ""))
            if (cached != null) {
                val results = copyMatching(cached, query)
                cache.put(key(Method.LOCAL_SERVER, mucServices, query), results)
                listener.onChannelSearchResultsFound(results)
            }
        }
        val queriesInFlight = AtomicInteger()
        val rooms = ArrayList<Room>()
        for (entry in localMucService.entries) {
            val itemsRequest = service.getIqGenerator().queryDiscoItems(entry.key)
            queriesInFlight.incrementAndGet()
            val account = entry.value
            service.sendIqPacket(
                    account,
                    itemsRequest,
                    Consumer { itemsResponse ->
                        if (itemsResponse.getType() == Iq.Type.RESULT) {
                            val items = IqParser.items(itemsResponse)
                            for (item in items) {
                                // Only looking for MUCs for now, and by spec they have a localpart
                                if (item.isDomainJid()) continue
                                val infoRequest = service.getIqGenerator().queryDiscoInfo(item)
                                queriesInFlight.incrementAndGet()
                                service.sendIqPacket(
                                        account,
                                        infoRequest,
                                        Consumer { infoResponse ->
                                            if (infoResponse.getType() == Iq.Type.RESULT) {
                                                // Tulkki: 3.7 pair 9 - the parse is `:app`'s own
                                                // now (`RoomParser`, beside this class). The island's
                                                // copy named a ref and is gone with it.
                                                val room = RoomParser.parse(infoResponse)
                                                if (room != null) {
                                                    rooms.add(room)
                                                }
                                                if (queriesInFlight.decrementAndGet() <= 0) {
                                                    finishDiscoSearch(
                                                            rooms,
                                                            query,
                                                            mucServices,
                                                            listener)
                                                }
                                            } else {
                                                queriesInFlight.decrementAndGet()
                                            }
                                        },
                                        20L)
                            }
                        }
                        if (queriesInFlight.decrementAndGet() <= 0) {
                            finishDiscoSearch(rooms, query, mucServices, listener)
                        }
                    })
        }
    }

    private fun finishDiscoSearch(
            rooms: MutableList<Room>,
            query: String,
            mucServices: Map<Jid, AccountRef>?,
            listener: OnChannelSearchResultsFound
    ) {
        Collections.sort(rooms)
        cache.put(key(Method.LOCAL_SERVER, mucServices, ""), rooms)
        if (query.isEmpty()) {
            listener.onChannelSearchResultsFound(rooms)
        } else {
            val results = copyMatching(rooms, query)
            cache.put(key(Method.LOCAL_SERVER, mucServices, query), results)
            listener.onChannelSearchResultsFound(rooms)
        }
    }

    private fun getLocalMucServices(): Map<Jid, Account> {
        val localMucServices = HashMap<Jid, Account>()
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.isEnabled()) {
                val xmppConnection: XmppConnection? = account.getXmppConnection()
                if (xmppConnection == null) {
                    continue
                }
                for (mucService in xmppConnection.getMucServers()) {
                    val jid = Jid.of(mucService)
                    if (!localMucServices.containsKey(jid)) {
                        localMucServices.put(jid, account)
                    }
                }
            }
        }
        return localMucServices
    }

    fun interface OnChannelSearchResultsFound {
        fun onChannelSearchResultsFound(results: List<Room>)
    }

    enum class Method {
        JABBER_NETWORK,
        LOCAL_SERVER
    }

    companion object {

        /**
         * Tulkki: C5-C - the muclumbus DTO's own wire type, converted to the model at the boundary.
         *
         * <p>Chosen over "make the DTO field a ref": Gson cannot instantiate an interface, so a ref
         * there deserialises to `null` and the screen silently shows nothing - a failure no instrument
         * we run would catch. This class is `:app`, so it may name `MuclumbusRoom` and `Room` both,
         * and the conversion is the only thing that changes.
         *
         * <p>One behaviour change, stated rather than hidden: a response whose `items` is absent used
         * to reach `cache.put(key, null)` and throw `NullPointerException` out of the Retrofit
         * callback. It now yields an empty list, which is what the `body == null` branch directly
         * above already does for the same class of malformed answer. Every other value maps
         * field-for-field, including `nusers` defaulting to 0 and the strings to null exactly as Gson
         * defaulted them on `Room`.
         */
        private fun toRooms(items: List<MuclumbusService.MuclumbusRoom>?): List<Room> {
            if (items == null) {
                return Collections.emptyList()
            }
            val rooms = ArrayList<Room>(items.size)
            for (item in items) {
                rooms.add(
                        Room(
                                item.address,
                                item.name,
                                item.description,
                                item.language,
                                item.nusers))
            }
            return rooms
        }

        private fun copyMatching(haystack: List<Room>, needle: String): List<Room> {
            val result = ArrayList<Room>()
            for (room in haystack) {
                if (room.contains(needle)) {
                    result.add(room)
                }
            }
            return result
        }

        private fun key(
                method: Method,
                mucServices: Map<Jid, AccountRef>?,
                query: String
        ): String {
            val servicesKey =
                    if (mucServices == null) "\u0000"
                    else mucServices.keys.joinToString("\u0000")
            return String.format("%s\u0000%s\u0000%s", method, servicesKey, query)
        }

        private fun logError(response: Response<*>) {
            val errorBody: ResponseBody? = response.errorBody()
            Log.d(Config.LOGTAG, "code from muclumbus=" + response.code())
            if (errorBody == null) {
                return
            }
            try {
                Log.d(Config.LOGTAG, "error body=" + errorBody.string())
            } catch (e: IOException) {
                // ignored
            }
        }
    }
}
