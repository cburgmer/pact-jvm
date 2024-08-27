package au.com.dius.pact.provider.spring.junit5

import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.PactSource
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationExtension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder

open class PactVerificationSpringExtension(
  pact: Pact,
  pactSource: PactSource,
  interaction: Interaction,
  serviceName: String,
  consumerName: String?
) : PactVerificationExtension(pact, pactSource, interaction, serviceName, consumerName) {
  constructor(context: PactVerificationExtension) : this(context.pact, context.pactSource, context.interaction,
    context.serviceName, context.consumerName)

  override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
    val store = extensionContext.getStore(ExtensionContext.Namespace.create("pact-jvm"))
    val testContext = store.get("interactionContext") as PactVerificationContext
    val target = testContext.currentTarget()
    return when (parameterContext.parameter.type) {
      MockHttpServletRequestBuilder::class.java -> target is MockMvcTestTarget
      WebTestClient.RequestHeadersSpec::class.java -> target is WebFluxTarget
      else -> super.supportsParameter(parameterContext, extensionContext)
    }
  }

  override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any? {
    val store = extensionContext.getStore(ExtensionContext.Namespace.create("pact-jvm"))
    return when (parameterContext.parameter.type) {
      MockHttpServletRequestBuilder::class.java -> store.get("request")
      WebTestClient.RequestHeadersSpec::class.java -> store.get("request")
      else -> super.resolveParameter(parameterContext, extensionContext)
    }
  }
}
