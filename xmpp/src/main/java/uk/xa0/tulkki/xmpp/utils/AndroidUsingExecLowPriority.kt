/*
 * Copyright 2015-2016 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package uk.xa0.tulkki.xmpp.utils

import java.io.IOException
import java.io.InputStreamReader
import java.io.LineNumberReader
import java.net.InetAddress
import java.util.ArrayList
import java.util.HashSet
import java.util.logging.Level
import org.minidns.dnsserverlookup.AbstractDnsServerLookupMechanism
import org.minidns.dnsserverlookup.AndroidUsingReflection
import org.minidns.dnsserverlookup.DnsServerLookupMechanism
import org.minidns.util.PlatformDetection

/**
 * Try to retrieve the list of DNS server by executing getprop.
 *
 * Ported from Java by the `px` lane. Decisions taken rather than inherited:
 *
 * 1. **`INSTANCE` is declared *before* `PRIORITY`**, which is the Java static-initialisation order
 *    and therefore a behaviour, not a style: constructing `INSTANCE` reads `PRIORITY` while it is
 *    still `0`, so the instance is registered at priority `0` rather than
 *    `AndroidUsingReflection.PRIORITY + 1`. Kotlin companions initialise their properties in
 *    declaration order exactly as Java's `<clinit>` does, so the order is kept deliberately. It is a
 *    latent defect in the Java and is preserved, not fixed here.
 * 2. **The two constants are `@JvmField`**: `Resolver.java:248` reads `INSTANCE`, and `PRIORITY` is
 *    the same `public static final int`.
 * 3. **`getDnsServerAddresses` answers `List<String>?`**, because Java's `return null` at the end is
 *    a real return path on a `getprop` failure; the base method is a Java platform type, so the
 *    nullable override is legal and faithful.
 * 4. **The inherited `LOGGER` is qualified with the declaring class**
 *    (`AbstractDnsServerLookupMechanism.LOGGER`), because Kotlin does not inherit Java statics into
 *    the subclass scope; the field and the `Level.WARNING` call are Java's.
 * 5. **The `ip == null` and `value == null` tests are kept.** Kotlin's platform types mean
 *    `InetAddress.getByName` is not statically known to be non-null, so the Java guards survive
 *    verbatim, dead or not; deleting them is not this commit's business.
 * 6. **The `while ((line = lnr.readLine()) != null)` becomes `while (true)` with the read at the top
 *    of the body** (`lnr.readLine() ?: break`), which is the same read-per-iteration shape and keeps
 *    every `continue` landing on a fresh line instead of skipping the read.
 */
class AndroidUsingExecLowPriority private constructor() :
    AbstractDnsServerLookupMechanism(
        AndroidUsingExecLowPriority::class.java.simpleName,
        PRIORITY,
    ) {

    override fun getDnsServerAddresses(): List<String>? {
        try {
            val process = Runtime.getRuntime().exec("getprop")
            val inputStream = process.inputStream
            val lnr = LineNumberReader(InputStreamReader(inputStream))
            val server = HashSet<String>(6)
            // The read is at the top of the body so that every `continue` below lands on a freshly
            // read line, which is what Java's `while ((line = lnr.readLine()) != null)` did.
            while (true) {
                val line = lnr.readLine() ?: break
                val split = line.indexOf("]: [")
                if (split == -1) {
                    continue
                }
                val property = line.substring(1, split)
                var value = line.substring(split + 4, line.length - 1)

                if (value.isEmpty()) {
                    continue
                }

                if (property.endsWith(".dns") ||
                    property.endsWith(".dns1") ||
                    property.endsWith(".dns2") ||
                    property.endsWith(".dns3") ||
                    property.endsWith(".dns4")
                ) {
                    // normalize the address
                    val ip = InetAddress.getByName(value)

                    if (ip == null) {
                        continue
                    }

                    value = ip.hostAddress

                    if (value == null) {
                        continue
                    }
                    if (value.isEmpty()) {
                        continue
                    }

                    server.add(value)
                }
            }
            if (server.size > 0) {
                return ArrayList(server)
            }
        } catch (e: IOException) {
            AbstractDnsServerLookupMechanism.LOGGER.log(
                Level.WARNING,
                "Exception in findDNSByExec",
                e,
            )
        }
        return null
    }

    override fun isAvailable(): Boolean = PlatformDetection.isAndroid()

    companion object {

        @JvmField
        val INSTANCE: DnsServerLookupMechanism = AndroidUsingExecLowPriority()

        @JvmField val PRIORITY: Int = AndroidUsingReflection.PRIORITY + 1
    }
}
