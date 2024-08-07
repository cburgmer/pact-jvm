package au.com.dius.pact.consumer.dsl

import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.matchingrules.ArrayContainsMatcher
import au.com.dius.pact.core.model.matchingrules.EachKeyMatcher
import au.com.dius.pact.core.model.matchingrules.MatchingRuleCategory
import au.com.dius.pact.core.model.matchingrules.MatchingRuleGroup
import au.com.dius.pact.core.model.matchingrules.MinTypeMatcher
import au.com.dius.pact.core.model.matchingrules.NumberTypeMatcher
import au.com.dius.pact.core.model.matchingrules.RegexMatcher
import au.com.dius.pact.core.model.matchingrules.RuleLogic
import au.com.dius.pact.core.model.matchingrules.TypeMatcher
import au.com.dius.pact.core.model.matchingrules.ValuesMatcher
import au.com.dius.pact.core.model.matchingrules.expressions.MatchingRuleDefinition
import kotlin.Triple
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

class PactDslJsonBodySpec extends Specification {

  def 'close must close off all parents and return the root'() {
    given:
      def root = new PactDslJsonBody()
      def array = new PactDslJsonArray('b', '', root)
      def obj = new PactDslJsonBody('c', '', array)

    when:
      def result = obj.close()

    then:
      root.closed
      obj.closed
      array.closed
      result.is root
  }

  @Unroll
  def 'min array like function should set the example size to the min size'() {
    expect:
    obj.close().body.get('test').size() == 2

    where:
    obj << [
      new PactDslJsonBody().minArrayLike('test', 2).id(),
      new PactDslJsonBody().minArrayLike('test', 2, PactDslJsonRootValue.id()),
      new PactDslJsonBody().minMaxArrayLike('test', 2, 3).id(),
    ]
  }

  def 'min array like function should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().minArrayLike('test', 3, 2)

    then:
    thrown(IllegalArgumentException)
  }

  def 'min array like function with root value should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().minArrayLike('test', 3, PactDslJsonRootValue.id(), 2)

    then:
    thrown(IllegalArgumentException)
  }

  def 'max array like function should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().maxArrayLike('test', 3, 4)

    then:
    thrown(IllegalArgumentException)
  }

  def 'max array like function with root value should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().minArrayLike('test', 4, PactDslJsonRootValue.id(), 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'minMax array like function should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().minMaxArrayLike('test', 3, 4, 2)

    then:
    thrown(IllegalArgumentException)
  }

  def 'minMax array like function with root value should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().minMaxArrayLike('test', 3, 4, PactDslJsonRootValue.id(), 2)

    then:
    thrown(IllegalArgumentException)
  }

  def 'minmax array like function should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().minMaxArrayLike('test', 2, 3, 4)

    then:
    thrown(IllegalArgumentException)
  }

  def 'minmax array like function with root value should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().minMaxArrayLike('test', 2, 3, PactDslJsonRootValue.id(), 4)

    then:
    thrown(IllegalArgumentException)
  }

  def 'each array with max like function should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().eachArrayWithMaxLike('test', 4, 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'each array with min function should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().eachArrayWithMinLike('test', 2, 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'each array with minmax like function should validate the number of examples match the max size'() {
    when:
    new PactDslJsonBody().eachArrayWithMinMaxLike('test', 4, 2, 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'each array with minmax function should validate the number of examples match the min size'() {
    when:
    new PactDslJsonBody().eachArrayWithMinMaxLike('test', 1, 2, 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'with nested objects, the rule logic value should be copied'() {
    expect:
    body.matchers.matchingRules['.foo.bar'].ruleLogic == RuleLogic.OR

    where:
    body = new PactDslJsonBody().object('foo')
      .or('bar', 42, PM.numberType(), PM.nullValue())
      .closeObject()
  }

  def 'generate the correct JSON when the attribute name is a number'() {
    expect:
    new PactDslJsonBody()
      .stringType('asdf')
      .array('0').closeArray()
      .eachArrayLike('1').closeArray().closeArray()
      .eachArrayWithMaxLike('2', 10).closeArray().closeArray()
      .eachArrayWithMinLike('3', 10).closeArray().closeArray()
            .close().toString() == '{"0":[],"1":[[]],"2":[[]],"3":[[],[],[],[],[],[],[],[],[],[]],"asdf":"string"}'
  }

  def 'generate the correct JSON when the attribute name has a space'() {
    expect:
    new PactDslJsonBody()
      .array('available Options')
        .object()
          .stringType('Material', 'Gold')
        .closeObject()
      .closeArray().toString() == '{"available Options":[{"Material":"Gold"}]}'
  }

  @Issue('#619')
  def 'test for behaviour of close for issue 619'() {
    given:
    PactDslJsonBody pactDslJsonBody = new PactDslJsonBody()
    PactDslJsonBody contactDetailsPactDslJsonBody = pactDslJsonBody.object('contactDetails')
    contactDetailsPactDslJsonBody.object('mobile')
      .stringType('countryCode', '64')
      .stringType('prefix', '21')
      .stringType('subscriberNumber', '123456')
      .closeObject()
    pactDslJsonBody = contactDetailsPactDslJsonBody.closeObject().close()

    expect:
    pactDslJsonBody.close().matchers.toMap(PactSpecVersion.V2) == [
      '$.body.contactDetails.mobile.countryCode': [match: 'type'],
      '$.body.contactDetails.mobile.prefix': [match: 'type'],
      '$.body.contactDetails.mobile.subscriberNumber': [match: 'type']
    ]
  }

  @Issue('#628')
  def 'test for behaviour of close for issue 628'() {
    given:
    PactDslJsonBody getBody = new PactDslJsonBody()
    getBody
      .object('metadata')
      .stringType('messageId', 'test')
      .stringType('date', 'test')
      .stringType('contractVersion', 'test')
      .closeObject()
      .object('payload')
      .stringType('name', 'srm.countries.get')
      .stringType('iri', 'some_iri')
      .closeObject()
      .closeObject()

    expect:
    getBody.close().matchers.toMap(PactSpecVersion.V2) == [
      '$.body.metadata.messageId': [match: 'type'],
      '$.body.metadata.date': [match: 'type'],
      '$.body.metadata.contractVersion': [match: 'type'],
      '$.body.payload.name': [match: 'type'],
      '$.body.payload.iri': [match: 'type']
    ]
  }

  def 'eachKey - generate a match values matcher'() {
    given:
    def pactDslJsonBody = new PactDslJsonBody()
      .object('one')
      .eachKeyLike('key1')
      .id()
      .closeObject()
      .closeObject()
      .object('two')
      .eachKeyLike('key2', PactDslJsonRootValue.stringMatcher('\\w+', 'test'))
      .closeObject()
      .object('three')
      .eachKeyMappedToAnArrayLike('key3')
      .id('key3-id')
      .closeObject()
      .closeArray()
      .closeObject()

    when:
    pactDslJsonBody.close()

    then:
    pactDslJsonBody.matchers.matchingRules == [
      '$.one': new MatchingRuleGroup([ValuesMatcher.INSTANCE]),
      '$.one.*.id': new MatchingRuleGroup([TypeMatcher.INSTANCE]),
      '$.two': new MatchingRuleGroup([ValuesMatcher.INSTANCE]),
      '$.two.*': new MatchingRuleGroup([new RegexMatcher('\\w+', 'test')]),
      '$.three': new MatchingRuleGroup([ValuesMatcher.INSTANCE]),
      '$.three.*[*].key3-id': new MatchingRuleGroup([TypeMatcher.INSTANCE])
    ]
  }

  def 'Allow an attribute to be defined from a DSL part'() {
    given:
    PactDslJsonBody contactDetailsPactDslJsonBody = new PactDslJsonBody()
    contactDetailsPactDslJsonBody.object('mobile')
      .stringType('countryCode', '64')
      .stringType('prefix', '21')
      .numberType('subscriberNumber')
      .closeObject()
    PactDslJsonBody pactDslJsonBody = new PactDslJsonBody()
      .object('contactDetails', contactDetailsPactDslJsonBody)
      .object('contactDetails2', contactDetailsPactDslJsonBody)
      .close()

    expect:
    pactDslJsonBody.matchers.toMap(PactSpecVersion.V2) == [
      '$.body.contactDetails.mobile.countryCode': [match: 'type'],
      '$.body.contactDetails.mobile.prefix': [match: 'type'],
      '$.body.contactDetails.mobile.subscriberNumber': [match: 'type'],
      '$.body.contactDetails2.mobile.countryCode': [match: 'type'],
      '$.body.contactDetails2.mobile.prefix': [match: 'type'],
      '$.body.contactDetails2.mobile.subscriberNumber': [match: 'type']
    ]
    pactDslJsonBody.generators.toMap(PactSpecVersion.V3) == [
      body: [
        '$.contactDetails.mobile.subscriberNumber': [type: 'RandomInt', min: 0, max: 2147483647],
        '$.contactDetails2.mobile.subscriberNumber': [type: 'RandomInt', min: 0, max: 2147483647]
      ]
    ]
    pactDslJsonBody.toString() ==
      '{"contactDetails":{"mobile":{"countryCode":"64","prefix":"21","subscriberNumber":100}},' +
      '"contactDetails2":{"mobile":{"countryCode":"64","prefix":"21","subscriberNumber":100}}}'
  }

  @Issue('#895')
  def 'check for invalid matcher paths'() {
    given:
    PactDslJsonBody body = new PactDslJsonBody()
    body.object('headers')
      .stringType('bestandstype')
      .stringType('Content-Type', 'application/json')
      .closeObject()
    PactDslJsonBody payload = new PactDslJsonBody()
    payload.stringType('bestandstype', 'foo')
      .stringType('bestandsid')
      .closeObject()
    body.object('payload', payload).close()

    expect:
    body.matchers.toMap(PactSpecVersion.V2) == [
      '$.body.headers.bestandstype': [match: 'type'],
      '$.body.headers.Content-Type': [match: 'type'],
      '$.body.payload.bestandstype': [match: 'type'],
      '$.body.payload.bestandsid': [match: 'type']
    ]
    body.matchers.toMap(PactSpecVersion.V3) == [
      '$.headers.bestandstype': [matchers: [[match: 'type']], combine: 'AND'],
      '$.headers.Content-Type': [matchers: [[match: 'type']], combine: 'AND'],
      '$.payload.bestandstype': [matchers: [[match: 'type']], combine: 'AND'],
      '$.payload.bestandsid': [matchers: [[match: 'type']], combine: 'AND']
    ]
    body.generators.toMap(PactSpecVersion.V3) == [body: [
      '$.headers.bestandstype': [type: 'RandomString', size: 20],
      '$.payload.bestandsid': [type: 'RandomString', size: 20]
    ]]
  }

  def 'support for date and time expressions'() {
    given:
    PactDslJsonBody body = new PactDslJsonBody()
    body.dateExpression('dateExp', 'today + 1 day')
      .timeExpression('timeExp', 'now + 1 hour')
      .datetimeExpression('datetimeExp', 'today + 1 hour')
      .closeObject()

    expect:
    body.matchers.toMap(PactSpecVersion.V3) == [
      '$.dateExp': [matchers: [[match: 'date', format: 'yyyy-MM-dd']], combine: 'AND'],
      '$.timeExp': [matchers: [[match: 'time', format: 'HH:mm:ss']], combine: 'AND'],
      '$.datetimeExp': [matchers: [[match: 'timestamp', format: "yyyy-MM-dd'T'HH:mm:ss"]], combine: 'AND']]

    body.generators.toMap(PactSpecVersion.V3) == [body: [
      '$.dateExp': [type: 'Date', format: 'yyyy-MM-dd', expression: 'today + 1 day'],
      '$.timeExp': [type: 'Time', format: 'HH:mm:ss', expression: 'now + 1 hour'],
      '$.datetimeExp': [type: 'DateTime', format: "yyyy-MM-dd'T'HH:mm:ss", expression: 'today + 1 hour']]]
  }

  def 'unordered array with min and max function should validate the minSize less than maxSize'() {
    when:
    new PactDslJsonBody().unorderedMinMaxArray('test', 4, 3)

    then:
    thrown(IllegalArgumentException)
  }

  def 'like matcher'() {
    given:
    PactDslJsonBody body = new PactDslJsonBody()
      .like('test', 'Test')
      .like('num', 100)

    expect:
    body.body.toString() == '{"num":100,"test":"Test"}'
    body.matchers.toMap(PactSpecVersion.V3) == [
      '.test': [matchers: [[match: 'type']], combine: 'AND'],
      '.num': [matchers: [[match: 'type']], combine: 'AND']
    ]
  }

  @Issue('#1220')
  def 'objects with date formatted keys'() {
    given:
    PactDslJsonBody body = new PactDslJsonBody()
      .stringType('01/01/2001', '1234')
      .booleanType('01/01/1900', true)

    expect:
    body.body.toString() == '{"01/01/1900":true,"01/01/2001":"1234"}'
    body.matchers.toMap(PactSpecVersion.V2) == [
      '$.body[\'01/01/2001\']': [match: 'type'],
      '$.body[\'01/01/1900\']': [match: 'type']
    ]
  }

  @Issue('#1367')
  def 'array contains test with two variants'() {
    when:
    PactDslJsonBody body = new PactDslJsonBody()
      .arrayContaining('output')
        .stringValue('a')
        .numberValue(1)
        .close()

    then:
    body.toString() == '{"output":["a",1]}'
    body.matchers.matchingRules == [
      '$.output': new MatchingRuleGroup([
        new ArrayContainsMatcher([
          new Triple(0, new MatchingRuleCategory('body'), [:]),
          new Triple(1, new MatchingRuleCategory('body'), [:])
        ])
      ])
    ]
  }

  @Issue('379')
  def 'using array like with multiple examples'() {
    when:
    PactDslJsonBody body = new PactDslJsonBody()
      .minArrayLike('foo', 2)
        .stringMatcher('bar', '[a-z0-9]+', 'abc', 'def')
        .integerType('baz', 666, 90210)
      .close()

    then:
    body.toString() == '{"foo":[{"bar":"abc","baz":666},{"bar":"def","baz":90210}]}'
    body.matchers.matchingRules == [
      '$.foo': new MatchingRuleGroup([new MinTypeMatcher(2)]),
      '$.foo[*].bar': new MatchingRuleGroup([new RegexMatcher('[a-z0-9]+')]),
      '$.foo[*].baz': new MatchingRuleGroup([new NumberTypeMatcher(NumberTypeMatcher.NumberType.INTEGER)])
    ]
  }

  @Issue('1600')
  def 'Match number type with Regex'() {
    when:
    PactDslJsonBody body = new PactDslJsonBody()
            .numberMatching('foo', '\\d+\\.\\d{2}', 2.01)
            .decimalMatching('bar', '\\d+\\.\\d{2}', 2.01)
            .integerMatching('baz', '\\d{5}', 90210)
            .close()

    then:
    body.toString() == '{"bar":2.01,"baz":90210,"foo":2.01}'
    body.matchers.matchingRules.keySet() == ['$.foo', '$.bar', '$.baz'] as Set
    body.matchers.matchingRules['$.foo'] == new MatchingRuleGroup([
      new NumberTypeMatcher(NumberTypeMatcher.NumberType.NUMBER),
      new RegexMatcher('\\d+\\.\\d{2}', '2.01')])
    body.matchers.matchingRules['$.bar'] == new MatchingRuleGroup([
      new NumberTypeMatcher(NumberTypeMatcher.NumberType.DECIMAL),
      new RegexMatcher('\\d+\\.\\d{2}', '2.01')])
    body.matchers.matchingRules['$.baz'] == new MatchingRuleGroup([
      new NumberTypeMatcher(NumberTypeMatcher.NumberType.INTEGER),
      new RegexMatcher('\\d{5}', '90210')])
  }

  @Issue('#1813')
  def 'matching each key'() {
    when:
    PactDslJsonBody body = new PactDslJsonBody()
      .object('test')
        .eachKeyMatching(Matchers.regexp('\\d+\\.\\d{2}', '2.01'))
      .closeObject()
    body.closeObject()

    then:
    body.toString() == '{"test":{"2.01":null}}'
    body.matchers.matchingRules.keySet() == ['$.test'] as Set
    body.matchers.matchingRules['$.test'] == new MatchingRuleGroup([
      new EachKeyMatcher(new MatchingRuleDefinition('2.01', new RegexMatcher('\\d+\\.\\d{2}', '2.01'), null))
    ])
  }

  @Issue('#1813')
  def 'matching each value'() {
    when:
    PactDslJsonBody body = new PactDslJsonBody()
      .eachValueMatching('prop1')
        .stringType('value', 'x')
      .closeObject()
    body.closeObject()

    then:
    body.toString() == '{"prop1":{"value":"x"}}'
    body.matchers.matchingRules.keySet() == ['$.*.value'] as Set
    body.matchers.matchingRules['$.*.value'] == new MatchingRuleGroup([
      TypeMatcher.INSTANCE
    ])
  }
}
