package au.com.dius.pact.consumer.dsl

import au.com.dius.pact.consumer.ConsumerPactBuilder
import au.com.dius.pact.core.model.OptionalBody
import au.com.dius.pact.core.model.PactSpecVersion
import spock.lang.Issue
import spock.lang.Specification

class PactDslRequestWithoutPathSpec extends Specification {

  def 'sets up any default state when created'() {
    given:
    ConsumerPactBuilder consumerPactBuilder = ConsumerPactBuilder.consumer('spec')
    PactDslWithState pactDslWithState = new PactDslWithState(consumerPactBuilder, 'spec', 'spec', null, null)
    PactDslRequestWithoutPath defaultRequestValues = new PactDslRequestWithoutPath(consumerPactBuilder,
      pactDslWithState, 'test', null, null, [:])
      .method('PATCH')
      .headers('test', 'test')
      .query('test=true')
      .body('{"test":true}')

    when:
    PactDslRequestWithoutPath subject = new PactDslRequestWithoutPath(consumerPactBuilder, pactDslWithState, 'test',
      defaultRequestValues, null, [:])

    then:
    subject.requestMethod == 'PATCH'
    subject.requestHeaders == [test: ['test']]
    subject.query == [test: ['true']]
    subject.requestBody == OptionalBody.body('{"test":true}'.bytes)
  }

  @Issue('#1121')
  def 'content type header is case sensitive'() {
    given:
    ConsumerPactBuilder consumerPactBuilder = ConsumerPactBuilder.consumer('spec')
    PactDslWithState pactDslWithState = new PactDslWithState(consumerPactBuilder, 'spec', 'spec', null, null)

    when:
    PactDslRequestWithoutPath request = new PactDslRequestWithoutPath(consumerPactBuilder,
      pactDslWithState, 'test', null, null, [:])
      .headers('content-type', 'text/plain')
      .body(new PactDslJsonBody())

    then:
    request.requestHeaders == ['content-type': ['text/plain']]
  }

  def 'allows setting any additional metadata'() {
    given:
    ConsumerPactBuilder consumerPactBuilder = ConsumerPactBuilder.consumer('spec')
    PactDslWithState pactDslWithState = new PactDslWithState(consumerPactBuilder, 'spec', 'spec', null, null)
    PactDslRequestWithoutPath subject = new PactDslRequestWithoutPath(consumerPactBuilder, pactDslWithState, 'test',
      null, null, [:])

    when:
    subject.addMetadataValue('test', 'value')

    then:
    subject.additionalMetadata == [test: 'value']
  }

  @Issue('#1623')
  def 'supports setting a content type matcher'() {
    given:
    def request = ConsumerPactBuilder.consumer('spec')
      .hasPactWith('provider')
      .uponReceiving('a XML request')
    def example = '<?xml version=\"1.0\" encoding=\"utf-8\"?><example>foo</example>'

    when:
    def result = request.bodyMatchingContentType('application/xml', example)

    then:
    result.requestHeaders['Content-Type'] == ['application/xml']
    result.requestBody.valueAsString() == example
    result.requestMatchers.rulesForCategory('body').toMap(PactSpecVersion.V4) == [
      '$': [matchers: [[match: 'contentType', value: 'application/xml']], combine: 'AND']
    ]
  }

  @Issue('#1767')
  def 'match path should valid the example against the regex'() {
    given:
    def request = ConsumerPactBuilder.consumer('spec')
      .hasPactWith('provider')
      .uponReceiving('a XML request')

    when:
    request.matchPath('\\/\\d+', '/abcd')

    then:
    def ex = thrown(au.com.dius.pact.consumer.InvalidMatcherException)
    ex.message == 'Example "/abcd" does not match regular expression "\\/\\d+"'
  }
}
