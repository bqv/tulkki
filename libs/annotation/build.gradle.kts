import java.util.Properties

apply(plugin = "java-library")
// Kotlin, because every type in this module is ours and the repo owns its own code in Kotlin:
// `uk.xa0.tulkki.annotation.XmlElement` and the six `uk.xa0.tulkki.libs` types (`Transferable`,
// `AudioDevice`, `TextAvatar`, `Avatarable`, `FilePathInfoRef`, `EmojiRef`). This was the owner's
// 2026-10-08 decision ("libs/annotation may stop being a bare java-library"); the module's Java
// files - `AudioDevice.java`, `TextAvatar.java`, `Transferable.java`, `FilePathInfoRef.java`
// (lane `F`) and last `EmojiRef.java` (lane `D`) - all converted the same day, so there
// is no Java half left here and no joint compilation left to do. `java-library` stays applied: it
// is what provides the `api`/`implementation` configurations and the `src/main/java` source set
// the Kotlin tree is compiled from.
// 2026-10-08, lane `G`: there is a Java half again. Eight refs landed here as **Java** by the
// `:libs` interface move - `AppSettingsRef`, `CommentRef`, `DownloadableFileRef`, `PostRef`,
// `PresenceRef`, `PresenceTemplateRef`, `PresencesRef`, `ServiceDiscoveryResultRef` - and they
// stayed Java on purpose: a Java getter a Kotlin caller reads keeps the synthetic-property spelling,
// and an unannotated Java reference type keeps the platform nullability a Kotlin respell would
// narrow. `java-library` is what compiles them, exactly as it did `FilePathInfoRef.java` before
// lane `F` respelled it; the Kotlin types beside them are unchanged and joint compilation is not
// involved (nothing here references a Kotlin type and nothing there references a Java one).
// Later the same day, lane `G`: the eight respelled to **Kotlin** (`AppSettingsRef.kt`,
// `CommentRef.kt`, `DownloadableFileRef.kt`, `PostRef.kt`, `PresenceRef.kt`, `PresenceTemplateRef.kt`,
// `PresencesRef.kt`, `ServiceDiscoveryResultRef.kt`), together with `Jid.kt`, `IP.kt`,
// `ReactionRef.java` and `StoryRef.java` that joined them. The two reasons above are now answered in
// the other direction: every member keeps its `getX`/`isX` JVM name as a `fun`, and each member's
// nullability is the *implementation's*, not a narrowing - which is why the respell carried a caller
// sweep (`TransferRequestBody`'s `file.size`/`file.key`/`file.mimeType`, `TransferCipher`'s four,
// `PresenceTemplateAdapter` and `AccountMaintenance`'s nullable `getStatusMessage()`,
// `ServiceDiscovery.fetchCaps`'s null-half key, `MessageParser.hasIdentityKnowForSendingHtml`'s
// `contains(null)`, and `HttpDownloadConnection`'s two). `AudioDevice.java`, `TextAvatar.java`,
// `Transferable.java`, `FilePathInfoRef.java` and `EmojiRef.java` were the earlier respells.
// `ReactionRef.java` and `StoryRef.java` were the module's last `.java` files, and lane `G`
// respelled them later the same night (`ReactionRef.kt`, `StoryRef.kt`) - so **there is no Java
// half left here at all**: `find libs -name '*.java'` is empty and this is a pure-Kotlin JVM
// module again. `java-library` stays applied for the reason it always did: it provides the
// `api`/`implementation` configurations and the `src/main/java` source set the Kotlin tree is
// compiled from, and removing it is a build change with no benefit and real risk.
apply(plugin = "org.jetbrains.kotlin.jvm")

configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(21)
}

// The Android SDK directory, resolved the way AGP resolves it: `local.properties` first, then
// `ANDROID_HOME`/`ANDROID_SDK_ROOT`. `tools/build` exports both env vars, and there is no
// `local.properties` in this checkout, so this is the same `/opt/android-sdk` the Android modules
// use. It exists only for the `compileOnly` stub jar below.
val androidSdk: String = run {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val props = Properties()
        local.inputStream().use { props.load(it) }
        val dir = props.getProperty("sdk.dir")
        if (!dir.isNullOrEmpty()) return@run dir
    }
    System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk"
}

dependencies {
    // `@ColorInt` on `uk.xa0.tulkki.libs.Avatarable`. compileOnly, not api: the annotation is
    // CLASS-retention, so the implementors and the readers resolve the interface without it,
    // and nothing downstream should inherit an androidx edge from this module.
    "compileOnly"("androidx.annotation:annotation:1.9.1")

    // `android.net.Uri` in `uk.xa0.tulkki.libs.AttachmentRef` (2026-10-08, lane `G`). The owner's
    // standing note sanctions this module ceasing to be a bare `java-library`, and the Android-library
    // spelling was not the one taken: converting the whole module to AGP would have given it a
    // namespace, a `compileSdk` and an AAR boundary to buy one platform type in one signature. The
    // stub jar is the narrower change, and it is `compileOnly` because the framework supplies the
    // real `Uri` on the device and AGP already puts a mockable `android.jar` on every consumer's
    // unit-test classpath. The path is resolved the way AGP resolves it - `local.properties` first,
    // then `ANDROID_HOME`/`ANDROID_SDK_ROOT` (which `tools/build` exports) - so this is the same SDK
    // the Android modules compile against, and `android-36` is their `compileSdk`.
    //
    // `:libs` has no test source set of its own (an empty `src/test/java` with a `.gitkeep`), so
    // nothing in this module can call a stub at runtime; `AttachmentRef` is an interface and its
    // one `Uri` is data handed in by `:data`'s `Attachment`, never constructed here.
    "compileOnly"(files("$androidSdk/platforms/android-36/android.jar"))

    // `uk.xa0.tulkki.libs.Jid` (2026-10-08, lane `G`): the XMPP address that left the island, and
    // the jxmpp parsing behind it. `implementation`, not `api`: `Jid`'s own signatures expose only
    // JDK types (`CharSequence`, `String`), so no consumer should inherit a jxmpp edge from here.
    "implementation"("org.jxmpp:jxmpp-jid:1.1.0")
    "implementation"("org.jxmpp:jxmpp-stringprep-libidn:1.1.0")

    // `uk.xa0.tulkki.libs.IP` moved beside it so `:libs` needs nothing of `:xmpp`: it is the
    // IPv4/IPv6 literal test `Jid.ofUserInput` calls, and its one Guava use is
    // `com.google.common.net.InetAddresses.isInetAddress` in `unwrapIPv6`. `implementation` again,
    // and deliberately the **same coordinate every other module uses**: `-jre` would resolve to a
    // second artifact of the same `com.google.guava:guava:33.6.0` module and put two copies of every
    // Guava class on the app's runtime classpath (a dexing failure, not a compile one).
    "implementation"("com.google.guava:guava:33.6.0-android")
}
