package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackModelsTest {
    private fun row(height: Int=720, codec: String="h264", url: String="https://example.com/video") = JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)
    private fun model(vararg rows: JSONObject)=JSONObject().put("video_list",JSONArray(rows.toList()))
    private fun repository(respond: () -> String) = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(respond().toResponseBody()).build()
    }.build())

    @Test fun arrayAndObjectListsKeepQualityLimits() {
        assertEquals("480P",PlaybackModels.pick(PlaybackModels.candidates(model(row(1080),row(480))),720).quality)
        val objectModel=JSONObject().put("video_list",JSONObject().put("hd",row(720)).put("fhd",row(1080)))
        assertEquals("720P",PlaybackModels.pick(PlaybackModels.candidates(objectModel),720).quality)
    }
    @Test fun badCodecKeyAndUrlAreIsolatedPerCandidate() {
        val invalidKey=row(1080).put("spade_a","broken")
        val nestedCodec=row(1080).put("video_meta",JSONObject().put("codec_type","bvc2"))
        val rows=PlaybackModels.candidates(model(row(1080,"ByteVC2"),nestedCodec,invalidKey,row(1080,url="file:///tmp/a"),row(480)))
        assertEquals(1,rows.size)
        assertEquals("480P",PlaybackModels.pick(rows,1080).quality)
    }
    @Test fun badPrimaryUsesValidBackupAndRejectsCredentials() {
        val rows=PlaybackModels.candidates(model(row(url="https://user:password@example.com/video").put("backup_url_1","https://example.com/backup")))
        assertEquals("https://example.com/backup",rows.single().url)
    }
    @Test fun plainBase64UrlAndEscapedUrlAreAccepted() {
        val encoded="https://example.com/base64".toByteArray().base64()
        assertEquals("https://example.com/base64",PlaybackModels.candidates(model(row(url=encoded))).single().url)
        assertEquals("https://example.com/video",PlaybackModels.candidates(model(row(url="https:\\/\\/example.com\\/video"))).single().url)
    }
    @Test fun dimensionlessDescriptionAndPortraitDimensionsKeepQuality() {
        val description=row(0).put("quality_desc","720P")
        val portrait=row(1920).put("vwidth",1080)
        assertEquals("720P",PlaybackModels.pick(PlaybackModels.candidates(model(description,portrait)),720).quality)
        assertEquals("1080P",PlaybackModels.pick(PlaybackModels.candidates(model(portrait)),1080).quality)
    }
    @Test fun unknownQualityDoesNotDisplaceKnownLimitedVariant() {
        assertEquals("480P",PlaybackModels.pick(PlaybackModels.candidates(model(row(0),row(480))),720).quality)
        assertEquals("自动",PlaybackModels.pick(PlaybackModels.candidates(model(row(0))),720).quality)
    }
    @Test fun wrongEpisodeIsNeverSilentlySelected() {
        val data=JSONObject().put("456",JSONObject().put("video_model",model(row())))
        assertNull(PlaybackModels.model(data,"123"))
        data.put("123",JSONObject().put("video_model",model(row()).toString()))
        assertNotNull(PlaybackModels.model(data,"123"))
    }
    @Test fun fallbackSupportsLegacyShapesButNotUnsafeSchemes() {
        for(value in listOf<Any>("https://example.com/f",JSONObject().put("fallback_api","https://example.com/f"),JSONArray().put("https://example.com/f"))) {
            assertEquals("https://example.com/f",PlaybackModels.fallback(JSONObject().put("fallback_api",value)))
        }
        assertNull(PlaybackModels.fallback(JSONObject().put("fallback_api","http://example.com/f")))
    }
    @Test fun nonzeroBusinessCodeIsStructuredAndMakesOnlyOneRequest() {
        var calls=0
        val repo=repository { calls++; """{"code":110001,"data":{}}""" }
        val error=assertThrows(PlaybackSourceException::class.java) { repo.currentStream("123") }
        assertEquals(110001,error.upstreamCode)
        assertEquals(PlaybackSourceException.Reason.REJECTED,error.reason)
        assertEquals(1,calls)
    }
    @Test fun directModelDoesNotNeedFallbackRequest() {
        var calls=0
        val data=JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",model(row()))))
        val repo=repository { calls++; data.toString() }
        assertEquals("720P",repo.currentStream("123").quality)
        assertEquals(1,calls)
    }
    @Test fun cancelledScopeNeverStartsRequest() {
        var calls=0
        val repo=repository { calls++; throw AssertionError("must not run") }
        val scope=RequestScope(); scope.cancel()
        assertThrows(IOException::class.java) { scope.run { repo.currentStream("123") } }
        assertEquals(0,calls)
    }
    @Test fun transportCancellationIsNotConvertedToSourceFailure() {
        val original=IOException("Canceled")
        val repo=repository { throw original }
        assertSame(original,assertThrows(IOException::class.java) { repo.currentStream("123") })
    }
    @Test fun httpRejectionIsPermanentButThrottlingAndServerFailuresCanRetry() {
        for(code in listOf(401,403,404,408,429,503)) {
            val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("response").body("{}".toResponseBody()).build()
            }.build())
            val error=assertThrows(IOException::class.java) { repo.currentStream("123") }
            assertEquals("HTTP $code",code in listOf(401,403,404),error is PlaybackSourceException)
        }
    }
    @Test fun malformedPayloadDoesNotBecomeTransportRetry() {
        val error=assertThrows(PlaybackSourceException::class.java) { repository { "not json" }.currentStream("123") }
        assertEquals(PlaybackSourceException.Reason.INVALID_RESPONSE,error.reason)
    }

    @Test fun nestedEncryptedVariantWithoutKeyIsNeverTreatedAsClearVideo() {
        val encrypted=row().put("encrypt_info",JSONObject().put("encrypt",true))
        assertTrue(PlaybackModels.candidates(model(encrypted)).isEmpty())
    }
    @Test fun nestedVideoMetadataPreservesQuality() {
        val variant=row(0).put("video_meta",JSONObject().put("vwidth",720).put("vheight",1280).put("definition","720p"))
            .put("encrypt_info",JSONObject().put("encrypt",false))
        assertEquals("720P",PlaybackModels.pick(PlaybackModels.candidates(model(variant)),720).quality)
    }

    @Test fun currentGetEndpointUnwrapsOnlyRequestedEpisode() {
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET",chain.request().method)
            assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/",chain.request().url.encodedPath)
            assertEquals("123",chain.request().url.queryParameter("video_id"))
            val body=JSONObject().put("code",0).put("data",JSONObject().put("video_model_data",
                JSONObject().put("123",JSONObject().put("video_model",model(row())))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toString().toResponseBody()).build()
        }.build())
        assertEquals("720P",repo.currentStream("123").quality)
    }

}
