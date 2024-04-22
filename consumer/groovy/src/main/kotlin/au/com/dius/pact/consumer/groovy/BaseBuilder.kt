package au.com.dius.pact.consumer.groovy

import au.com.dius.pact.consumer.interactionCatalogueEntries
import au.com.dius.pact.core.matchers.MatchingConfig
import au.com.dius.pact.core.matchers.matcherCatalogueEntries
import au.com.dius.pact.core.model.IHttpPart
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.ContentType
import au.com.dius.pact.core.model.generators.Category
import au.com.dius.pact.core.model.generators.Generators
import au.com.dius.pact.core.model.generators.ProviderStateGenerator
import au.com.dius.pact.core.model.matchingrules.MatchingRuleCategory
import au.com.dius.pact.core.model.matchingrules.MatchingRules
import au.com.dius.pact.core.model.queryStringToMap
import au.com.dius.pact.core.support.expressions.DataType
import au.com.dius.pact.core.support.property
import au.com.dius.pact.core.support.Result
import groovy.json.JsonBuilder
import io.pact.plugins.jvm.core.CatalogueManager
import io.pact.plugins.jvm.core.DefaultPluginManager
import io.pact.plugins.jvm.core.PactPlugin
import io.pact.plugins.jvm.core.PactPluginNotFoundException
import io.github.oshai.kotlinlogging.KLogging
import java.util.regex.Pattern

open class BaseBuilder(
  var pactVersion: PactSpecVersion = PactSpecVersion.V4,
  val plugins: MutableList<PactPlugin> = mutableListOf()
) : Matchers() {

  init {
    CatalogueManager.registerCoreEntries(
      MatchingConfig.contentMatcherCatalogueEntries() +
      matcherCatalogueEntries() + interactionCatalogueEntries() + MatchingConfig.contentHandlerCatalogueEntries()
    )
  }

  protected fun setupBody(data: Map<String, Any>, httpPart: IHttpPart): OptionalBody {
    return if (data.containsKey(BODY)) {
      val body = data[BODY]
      val contentType = httpPart.determineContentType()
      if (body != null && body::class.qualifiedName == "au.com.dius.pact.consumer.groovy.PactBodyBuilder") {
        httpPart.matchingRules.addCategory(body::class.property("matchers")?.get(body) as MatchingRuleCategory)
        httpPart.generators.addGenerators(body::class.property("generators")?.get(body) as Generators)
        OptionalBody.body(body::class.property(BODY)?.get(body).toString().toByteArray(contentType.asCharset()))
      } else if (body is Matcher) {
        httpPart.matchingRules.addCategory("body").addRule("$", body.matcher!!)
        if (body.generator != null) {
          httpPart.generators.addGenerator(Category.BODY, "$", body.generator!!)
        }
        if (!httpPart.hasHeader("Content-Type")) {
          httpPart.headers["Content-Type"] = listOf(ContentType.TEXT_PLAIN.toString())
        }
        OptionalBody.body(body.value?.toString()?.toByteArray(contentType.asCharset()), ContentType.TEXT_PLAIN)
      } else if (body != null && body !is String) {
        if (contentType.isBinaryType()) {
          when (body) {
            is ByteArray -> OptionalBody.body(body)
            else -> OptionalBody.body(body.toString().toByteArray(contentType.asCharset()))
          }
        } else {
          val prettyPrint = data["prettyPrint"] as Boolean?
          if (prettyPrint == null && !compactMimeTypes(data) || prettyPrint == true) {
            OptionalBody.body(JsonBuilder(body).toPrettyString().toByteArray(contentType.asCharset()))
          } else {
            OptionalBody.body(JsonBuilder(body).toString().toByteArray(contentType.asCharset()))
          }
        }
      } else {
        OptionalBody.body(body.toString().toByteArray(contentType.asCharset()))
      }
    } else {
      OptionalBody.missing()
    }
  }

  protected fun setupHeaders(
    headers: Map<String, Any>,
    matchers: MatchingRules,
    generators: Generators
  ): Map<String, List<String>> {
    return headers.entries.associate { (key, value) ->
      when (value) {
        is Matcher -> {
          matchers.addCategory(HEADER).addRule(key, value.matcher!!)
          key to listOf(value.value.toString())
        }
        is Pattern -> {
          val matcher = RegexpMatcher(value.toString())
          matchers.addCategory(HEADER).addRule(key, matcher.matcher!!)
          key to listOf(matcher.value.toString())
        }
        is GeneratedValue -> {
          generators.addGenerator(au.com.dius.pact.core.model.generators.Category.HEADER, key,
            ProviderStateGenerator(value.expression, DataType.STRING))
          key to listOf(value.exampleValue.toString())
        }
        else -> {
          val list = if (value is List<*>) value.map { it.toString() } else listOf(value.toString())
          key to list
        }
      }
    }
  }

  protected fun setupPath(path: Any, matchers: MatchingRules, generators: Generators): String {
    return when (path) {
      is Matcher -> {
        matchers.addCategory("path").addRule(path.matcher!!)
        path.value.toString()
      }
      is Pattern -> {
        val matcher = RegexpMatcher(path.toString())
        matchers.addCategory("path").addRule(matcher.matcher!!)
        matcher.value.toString()
      }
      is GeneratedValue -> {
        generators.addGenerator(au.com.dius.pact.core.model.generators.Category.PATH,
          generator = ProviderStateGenerator(path.expression, DataType.STRING))
        path.exampleValue.toString()
      }
      else -> {
        path.toString()
      }
    }
  }

  protected fun setupQueryParameters(
    query: Any,
    matchers: MatchingRules,
    generators: Generators
  ): Map<String, List<String>> {
    return if (query is Map<*, *>) {
      query.entries.associate { (key, value) ->
        when (value) {
          is Matcher -> {
            matchers.addCategory("query").addRule(key.toString(), value.matcher!!)
            key.toString() to listOf(value.value.toString())
          }
          is Pattern -> {
            val matcher = RegexpMatcher(value.toString())
            matchers.addCategory("query").addRule(key.toString(), matcher.matcher!!)
            key.toString() to listOf(matcher.value.toString())
          }
          is GeneratedValue -> {
            generators.addGenerator(au.com.dius.pact.core.model.generators.Category.QUERY, key.toString(),
              ProviderStateGenerator(value.expression, DataType.STRING))
            key.toString() to listOf(value.exampleValue.toString())
          }
          else -> {
            val list = if (value is List<*>) value.map { it.toString() } else listOf(value.toString())
            key.toString() to list
          }
        }
      }
    } else {
      queryStringToMap(query.toString())
    }
  }

  /**
   * Enable a plugin
   */
  open fun usingPlugin(name: String, version: String? = null): BaseBuilder {
    val plugin = findPlugin(name, version)
    if (plugin == null) {
      when (val result = DefaultPluginManager.loadPlugin(name, version)) {
        is Result.Ok -> plugins.add(result.value)
        is Result.Err -> {
          logger.error { result.error }
          throw PactPluginNotFoundException(name, version)
        }
      }
    }
    return this
  }

  /**
   * Enable a plugin
   */
  open fun usingPlugin(name: String) = usingPlugin(name, null)

  private fun findPlugin(name: String, version: String?): PactPlugin? {
    return if (version == null) {
      plugins.filter { it.manifest.name == name }.maxByOrNull { it.manifest.version }
    } else {
      plugins.find { it.manifest.name == name && it.manifest.version == version }
    }
  }

  companion object : KLogging() {
    const val CONTENT_TYPE = "Content-Type"
    const val JSON = "application/json"
    const val BODY = "body"
    const val LOCALHOST = "localhost"
    const val HEADER = "header"

    val COMPACT_MIME_TYPES = listOf("application/x-thrift+json")

    @JvmStatic
    fun compactMimeTypes(reqResData: Map<String, Any>): Boolean {
      return if (reqResData.containsKey("headers")) {
        val headers = reqResData["headers"] as Map<String, String>
        headers.entries.find { it.key == CONTENT_TYPE }?.value in COMPACT_MIME_TYPES
      } else false
    }

    @JvmStatic
    fun isCompactMimeType(mimetype: String) = mimetype in COMPACT_MIME_TYPES
  }
}
