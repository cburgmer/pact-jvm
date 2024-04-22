package au.com.dius.pact.core.model.matchingrules

import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.atLeast
import au.com.dius.pact.core.model.pathFromTokens
import au.com.dius.pact.core.model.parsePath
import au.com.dius.pact.core.support.Json
import au.com.dius.pact.core.support.json.JsonValue
import io.github.oshai.kotlinlogging.KLogging

class MatchingRulesImpl : MatchingRules {

    val rules = mutableMapOf<String, MatchingRuleCategory>()

    override fun rulesForCategory(category: String): MatchingRuleCategory = addCategory(category)

    override fun addCategory(category: MatchingRuleCategory): MatchingRuleCategory {
        rules[category.name] = category
        return category
    }

    override fun addCategory(category: String): MatchingRuleCategory = rules.getOrPut(category) {
      MatchingRuleCategory(category)
    }

  override fun copy(): MatchingRules {
        val copy = MatchingRulesImpl()
        rules.map { it.value }.forEach { copy.addCategory(it) }
        return copy
    }

    fun fromV2Json(json: JsonValue.Object) {
      json.entries.forEach { (key, value) ->
        val path = parsePath(key)
        if (key.startsWith("$.body")) {
          if (key == "$.body") {
            addV2Rule("body", "$", Json.toMap(value))
          } else {
            addV2Rule("body", "$${key.substring(6)}", Json.toMap(value))
          }
        } else if (key.startsWith("$.headers")) {
          val headerValue = if (path.size > 3) {
            pathFromTokens(path.drop(2))
          } else path[2].rawString()
          addV2Rule("header", headerValue, Json.toMap(value))
        } else {
          val ruleValue = if (path.size > 3) {
            pathFromTokens(path.drop(2))
          }
          else if (path.size == 3) path[2].rawString()
          else null
          addV2Rule(path[1].toString(), ruleValue, Json.toMap(value))
        }
      }
    }

    override fun isEmpty(): Boolean = rules.all { it.value.isEmpty() }

    override fun isNotEmpty(): Boolean = !isEmpty()

    override fun hasCategory(category: String): Boolean = rules.contains(category)

    override fun getCategories(): Set<String> = rules.keys

    override fun toString(): String = "MatchingRules(rules=$rules)"

    override fun equals(other: Any?): Boolean = when (other) {
        is MatchingRulesImpl -> other.rules == rules
        else -> false
    }

    override fun hashCode(): Int = rules.hashCode()

    override fun toMap(pactSpecVersion: PactSpecVersion?): Map<String, Any?> = when {
        pactSpecVersion.atLeast(PactSpecVersion.V3) -> toV3Map(pactSpecVersion)
        else -> toV2Map()
    }

    private fun toV3Map(pactSpecVersion: PactSpecVersion?): Map<String, Map<String, Any?>> =
      rules.filter { it.value.isNotEmpty() }.mapValues { entry ->
        entry.value.toMap(pactSpecVersion)
    }

  fun fromV3Json(json: JsonValue.Object) {
    json.entries.forEach { (key, value) ->
      addRules(key, value)
    }
  }

  override fun validateForVersion(pactVersion: PactSpecVersion?): List<String> {
    return rules.values.flatMap { it.validateForVersion(pactVersion) }
  }

  override fun rename(oldCategory: String, newCategory: String): MatchingRules {
    val copy = MatchingRulesImpl()
    rules.map { it.value }.forEach {
      if (it.name == oldCategory) {
        copy.addCategory(it.copy(name = newCategory))
      } else {
        copy.addCategory(it)
      }
    }
    return copy
  }

  companion object : KLogging() {
      @JvmStatic
      fun fromJson(json: JsonValue?): MatchingRules {
        val matchingRules = MatchingRulesImpl()
        if (json is JsonValue.Object && json.entries.isNotEmpty()) {
          if (json.entries.keys.first().startsWith("$")) {
            matchingRules.fromV2Json(json)
          } else {
            matchingRules.fromV3Json(json)
          }
        } else logger.warn { "$json is not valid matching rules format" }
        return matchingRules
      }
    }

  private fun addRules(categoryName: String, matcherDef: JsonValue) {
    addCategory(categoryName).fromJson(matcherDef)
  }

    private fun toV2Map(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        rules.forEach { entry ->
            entry.value.toMap(PactSpecVersion.V2).forEach {
                result[it.key] = it.value
            }
        }
        return result
    }

    private fun addV2Rule(categoryName: String, item: String?, matcher: Map<String, Any?>) {
        val category = addCategory(categoryName)
        if (item != null) {
            category.addRule(item, MatchingRule.fromJson(Json.toJson(matcher)))
        } else {
            category.addRule(MatchingRule.fromJson(Json.toJson(matcher)))
        }
    }
}
