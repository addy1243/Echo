package dev.echo.app

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestSearchTest {
    @Test fun cancelledSearchCannotReplaceNewerResultsEvenIfProviderFinishes() = runBlocking {
        var results = "initial"
        lateinit var response: Continuation<String>
        val oldSearch = launch(Dispatchers.Unconfined) {
            publishSearchResult({ suspendCoroutine<String> { response = it } }) { results = it }
        }
        oldSearch.cancel()
        publishSearchResult({ "new results" }) { results = it }
        response.resume("stale results")
        oldSearch.join()
        assertEquals("new results", results)
    }
}
