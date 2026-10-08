package uk.xa0.tulkki.ui

import org.junit.Assert
import org.junit.Test

/**
 * The one part of the top-up screen a JVM can judge.
 *
 * The WebView itself is not tested here and cannot be: its rendering, its cookie jar, its dark mode
 * and its intent handoff are only observable on a device, and a real payment cannot be driven at all.
 * What is pinned here is what those behaviours are decided by - whether an address belongs inside the
 * WebView or to another app, and what the WebView calls itself when it fetches the page.
 */
class TopUpPageTest {

    @Test
    fun httpAndHttpsStayInTheWebView() {
        Assert.assertEquals(TopUpPage.Destination.WEBVIEW, TopUpPage.destinationOf(TopUpPage.URL))
        Assert.assertEquals(
            TopUpPage.Destination.WEBVIEW,
            TopUpPage.destinationOf("http://platform.deepseek.com/top_up"),
        )
        Assert.assertEquals(
            TopUpPage.Destination.WEBVIEW,
            TopUpPage.destinationOf("https://platform.deepseek.com/top_up#amount=50"),
        )
        Assert.assertEquals(
            TopUpPage.Destination.WEBVIEW,
            TopUpPage.destinationOf("HTTPS://PLATFORM.DEEPSEEK.COM/TOP_UP"),
        )
    }

    @Test
    fun thePaymentSchemesAreHandedToTheSystem() {
        val paymentAddresses =
            listOf(
                "alipay://platformapi/startapp?appId=20000056",
                "weixin://wap/pay?prepayid=abc",
                "intent://pay.example/#Intent;scheme=pay;package=com.example.pay;end",
                "market://details?id=com.eg.android.AlipayGphone",
                "tel:+358401234567",
                "mailto:support@deepseek.com",
            )
        for (address in paymentAddresses) {
            Assert.assertEquals(
                "the system should get $address",
                TopUpPage.Destination.SYSTEM,
                TopUpPage.destinationOf(address),
            )
        }
    }

    @Test
    fun anAppLinkWithASchemeOfItsOwnGoesOut() {
        // Not one of the names in the brief and still not the WebView's business: an app link is a
        // scheme somebody's app answers, whatever it is called.
        Assert.assertEquals(
            TopUpPage.Destination.SYSTEM,
            TopUpPage.destinationOf("myapp://open?order=7"),
        )
        Assert.assertEquals(
            TopUpPage.Destination.SYSTEM,
            TopUpPage.destinationOf("wechatpay://pay?id=1"),
        )
        Assert.assertEquals(TopUpPage.Destination.SYSTEM, TopUpPage.destinationOf("sms:+358401234567"))
    }

    @Test
    fun anUnknownSchemeGoesOutRatherThanCrashing() {
        // The decision does not need to know the handler: nobody does. The caller tries the system
        // and reports it when nothing answers, which is the only honest outcome.
        Assert.assertEquals(
            TopUpPage.Destination.SYSTEM,
            TopUpPage.destinationOf("nosuchscheme://pay"),
        )
        Assert.assertEquals(TopUpPage.Destination.SYSTEM, TopUpPage.destinationOf("zzz:whatever"))
    }

    @Test
    fun theSchemesOnlyTheWebViewCanRenderStay() {
        // Handing these out would be meaningless at best and a local-file leak at worst; file: and
        // content: access are switched off, so the WebView is where they get refused.
        val webViewAddresses =
            listOf(
                "about:blank",
                "javascript:void(0)",
                "data:text/html,<p>hi</p>",
                "blob:https://platform.deepseek.com/1234",
                "file:///etc/passwd",
                "content://com.example.provider/x",
                "chrome://crash",
            )
        for (address in webViewAddresses) {
            Assert.assertEquals(
                "the WebView should keep $address",
                TopUpPage.Destination.WEBVIEW,
                TopUpPage.destinationOf(address),
            )
        }
    }

    @Test
    fun anAddressWithNoSchemeStays() {
        // A relative reference is the WebView's to resolve, and a colon that is not a scheme - a port
        // in a path, a fragment - must not turn a relative address into an intent for another app.
        Assert.assertEquals(TopUpPage.Destination.WEBVIEW, TopUpPage.destinationOf("/top_up"))
        Assert.assertEquals(
            TopUpPage.Destination.WEBVIEW,
            TopUpPage.destinationOf("//platform.deepseek.com/top_up"),
        )
        Assert.assertEquals(
            TopUpPage.Destination.WEBVIEW,
            TopUpPage.destinationOf("platform.deepseek.com/top_up:8080"),
        )
        Assert.assertEquals(TopUpPage.Destination.WEBVIEW, TopUpPage.destinationOf("http"))
    }

    @Test
    fun garbageIsNotACrash() {
        for (nothingMuch in listOf(null, "", "   ", ":", ":::", "not a url")) {
            val destination = TopUpPage.destinationOf(nothingMuch)
            Assert.assertNotNull(destination)
            Assert.assertEquals(
                "nothing to hand out in <$nothingMuch>",
                TopUpPage.Destination.WEBVIEW,
                destination,
            )
        }
    }

    @Test
    fun theTopUpPageIsThePlatformsOwn() {
        // The address the screen opens, pinned: the API host is a different one and the key is no
        // part of this.
        Assert.assertEquals("https://platform.deepseek.com/top_up", TopUpPage.URL)
        Assert.assertEquals(TopUpPage.Destination.WEBVIEW, TopUpPage.destinationOf(TopUpPage.URL))
        Assert.assertTrue(TopUpPage.URL.startsWith("https://"))
    }

    @Test
    fun theWebViewTokensAreTakenOutOfTheUserAgent() {
        val userAgent = TopUpPage.browserUserAgent(WEBVIEW_USER_AGENT)
        Assert.assertNotNull(userAgent)
        val agent = userAgent!!
        Assert.assertFalse("the wv marker survives: $agent", agent.contains("; wv"))
        Assert.assertFalse("the Version/4.0 token survives: $agent", agent.contains("Version/4.0"))
        Assert.assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004)" +
                " AppleWebKit/537.36 (KHTML, like Gecko)" +
                " Chrome/121.0.6167.143 Mobile Safari/537.36",
            agent,
        )
    }

    @Test
    fun theRewrittenUserAgentIsStillThisDeviceAndThisEngine() {
        // The point of rewriting rather than substituting a canned string: the site is still told the
        // truth about the platform and the engine, only not that the client is a WebView.
        val userAgent = TopUpPage.browserUserAgent(WEBVIEW_USER_AGENT)
        Assert.assertNotNull(userAgent)
        val agent = userAgent!!
        Assert.assertTrue(agent.contains("(Linux; Android 14; Pixel 7 Build/UQ1A.240205.004)"))
        Assert.assertTrue(agent.contains("AppleWebKit/537.36 (KHTML, like Gecko)"))
        Assert.assertTrue(agent.contains("Chrome/121.0.6167.143"))
        Assert.assertTrue(agent.contains("Mobile Safari/537.36"))
        Assert.assertFalse("spaces were not collapsed: $agent", agent.contains("  "))
    }

    @Test
    fun anOrdinaryUserAgentIsLeftAlone() {
        val chrome =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko)" +
                " Chrome/121.0.6167.143 Mobile Safari/537.36"
        Assert.assertEquals(chrome, TopUpPage.browserUserAgent(chrome))
    }

    @Test
    fun aMissingUserAgentStaysMissingRatherThanBecomingEmpty() {
        // null means "keep whatever the WebView defaults to"; "" would be sent as an empty user agent,
        // which is worse than either.
        Assert.assertNull(TopUpPage.browserUserAgent(null))
        Assert.assertEquals("", TopUpPage.browserUserAgent(""))
    }

    @Test
    fun onlyTheMarkerTokensAreRewritten() {
        Assert.assertEquals("A B C", TopUpPage.browserUserAgent("A; wv B Version/4.0 C"))
        Assert.assertEquals("A B", TopUpPage.browserUserAgent("A  B"))
        Assert.assertEquals("A B", TopUpPage.browserUserAgent("A B "))
    }

    private companion object {
        /**
         * A default Android WebView user agent, in the shape the platform builds it: the `; wv`
         * marker and the `Version/4.0` product token are the two things that say "embedded
         * WebView", and a site that refuses WebViews refuses on one of them.
         */
        const val WEBVIEW_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240205.004; wv)" +
                " AppleWebKit/537.36 (KHTML, like Gecko)" +
                " Version/4.0 Chrome/121.0.6167.143 Mobile Safari/537.36"
    }
}
