-dontobfuscate

# Tulkki: the whole tree we own. One line, because everything is inside it now: the 3.8 rename moved
# the islands (crypto, xml, xmpp, parser) and the im.conversations wire models to uk.xa0.tulkki.*, so
# the lines that used to name them are gone rather than kept. Do not narrow it: seven of the eight
# settings fragments are reached *only* through `app:fragment` in res/xml/preferences_*.xml, an attribute
# R8 does not read, so under a narrower rule they are shrunk and the row dies with
# Fragment.InstantiationException in a release APK.
# See docs/MIGRATION.md, "The rename's leftovers: what the OS holds, and what R8 can see".
-keep class uk.xa0.** { *; }
# S5-1: Room resolves its generated implementation by name - `Class.forName("<package>.<Db>_Impl")`
# - so a shrunk `_Impl` is a crash no test in this repo can see, on a build (`assembleTulkkiRelease`)
# this project never runs. The line above already covers it: the 3.8 rename put every module root,
# `:data` included, under `uk.xa0.tulkki.*`. This rule is pinned anyway, because the whole reason to
# write it down is that it must not depend on the reader noticing that - and
# `RoomKeepRuleTest` fails if this line goes.
-keep class uk.xa0.tulkki.data.**_Impl { *; }
# The extension index reaches its classes by *name*: `ExtensionFactory` reads
# `uk/xa0/tulkki/xmpp/models/extension-index.txt` and calls `Class.forName` on each line's
# `binary-class` field, so R8 sees no reference to walk and a model reached only that way is stripped
# or renamed - the element then stops resolving, at runtime, with nothing to warn you. The
# `-keep class uk.xa0.** { *; }` above already covers every one of them today (and `-dontobfuscate`
# rules out renaming), but that is a property of a much wider rule; this one keeps the *reason*, so
# narrowing `uk.xa0.**` cannot take the index's referents out from under it. **Release is unverified
# here** - this repo builds only `assembleTulkkiDebug`, and debug does not shrink - so the rule is
# reasoned from the source, not measured on a shrunk APK.
-keep @uk.xa0.tulkki.annotation.XmlElement class * { public <init>(); }
# Kept deliberately, and its surviving referent is named so it is not deleted as redundant: the
# third-party webrtc AAR's generated `im.conversations.webrtc.BuildConfig`, whose `WEBRTC_VERSION`
# `MainSettingsFragment` reads and which the `uk.xa0.**` rule above cannot cover. Until 2026-10-08 the
# named referent was also the annotation module, then spelled upstream's way as
# `im.conversations.android.annotation`; that module is ours now (`uk.xa0.tulkki.annotation`, the
# `-keep @...XmlElement` line above), so `uk.xa0.**` covers it and only the third-party half of the
# reason survives. The processor and the `Extensions` class this comment used to name are deleted
# (the index is derived from the compiled classes now).
-keep class im.conversations.** { *; }

-keep class org.whispersystems.** { *; }

-keep class com.kyleduo.switchbutton.Configuration

# Needed for proper GSON deserialization
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

-keep class com.soundcloud.android.crop.** { *; }

-keep class com.google.android.gms.** { *; }

-keep class org.openintents.openpgp.*
-keep class org.webrtc.** { *; }


-keep class net.fellbaum.jemoji.** { *; }
-keeppackagenames net.fellbaum.jemoji.**
-keepdirectories jemoji

-dontwarn javax.mail.internet.MimeMessage
-dontwarn javax.mail.internet.MimeBodyPart
-dontwarn javax.mail.internet.SharedInputStream
-dontwarn javax.activation.DataContentHandler
-dontwarn org.bouncycastle.mail.**
-dontwarn org.bouncycastle.x509.util.LDAPStoreHelper
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.cert.dane.**
-dontwarn rocks.xmpp.addr.**
-dontwarn com.google.firebase.analytics.connector.AnalyticsConnector
-dontwarn java.lang.**
-dontwarn javax.lang.**

-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.jetbrains.annotations.**
# Tulkki: the offline language detector (com.optimaize.languagedetector) depends on slf4j-api and no
# binding ships with it, so R8 finds org.slf4j.impl.StaticLoggerBinder referenced and absent. Nothing
# in the app reads that library's logs and slf4j falls back to its NOP logger at runtime, so the
# reference is deliberately not followed up with a binding jar. Do not delete this as noise.
-dontwarn org.slf4j.**
# Tulkki: opennlp-tools (the maximum-entropy classifier behind LanguageCheck) carries an OSGi
# integration whose classes cannot exist on Android, and R8 refuses to compile the reference unless it
# is told the absence is expected. -dontwarn is the correct instrument and a keep rule is not: a keep
# rule cannot conjure a class that is not on the classpath - what is missing is the OSGi container
# itself, not something shrinking would remove. The reference lives in
# opennlp.tools.util.ext.ExtensionLoader.instantiateExtension, which probes
# OSGiExtensionLoader only behind its own static `isOsgiAvailable`; that flag is set only by
# OSGiExtensionLoader.start(BundleContext), i.e. only by an OSGi framework, which Android does not
# have. The one path Tulkki takes - OpenNlpReading.reader() -> new LanguageDetectorME(model) ->
# BaseModel.initializeFactory() -> BaseToolFactory.create(String, ...) - reaches the method with the
# factory name the bundled model's own manifest supplies
# (`factory=opennlp.tools.langdetect.LanguageDetectorFactory`, langdetect-183.bin), so Class.forName and
# newInstance succeed and the failure fallback that leads to the OSGi probe is never entered. Honest
# cost, stated: this turns a compile-time refusal into a NoClassDefFoundError *if* that OSGi path is
# ever reached, which it cannot be, because reaching it requires the OSGi framework that would have to
# have loaded this class in the first place. Scope is the whole `org.osgi` namespace rather than the one
# class AGP named (`org.osgi.framework.BundleActivator`): opennlp-tools names seven OSGi classes across
# two packages (BundleActivator, BundleContext, Filter, FrameworkUtil, InvalidSyntaxException and the
# util.tracker ServiceTracker pair) and reaches all of them through the same dead branch, so the
# narrower rule is incomplete for this dependency and would buy a second 4-10 minute validation run.
# Nothing in this app or its dependencies means anything else by `org.osgi`, so the wider scope cannot
# hide a genuinely missing class.
-dontwarn org.osgi.**

-keepclassmembers class uk.xa0.tulkki.app.http.services.** {
  !transient <fields>;
}

# Retrofit does reflection on generic parameters. InnerClasses is required to use Signature and
# EnclosingMethod is required to use InnerClasses.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Retrofit does reflection on method and parameter annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Retain service method parameters when optimizing.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Ignore annotation used for build tooling.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Ignore JSR 305 annotations for embedding nullability information.
-dontwarn javax.annotation.**

# Guarded by a NoClassDefFoundError try/catch and only used when on the classpath.
-dontwarn kotlin.Unit

# Top-level functions that can only be used by Kotlin.
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*


# With R8 full mode, it sees no subtypes of Retrofit interfaces since they are created with a Proxy
# and replaces all potential values with null. Explicitly keeping the interfaces prevents this.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# Keep inherited services.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# R8 full mode strips generic signatures from return types if not kept.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

-keepattributes *Annotation*,SourceFile,LineNumberTable
