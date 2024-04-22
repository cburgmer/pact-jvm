package au.com.dius.pact.provider

import au.com.dius.pact.core.matchers.BodyMismatch
import au.com.dius.pact.core.matchers.BodyTypeMismatch
import au.com.dius.pact.core.matchers.HeaderMismatch
import au.com.dius.pact.core.matchers.Matching
import au.com.dius.pact.core.matchers.MatchingConfig
import au.com.dius.pact.core.matchers.MatchingContext
import au.com.dius.pact.core.matchers.MetadataMismatch
import au.com.dius.pact.core.matchers.Mismatch
import au.com.dius.pact.core.matchers.ResponseMatching
import au.com.dius.pact.core.matchers.StatusMismatch
import au.com.dius.pact.core.matchers.generateDiff
import au.com.dius.pact.core.model.ContentType
import au.com.dius.pact.core.model.IResponse
import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.Response
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.isNullOrEmpty
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.core.model.messaging.MessageInteraction
import au.com.dius.pact.core.model.orEmpty
import au.com.dius.pact.core.model.orEmptyBody
import au.com.dius.pact.core.support.Json
import au.com.dius.pact.core.support.Result
import au.com.dius.pact.core.support.Utils.sizeOf
import au.com.dius.pact.core.support.expressions.SystemPropertyResolver
import au.com.dius.pact.core.support.expressions.ValueResolver
import au.com.dius.pact.core.support.isNotEmpty
import au.com.dius.pact.core.support.jsonObject
import io.pact.plugins.jvm.core.PluginConfiguration
import io.github.oshai.kotlinlogging.KLogging
import java.lang.Integer.max

data class BodyComparisonResult(
  val mismatches: Map<String, List<BodyMismatch>> = emptyMap(),
  val diff: List<String> = emptyList()
) {
  fun toJson() = jsonObject(
    "mismatches" to Json.toJson(mismatches.mapValues { entry -> entry.value.map { it.description() } }),
    "diff" to diff.joinToString("\n")
  )
}

data class ComparisonResult(
  val statusMismatch: StatusMismatch? = null,
  val headerMismatches: Map<String, List<HeaderMismatch>> = emptyMap(),
  val bodyMismatches: Result<BodyComparisonResult, BodyTypeMismatch> = Result.Ok(BodyComparisonResult()),
  val metadataMismatches: Map<String, List<MetadataMismatch>> = emptyMap()
)

/**
 * Interface to the utility class that provides the logic to compare responses
 */
interface IResponseComparison {
  @Deprecated("Use version that takes pluginConfiguration parameter")
  fun compareResponse(
    response: IResponse,
    actualResponse: ProviderResponse
  ): ComparisonResult

  fun compareResponse(
    response: IResponse,
    actualResponse: ProviderResponse,
    pluginConfiguration: Map<String, PluginConfiguration>
  ): ComparisonResult

  @Deprecated("Use version that takes pluginConfiguration parameter")
  fun compareMessage(
    message: MessageInteraction,
    actual: OptionalBody
  ): ComparisonResult

  @Deprecated("Use version that takes pluginConfiguration parameter")
  fun compareMessage(
    message: MessageInteraction,
    actual: OptionalBody,
    metadata: Map<String, Any>?
  ): ComparisonResult

  fun compareMessage(
    message: MessageInteraction,
    actual: OptionalBody,
    metadata: Map<String, Any>?,
    pluginConfiguration: Map<String, PluginConfiguration>
  ): ComparisonResult

  fun compareSynchronousMessage(
    interaction: V4Interaction.SynchronousMessages,
    body: OptionalBody,
    messageMetadata: Map<String, Any>?,
    pluginConfiguration: Map<String, PluginConfiguration>
  ): ComparisonResult
}

/**
 * Utility class to compare responses
 */
class ResponseComparison(
  private val expectedHeaders: Map<String, List<String>>,
  private val expectedBody: OptionalBody,
  private val isJsonBody: Boolean,
  private val actualResponseContentType: ContentType,
  private val actualBody: OptionalBody?
) {

  fun statusResult(mismatches: List<Mismatch>) = mismatches.filterIsInstance<StatusMismatch>().firstOrNull()

  fun headerResult(mismatches: List<Mismatch>): Map<String, List<HeaderMismatch>> {
    val headerMismatchers = mismatches.filterIsInstance<HeaderMismatch>()
      .groupBy { it.headerKey }
    return if (headerMismatchers.isEmpty()) {
      emptyMap()
    } else {
      expectedHeaders.entries.associate { (headerKey, _) ->
        headerKey to headerMismatchers[headerKey].orEmpty()
      }
    }
  }

  fun bodyResult(
    mismatches: List<Mismatch>,
    resolver: ValueResolver
  ): Result<BodyComparisonResult, BodyTypeMismatch> {
    val bodyTypeMismatch = mismatches.filterIsInstance<BodyTypeMismatch>().firstOrNull()
    return if (bodyTypeMismatch != null) {
      Result.Err(bodyTypeMismatch)
    } else {
      val bodyMismatches = mismatches
        .filterIsInstance<BodyMismatch>()
        .groupBy { bm -> bm.path }

      val contentType = this.actualResponseContentType
      val expected = expectedBody.valueAsString()
      val actual = actualBody.orEmpty()
      val diff = when (val shouldIncludeDiff = shouldGenerateDiff(resolver, max(actual.size, expected.length))) {
        is Result.Ok -> if (shouldIncludeDiff.value) {
          generateFullDiff(actual.toString(contentType.asCharset()), contentType, expected, isJsonBody)
        } else {
          emptyList()
        }
        is Result.Err -> {
          logger.warn { "Invalid value for property 'pact.verifier.generateDiff' - ${shouldIncludeDiff.error}" }
          emptyList()
        }
      }
      Result.Ok(BodyComparisonResult(bodyMismatches, diff))
    }
  }

  companion object : KLogging(), IResponseComparison {
    private fun generateFullDiff(
      actual: String,
      contentType: ContentType,
      response: String,
      jsonBody: Boolean
    ): List<String> {
      var actualBodyString = ""
      if (actual.isNotEmpty()) {
        actualBodyString = if (contentType.isJson()) {
          Json.prettyPrint(actual)
        } else {
          actual
        }
      }

      var expectedBodyString = ""
      if (response.isNotEmpty()) {
        expectedBodyString = if (jsonBody) {
          Json.prettyPrint(response)
        } else {
          response
        }
      }

      return generateDiff(expectedBodyString, actualBodyString)
    }

    @JvmStatic
    fun shouldGenerateDiff(resolver: ValueResolver, length: Int): Result<Boolean, String> {
      val shouldIncludeDiff = resolver.resolveValue("pact.verifier.generateDiff", "NOT_SET")
      return when (val v = shouldIncludeDiff?.lowercase()) {
        "true", "not_set" -> Result.Ok(true)
        "false" -> Result.Ok(false)
        else -> if (v.isNotEmpty()) {
          when (val result = sizeOf(v!!)) {
            is Result.Ok -> Result.Ok(length <= result.value)
            is Result.Err -> result
          }
        } else {
          Result.Ok(false)
        }
      }
    }

    @Deprecated("Use version that takes pluginConfiguration parameter", ReplaceWith(
      "compareResponse(response, actualResponse, emptyMap())",
      "au.com.dius.pact.provider.ResponseComparison.Companion.compareResponse")
    )
    override fun compareResponse(
      response: IResponse,
      actualResponse: ProviderResponse
    ) = compareResponse(response, actualResponse, emptyMap())

    override fun compareResponse(
      response: IResponse,
      actualResponse: ProviderResponse,
      pluginConfiguration: Map<String, PluginConfiguration>
    ): ComparisonResult {
      val actualResponseContentType = actualResponse.contentType
      val comparison = ResponseComparison(response.headers, response.body, response.asHttpPart().jsonBody(),
        actualResponseContentType, actualResponse.body)
      val mismatches = ResponseMatching.responseMismatches(response, Response(actualResponse.statusCode ?: 200,
        actualResponse.headers?.toMutableMap() ?: mutableMapOf(),
        actualResponse.body.orEmptyBody()), pluginConfiguration)
      return ComparisonResult(comparison.statusResult(mismatches), comparison.headerResult(mismatches),
        comparison.bodyResult(mismatches, SystemPropertyResolver))
    }


    @Deprecated("Use version that takes pluginConfiguration parameter", ReplaceWith(
      "compareMessage(message, actual, null, pluginConfiguration)",
      "au.com.dius.pact.provider.ResponseComparison.Companion.compareMessage")
    )
    override fun compareMessage(message: MessageInteraction, actual: OptionalBody) =
      compareMessage(message, actual, null, emptyMap())

    @Deprecated("Use version that takes pluginConfiguration parameter", ReplaceWith(
      "compareMessage(message, actual, metadata, pluginConfiguration)",
      "au.com.dius.pact.provider.ResponseComparison.Companion.compareMessage")
    )
    override fun compareMessage(message: MessageInteraction, actual: OptionalBody, metadata: Map<String, Any>?) =
      compareMessage(message, actual, metadata, emptyMap())

    override fun compareMessage(
      message: MessageInteraction,
      actual: OptionalBody,
      metadata: Map<String, Any>?,
      pluginConfiguration: Map<String, PluginConfiguration>
    ): ComparisonResult {
      val (bodyMismatches, metadataMismatches) = when (message) {
        is V4Interaction.AsynchronousMessage -> {
          val bodyContext = MatchingContext(
            message.contents.matchingRules.rulesForCategory("content")
              .orElse(message.contents.matchingRules.rulesForCategory("body")),
            true, pluginConfiguration)
          val metadataContext = MatchingContext(message.contents.matchingRules.rulesForCategory("metadata"),
            true, pluginConfiguration)
          val bodyMismatches = compareMessageBody(message, actual, bodyContext)
          val metadataMismatches = when (metadata) {
            null -> emptyList()
            else -> Matching.compareMessageMetadata(message.contents.metadata, metadata, metadataContext)
          }
          val messageContentType = message.contentType.or(ContentType.TEXT_PLAIN)
          val responseComparison = ResponseComparison(
            mapOf("Content-Type" to listOf(messageContentType.toString())), message.contents.contents,
            messageContentType.isJson(), messageContentType, actual)
          responseComparison.bodyResult(bodyMismatches, SystemPropertyResolver) to metadataMismatches
        }

        is Message -> {
          val bodyContext = MatchingContext(message.matchingRules.rulesForCategory("content")
            .orElse(message.matchingRules.rulesForCategory("body")),
            true, pluginConfiguration)
          val metadataContext = MatchingContext(message.matchingRules.rulesForCategory("metadata"),
            true, pluginConfiguration)
          val bodyMismatches = compareMessageBody(message, actual, bodyContext)
          val metadataMismatches = when (metadata) {
            null -> emptyList()
            else -> Matching.compareMessageMetadata(message.metadata, metadata, metadataContext)
          }
          val messageContentType = message.contentType.or(ContentType.TEXT_PLAIN)
          val responseComparison = ResponseComparison(
            mapOf("Content-Type" to listOf(messageContentType.toString())), message.contents,
            messageContentType.isJson(), messageContentType, actual)
          responseComparison.bodyResult(bodyMismatches, SystemPropertyResolver) to metadataMismatches
        }
        else -> TODO("Matching a ${message.javaClass.simpleName} is not implemented")
      }

      return ComparisonResult(bodyMismatches = bodyMismatches,
        metadataMismatches = metadataMismatches.groupBy { it.key })
    }

    override fun compareSynchronousMessage(
      interaction: V4Interaction.SynchronousMessages,
      body: OptionalBody,
      messageMetadata: Map<String, Any>?,
      pluginConfiguration: Map<String, PluginConfiguration>
    ): ComparisonResult {
      if (interaction.response.size > 1) {
        logger.warn {
          "Messages with multiple responses are not currently supported, will only compare the first one"
        }
      }
      val messageContents = interaction.response.first()
      val bodyContext = MatchingContext(messageContents.matchingRules.rulesForCategory("body"),
        true, pluginConfiguration)
      val metadataContext = MatchingContext(messageContents.matchingRules.rulesForCategory("metadata"),
        true, pluginConfiguration)
      val bodyMismatches = compareMessageBody(interaction, body, bodyContext)
      val metadataMismatches = when (messageMetadata) {
        null -> emptyList()
        else -> Matching.compareMessageMetadata(messageContents.metadata, messageMetadata, metadataContext)
      }
      val messageContentType = messageContents.getContentType().or(ContentType.TEXT_PLAIN)
      val responseComparison = ResponseComparison(
        mapOf("Content-Type" to listOf(messageContentType.toString())), messageContents.contents,
        messageContentType.isJson(), messageContentType, body)
      val bodyResult = responseComparison.bodyResult(bodyMismatches, SystemPropertyResolver)
      return ComparisonResult(bodyMismatches = bodyResult,
        metadataMismatches = metadataMismatches.groupBy { it.key })
    }

    @JvmStatic
    fun compareMessageBody(
      message: Interaction,
      actual: OptionalBody,
      context: MatchingContext
    ): MutableList<BodyMismatch> {
      val (contents, contentType) = when (message) {
        is V4Interaction.AsynchronousMessage -> message.contents.contents to message.contents.getContentType()
        is V4Interaction.SynchronousMessages -> {
          val messageContents = message.response.first()
          messageContents.contents to messageContents.getContentType()
        }
        is Message -> message.contents to message.contentType
        else -> TODO("Matching a ${message.javaClass.simpleName} is not implemented")
      }
      val result = MatchingConfig.lookupContentMatcher(contentType.getBaseType())
      var bodyMismatches = mutableListOf<BodyMismatch>()
      if (result != null) {
        bodyMismatches = result.matchBody(contents, actual, context)
          .bodyResults.flatMap { it.result }.toMutableList()
      } else {
        val expectedBody = contents.valueAsString()
        if (expectedBody.isNotEmpty() && actual.isNullOrEmpty()) {
          bodyMismatches.add(BodyMismatch(expectedBody, null, "Expected body '$expectedBody' but was missing"))
        } else if (expectedBody.isNotEmpty() && actual.valueAsString() != expectedBody) {
          bodyMismatches.add(BodyMismatch(expectedBody, actual.valueAsString(),
            "Actual body '${actual.valueAsString()}' is not equal to the expected body '$expectedBody'"))
        }
      }
      return bodyMismatches
    }
  }
}
