package au.com.dius.pact.provider.junit5

import au.com.dius.pact.core.model.DirectorySource
import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.core.model.PactBrokerSource
import au.com.dius.pact.core.model.PactSource
import au.com.dius.pact.core.model.SynchronousRequestResponse
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.generators.GeneratorTestMode
import au.com.dius.pact.core.model.messaging.MessageInteraction
import au.com.dius.pact.provider.ConsumerInfo
import au.com.dius.pact.provider.HttpClientFactory
import au.com.dius.pact.provider.IHttpClientFactory
import au.com.dius.pact.provider.IProviderInfo
import au.com.dius.pact.provider.IProviderVerifier
import au.com.dius.pact.provider.PactVerification
import au.com.dius.pact.provider.ProviderClient
import au.com.dius.pact.provider.ProviderInfo
import au.com.dius.pact.provider.ProviderResponse
import org.apache.hc.client5.http.classic.methods.HttpUriRequest
import java.net.URL
import java.net.URLClassLoader
import java.util.function.Function
import java.util.function.Supplier

/**
 * Interface to a test target
 */
interface TestTarget {
  /**
   * Any user provided configuration
   */
  val userConfig: Map<String, Any?>

  /**
   * Returns information about the provider
   */
  fun getProviderInfo(serviceName: String, pactSource: PactSource? = null): IProviderInfo

  /**
   * Prepares the request for the interaction.
   *
   * @return a pair of the client class and request to use for the test, or null if there is none
   */
  fun prepareRequest(pact: Pact, interaction: Interaction, context: MutableMap<String, Any>): Pair<Any, Any?>?

  /**
   * If this is a request response (HTTP or HTTPS) target
   */
  fun isHttpTarget(): Boolean

  /**
   * Executes the test (using the client and request from prepareRequest, if any)
   *
   * @return Map of failures, or an empty map if there were not any
   */
  fun executeInteraction(client: Any?, request: Any?): ProviderResponse

  /**
   * Prepares the verifier for use during the test
   */
  fun prepareVerifier(verifier: IProviderVerifier, testInstance: Any, pact: Pact)

  /**
   * If the test target supports the given interaction
   */
  fun supportsInteraction(interaction: Interaction): Boolean = false
}

/**
 * Test target for HTTP tests. This is the default target.
 *
 * @property host Host to bind to. Defaults to localhost.
 * @property port Port that the provider is running on. Defaults to 8080.
 * @property path The path that the provider is mounted on. Defaults to the root path.
 */
open class HttpTestTarget @JvmOverloads constructor (
  val host: String = "localhost",
  val port: Int = 8080,
  val path: String = "/",
  val httpClientFactory: () -> IHttpClientFactory = { HttpClientFactory() }
) : TestTarget {
  override fun isHttpTarget() = true

  override val userConfig: Map<String, Any?> = emptyMap()

  override fun getProviderInfo(serviceName: String, pactSource: PactSource?): IProviderInfo {
    val providerInfo = ProviderInfo(serviceName)
    providerInfo.port = port
    providerInfo.host = host
    providerInfo.protocol = "http"
    providerInfo.path = path
    return providerInfo
  }

  override fun prepareRequest(pact: Pact, interaction: Interaction, context: MutableMap<String, Any>): Pair<Any, Any>? {
    val providerClient = ProviderClient(getProviderInfo("provider"), this.httpClientFactory.invoke())
    if (interaction is SynchronousRequestResponse) {
      val request = interaction.request.generatedRequest(context, GeneratorTestMode.Provider)
      return providerClient.prepareRequest(request) to providerClient
    }
    throw UnsupportedOperationException("Only request/response interactions can be used with an HTTP test target")
  }

  override fun prepareVerifier(verifier: IProviderVerifier, testInstance: Any, pact: Pact) { }

  override fun supportsInteraction(interaction: Interaction) = interaction is SynchronousRequestResponse

  override fun executeInteraction(client: Any?, request: Any?): ProviderResponse {
    val providerClient = client as ProviderClient
    val httpRequest = request as HttpUriRequest
    return providerClient.executeRequest(providerClient.getHttpClient(), httpRequest)
  }

  companion object {
    /**
     * Creates a HttpTestTarget from a URL. If the URL does not contain a port, 8080 will be used.
     */
    @JvmStatic
    fun fromUrl(url: URL) = HttpTestTarget(url.host,
        if (url.port == -1) 8080 else url.port,
        if (url.path == null) "/" else url.path)
  }
}

/**
 * Test target for providers using HTTPS.
 *
 * @property host Host to bind to. Defaults to localhost.
 * @property port Port that the provider is running on. Defaults to 8080.
 * @property path The path that the provider is mounted on. Defaults to the root path.
 * @property insecure Supports using certs that will not be verified. You need this enabled if you are using self-signed
 * or untrusted certificates. Defaults to false.
 */
open class HttpsTestTarget @JvmOverloads constructor (
  host: String = "localhost",
  port: Int = 8443,
  path: String = "",
  val insecure: Boolean = false,
  httpClientFactory: () -> IHttpClientFactory = { HttpClientFactory() }
) : HttpTestTarget(host, port, path, httpClientFactory) {

  override fun getProviderInfo(serviceName: String, pactSource: PactSource?): IProviderInfo {
    val providerInfo = super.getProviderInfo(serviceName, pactSource)
    providerInfo.protocol = "https"
    providerInfo.insecure = insecure
    return providerInfo
  }

  companion object {
    /**
     * Creates a HttpsTestTarget from a URL. If the URL does not contain a port, 443 will be used.
     *
     * @param insecure Supports using certs that will not be verified. You need this enabled if you are using self-signed
     * or untrusted certificates. Defaults to false.
     */
    @JvmStatic
    @JvmOverloads
    fun fromUrl(url: URL, insecure: Boolean = false) = HttpsTestTarget(url.host,
      if (url.port == -1) 443 else url.port, if (url.path == null) "/" else url.path, insecure)
  }
}

/**
 * Test target for use with asynchronous providers (like with message queues) and synchronous request/response message
 * flows (like gRPC or Kafka request/reply strategies).
 *
 * This target will look for methods with a @PactVerifyProvider annotation where the value is the description of the
 * interaction. For asynchronous messages, these functions must take no parameter and return the message
 * (or message + metadata), while for synchronous messages they can receive the request message then must return the
 * response message (or message + metadata).
 *
 * @property packagesToScan List of packages to scan for methods with @PactVerifyProvider annotations. Defaults to the
 * full test classpath.
 * @property classLoader (Optional) ClassLoader to use to scan for packages
 */
open class MessageTestTarget @JvmOverloads constructor(
  private val packagesToScan: List<String> = emptyList(),
  private val classLoader: ClassLoader? = null
) : TestTarget {
  override fun isHttpTarget() = false
  override val userConfig: Map<String, Any?> = emptyMap()

  override fun getProviderInfo(serviceName: String, pactSource: PactSource?): IProviderInfo {
    val providerInfo = ProviderInfo(serviceName)
    providerInfo.verificationType = PactVerification.ANNOTATED_METHOD
    providerInfo.packagesToScan = packagesToScan

    if (pactSource is PactBrokerSource<*>) {
      val (_, _, _, pacts) = pactSource
      providerInfo.consumers = pacts.entries.flatMap { e -> e.value.map { p -> ConsumerInfo(e.key.name, p) } }
        .toMutableList()
    } else if (pactSource is DirectorySource) {
      val (_, pacts) = pactSource
      providerInfo.consumers = pacts.entries.map { e -> ConsumerInfo(e.value.consumer.name, e.value) }
        .toMutableList()
    }
    return providerInfo
  }

  override fun prepareRequest(pact: Pact, interaction: Interaction, context: MutableMap<String, Any>): Pair<Any, Any>? {
    if (interaction is MessageInteraction || interaction is V4Interaction.SynchronousMessages) {
      return null
    }
    throw UnsupportedOperationException("Only message interactions can be used with an AMPQ test target")
  }

  override fun prepareVerifier(verifier: IProviderVerifier, testInstance: Any, pact: Pact) {
    verifier.projectClassLoader = Supplier { classLoader }
    verifier.projectClasspath = Supplier {
      when (val classLoader = classLoader ?: testInstance.javaClass.classLoader) {
        is URLClassLoader -> classLoader.urLs.toList()
        else -> emptyList()
      }
    }
    val defaultProviderMethodInstance = verifier.providerMethodInstance
    verifier.providerMethodInstance = Function { m ->
      if (m.declaringClass == testInstance.javaClass) {
        testInstance
      } else {
        defaultProviderMethodInstance.apply(m)
      }
    }
  }

  override fun supportsInteraction(interaction: Interaction) = interaction is MessageInteraction ||
    interaction is V4Interaction.SynchronousMessages

  override fun executeInteraction(client: Any?, request: Any?): ProviderResponse {
    return ProviderResponse(200)
  }
}
