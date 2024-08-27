package au.com.dius.pact.provider.gradle

import au.com.dius.pact.core.support.Auth.Companion.DEFAULT_AUTH_HEADER

/**
 * Config for pact publish task
 */
data class PactPublish @JvmOverloads constructor(
  var pactDirectory: Any? = null,
  var pactBrokerUrl: String? = null,
  var consumerVersion: Any? = null,
  var pactBrokerToken: String? = null,
  var pactBrokerUsername: String? = null,
  var pactBrokerPassword: String? = null,
  var pactBrokerAuthenticationScheme: String? = null,
  var pactBrokerAuthenticationHeader: String? = DEFAULT_AUTH_HEADER,
  var tags: List<String> = listOf(),
  var excludes: List<String> = listOf(),
  var consumerBranch: String? = null,
  var consumerBuildUrl: String? = null,
  var pactBrokerInsecureTLS: Boolean? = null
) {
  override fun toString(): String {
    val password = if (pactBrokerPassword != null) "".padEnd(pactBrokerPassword!!.length, '*') else null
    return "PactPublish(pactDirectory=$pactDirectory, pactBrokerUrl=$pactBrokerUrl, " +
      "consumerVersion=$consumerVersion, pactBrokerToken=$pactBrokerToken, " +
      "pactBrokerUsername=$pactBrokerUsername, pactBrokerPassword=$password, " +
      "pactBrokerAuthenticationScheme=$pactBrokerAuthenticationScheme, " +
      "pactBrokerAuthenticationHeader=$pactBrokerAuthenticationHeader, " +
      "pactBrokerInsecureTLS=$pactBrokerInsecureTLS, " +
      "tags=$tags, " +
      "excludes=$excludes, " +
      "consumerBranch=$consumerBranch, consumerBuildUrl=$consumerBuildUrl)"
  }
}
