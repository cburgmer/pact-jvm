package au.com.dius.pact.provider

import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.ProviderState
import au.com.dius.pact.core.support.Json
import au.com.dius.pact.core.support.Result
import au.com.dius.pact.core.support.json.JsonParser
import au.com.dius.pact.core.support.mapEither
import groovy.lang.Closure
import io.github.oshai.kotlinlogging.KLogging
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.core5.http.io.entity.EntityUtils
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

data class StateChangeResult @JvmOverloads constructor (
  val stateChangeResult: Result<Map<String, Any?>, Exception>,
  val message: String = ""
)

interface StateChange {
  fun executeStateChange(
    verifier: IProviderVerifier,
    provider: IProviderInfo,
    consumer: IConsumerInfo,
    interaction: Interaction,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    providerClient: ProviderClient
  ): StateChangeResult

  fun stateChange(
    verifier: IProviderVerifier,
    state: ProviderState,
    provider: IProviderInfo,
    consumer: IConsumerInfo,
    isSetup: Boolean,
    providerClient: ProviderClient
  ): Result<Map<String, Any?>, Exception>

  fun executeStateChangeTeardown(
    verifier: IProviderVerifier,
    interaction: Interaction,
    provider: IProviderInfo,
    consumer: IConsumerInfo,
    providerClient: ProviderClient
  )
}

/**
 * Class containing all the state change logic
 */
object DefaultStateChange : StateChange, KLogging() {

  override fun executeStateChange(
    verifier: IProviderVerifier,
    provider: IProviderInfo,
    consumer: IConsumerInfo,
    interaction: Interaction,
    interactionMessage: String,
    failures: MutableMap<String, Any>,
    providerClient: ProviderClient
  ): StateChangeResult {
    var message = interactionMessage
    var stateChangeResult: Result<Map<String, Any?>, Exception> = Result.Ok(emptyMap())

    if (interaction.providerStates.isNotEmpty()) {
      val iterator = interaction.providerStates.iterator()
      var first = true
      while (stateChangeResult is Result.Ok && iterator.hasNext()) {
        val providerState = iterator.next()
        val result = stateChange(verifier, providerState, provider, consumer, true, providerClient)
        logger.debug { "State Change: \"$providerState\" -> $result" }

        stateChangeResult = result.mapEither({
          if (first) {
            message += " Given ${providerState.name}"
            first = false
          } else {
            message += " And ${providerState.name}"
          }
          stateChangeResult.unwrap().plus(it)
        }, {
          failures[message] = it.message.toString()
          it
        })
      }
    } else {
      val result = stateChange(verifier, ProviderState(""), provider, consumer, true, providerClient)
      logger.debug { "State Change: \"\" -> $result" }
      result.mapEither({
        stateChangeResult.unwrap().plus(it)
      }, {
        failures[message] = it.message.toString()
        it
      })
    }

    return StateChangeResult(stateChangeResult, message)
  }

  @Suppress("TooGenericExceptionCaught", "ReturnCount", "ComplexMethod", "LongParameterList")
  override fun stateChange(
    verifier: IProviderVerifier,
    state: ProviderState,
    provider: IProviderInfo,
    consumer: IConsumerInfo,
    isSetup: Boolean,
    providerClient: ProviderClient
  ): Result<Map<String, Any?>, Exception> {
    verifier.reportStateForInteraction(state.name.toString(), provider, consumer, isSetup)

    logger.debug {
      "stateChangeHandler: consumer.stateChange=${consumer.stateChange}, " +
              "provider.stateChangeUrl=${provider.stateChangeUrl}"
    }
    try {
      var stateChangeHandler = consumer.stateChange
      var stateChangeUsesBody = consumer.stateChangeUsesBody
      if (stateChangeHandler == null) {
        stateChangeHandler = provider.stateChangeUrl
        stateChangeUsesBody = provider.stateChangeUsesBody
      }
      if (stateChangeHandler == null || (stateChangeHandler is String && stateChangeHandler.isBlank())) {
        verifier.reporters.forEach { it.warnStateChangeIgnored(state.name.toString(), provider, consumer) }
        return Result.Ok(emptyMap())
      } else if (verifier.checkBuildSpecificTask.apply(stateChangeHandler)) {
        logger.debug { "Invoking build specific task $stateChangeHandler" }
        verifier.executeBuildSpecificTask.accept(stateChangeHandler, state)
        return Result.Ok(emptyMap())
      } else if (stateChangeHandler is Closure<*>) {
        val result = if (provider.stateChangeTeardown) {
          stateChangeHandler.call(state, if (isSetup) "setup" else "teardown")
        } else {
          stateChangeHandler.call(state)
        }
        logger.debug { "Invoked state change closure -> $result" }
        if (result !is URL) {
          val map = if (result is Map<*, *>) {
            state.params + (result as Map<String, Any?>)
          } else {
            state.params
          }
          return Result.Ok(map)
        }
        stateChangeHandler = result
      }

      val stateChangeResult = executeHttpStateChangeRequest(
        verifier, stateChangeHandler, stateChangeUsesBody, state, provider, isSetup,
        providerClient
      )
      return when (stateChangeResult) {
        is Result.Ok -> {
          Result.Ok(state.params + stateChangeResult.value)
        }
        is Result.Err -> stateChangeResult
      }
    } catch (e: Exception) {
      verifier.reportStateChangeFailed(state, e, isSetup)
      return Result.Err(e)
    }
  }

  override fun executeStateChangeTeardown(
    verifier: IProviderVerifier,
    interaction: Interaction,
    provider: IProviderInfo,
    consumer: IConsumerInfo,
    providerClient: ProviderClient
  ) {
    if (interaction.providerStates.isNotEmpty()) {
      interaction.providerStates.forEach {
        stateChange(verifier, it, provider, consumer, false, providerClient)
      }
    } else {
      stateChange(verifier, ProviderState(""), provider, consumer, false, providerClient)
    }
  }

  private fun executeHttpStateChangeRequest(
    verifier: IProviderVerifier,
    stateChangeHandler: Any,
    useBody: Boolean,
    state: ProviderState,
    provider: IProviderInfo,
    isSetup: Boolean,
    providerClient: ProviderClient
  ): Result<Map<String, Any?>, Exception> {
    return try {
      val url = stateChangeHandler as? URI ?: URI(stateChangeHandler.toString())
      val response = providerClient.makeStateChangeRequest(url, state, useBody, isSetup, provider.stateChangeTeardown)
      logger.debug { "Invoked state change $url -> ${response?.code}" }
      response?.use {
        if (response.code >= 400) {
          verifier.reporters.forEach {
            it.stateChangeRequestFailed(
              state.name.toString(),
              provider,
              isSetup,
              "${response.code} ${response.reasonPhrase}"
            )
          }
          Result.Err(Exception("State Change Request Failed - ${response.code} ${response.reasonPhrase}"))
        } else {
          parseJsonResponse(response.entity)
        }
      } ?: Result.Ok(emptyMap())
    } catch (ex: URISyntaxException) {
      logger.error(ex) { "State change request is not valid" }
      verifier.reporters.forEach {
        it.warnStateChangeIgnoredDueToInvalidUrl(state.name.toString(), provider, isSetup, stateChangeHandler)
      }
      Result.Ok(emptyMap())
    }
  }

  private fun parseJsonResponse(entity: HttpEntity?): Result<Map<String, Any?>, Exception> {
    return if (entity != null) {
      val contentType: ContentType? = ContentType.parse(entity.contentType)
      if (contentType != null && contentType.mimeType == ContentType.APPLICATION_JSON.mimeType) {
        val body = EntityUtils.toString(entity)
          Result.Ok(Json.toMap(JsonParser.parseString(body)))
      } else {
        Result.Ok(emptyMap())
      }
    } else {
      Result.Ok(emptyMap())
    }
  }
}
