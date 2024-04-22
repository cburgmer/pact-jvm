package au.com.dius.pact.consumer.groovy

import au.com.dius.pact.core.model.ProviderState
import spock.lang.Issue
import spock.lang.Specification

@SuppressWarnings('PrivateFieldCouldBeFinal')
class PactBuilderSpec extends Specification {

  private PactBuilder aliceService = new PactBuilder()

  void setup() {
    aliceService {
      serviceConsumer 'Consumer'
      hasPactWith 'Alice Service'
      port 1234
    }
  }

  def 'should not define providerStates when no given()'() {
    given:
    aliceService {
      uponReceiving('a retrieve Mallory request')
      withAttributes(method: 'get', path: '/mallory')
      willRespondWith(
        status: 200,
        headers: ['Content-Type': 'text/html'],
        body: '"That is some good Mallory."'
      )
    }

    when:
    aliceService.updateInteractions()

    then:
    aliceService.interactions.size() == 1
    aliceService.interactions[0].providerStates.empty
  }

  def 'allows matching on paths'() {
    given:
    aliceService {
      uponReceiving('a request to match by path')
      withAttributes(method: 'get', path: ~'/mallory/[0-9]+')
      willRespondWith(
        status: 200,
        headers: ['Content-Type': 'text/html'],
        body: '"That is some good Mallory."'
      )
    }

    when:
    aliceService.updateInteractions()

    then:
    aliceService.interactions.size() == 1
    aliceService.interactions[0].request.path =~ '/mallory/[0-9]+'
    aliceService.interactions[0].request.matchingRules.rulesForCategory('path').matchingRules[''].rules.first().regex ==
      '/mallory/[0-9]+'
  }

  def 'allows using the defined matcher on paths'() {
    given:
    aliceService {
      uponReceiving('a request to match by path')
      withAttributes(method: 'get', path: regexp(~'/mallory/[0-9]+', '/mallory/1234567890'))
      willRespondWith(
        status: 200,
        headers: ['Content-Type': 'text/html'],
        body: '"That is some good Mallory."'
      )
    }

    when:
    aliceService.updateInteractions()

    then:
    aliceService.interactions.size() == 1
    aliceService.interactions[0].request.path == '/mallory/1234567890'
    aliceService.interactions[0].request.matchingRules.rulesForCategory('path').matchingRules[''].rules.first().regex ==
      '/mallory/[0-9]+'
  }

  def 'allows matching on headers'() {
    given:
    aliceService {
      uponReceiving('a request to match a header')
      withAttributes(method: 'get', path: '/headers', headers: [MALLORY: ~'mallory:[0-9]+'])
      willRespondWith(
        status: 200,
        headers: ['Content-Type': regexp('text/.*', 'text/html')],
        body: '"That is some good Mallory."'
      )
    }

    when:
    aliceService.updateInteractions()
    def firstInteraction = aliceService.interactions[0]

    then:
    aliceService.interactions.size() == 1

    firstInteraction.request.headers.MALLORY =~ 'mallory:[0-9]+'
    firstInteraction.request.matchingRules.rulesForCategory('header').matchingRules['MALLORY'].rules.first().regex ==
      'mallory:[0-9]+'
    firstInteraction.response.headers['Content-Type'] == ['text/html']
    firstInteraction.response.matchingRules.rulesForCategory('header').matchingRules['Content-Type'].
      rules.first().regex == 'text/.*'
  }

  def 'allow arrays as the root of the body'() {
    given:
    aliceService {
      uponReceiving('a request to get a array response')
      withAttributes(method: 'get', path: '/array')
      willRespondWith(status: 200)
      withBody([
        1, 2, 3
      ])
    }

    when:
    aliceService.updateInteractions()
    def firstInteraction = aliceService.interactions[0]

    then:
    aliceService.interactions.size() == 1

    firstInteraction.response.body.valueAsString() == '[\n' +
      '    1,\n' +
      '    2,\n' +
      '    3\n' +
      ']'
  }

  def 'allow arrays of objects as the root of the body'() {
    given:
    aliceService {
      uponReceiving('a request to get a array of objects response')
      withAttributes(method: 'get', path: '/array')
      willRespondWith(status: 200)
      withBody([
        {
          id identifier(1)
          name 'item1'
        }, {
          id identifier(2)
          name 'item2'
        }
      ])
    }

    when:
    aliceService.updateInteractions()
    def firstInteraction = aliceService.interactions[0]

    then:
    aliceService.interactions.size() == 1

    firstInteraction.response.body.valueAsString() == '[\n' +
      '    {\n' +
      '        "id": 1,\n' +
      '        "name": "item1"\n' +
      '    },\n' +
      '    {\n' +
      '        "id": 2,\n' +
      '        "name": "item2"\n' +
      '    }\n' +
      ']'
    firstInteraction.response.matchingRules.rulesForCategory('body').matchingRules.keySet().toString() ==
      '[$[0].id, $[1].id]'
  }

  def 'allow like matcher as the root of the body'() {
    given:
    aliceService {
      uponReceiving('a request to get a like array of objects response')
      withAttributes(method: 'get', path: '/array')
      willRespondWith(status: 200)
      withBody eachLike {
        id identifier(1)
        name 'item1'
      }
    }

    when:
    aliceService.updateInteractions()
    def firstInteraction = aliceService.interactions[0]

    then:
    aliceService.interactions.size() == 1

    firstInteraction.response.body.valueAsString() == '[\n' +
      '    {\n' +
      '        "id": 1,\n' +
      '        "name": "item1"\n' +
      '    }\n' +
      ']'
    firstInteraction.response.matchingRules.rulesForCategory('body').matchingRules.keySet().toString() ==
      '[$, $[*].id]'
  }

  def 'pretty prints bodies by default'() {
    given:
    aliceService {
      uponReceiving('a request')
      withAttributes(method: 'get', path: '/', body: [
        name: 'harry',
        surname: 'larry',
        position: 'staff',
        happy: true
      ])
      willRespondWith(status: 200, body: [name: 'harry'])
    }

    when:
    aliceService.updateInteractions()
    def request = aliceService.interactions.first().request
    def response = aliceService.interactions.first().response

    then:
    request.body.valueAsString() == '''|{
                             |    "name": "harry",
                             |    "surname": "larry",
                             |    "position": "staff",
                             |    "happy": true
                             |}'''.stripMargin()
    response.body.valueAsString() == '''|{
                              |    "name": "harry"
                              |}'''.stripMargin()
  }

  def 'pretty prints bodies if pretty print is set to true'() {
    given:
    aliceService {
      uponReceiving('a request')
      withAttributes(method: 'get', path: '/', body: [
        name: 'harry',
        surname: 'larry',
        position: 'staff',
        happy: true
      ], prettyPrint: true)
      willRespondWith(status: 200, body: [name: 'harry'], prettyPrint: true)
    }

    when:
    aliceService.updateInteractions()
    def request = aliceService.interactions.first().request
    def response = aliceService.interactions.first().response

    then:
    request.body.valueAsString() == '''|{
                             |    "name": "harry",
                             |    "surname": "larry",
                             |    "position": "staff",
                             |    "happy": true
                             |}'''.stripMargin()
    response.body.valueAsString() == '''|{
                              |    "name": "harry"
                              |}'''.stripMargin()
  }

  def 'does not pretty print bodies if pretty print is set to false'() {
    given:
    aliceService {
      uponReceiving('a request')
      withAttributes(method: 'get', path: '/', body: [
        name: 'harry',
        surname: 'larry',
        position: 'staff',
        happy: true
      ], prettyPrint: false)
      willRespondWith(status: 200, body: [name: 'harry'], prettyPrint: false)
    }

    when:
    aliceService.updateInteractions()
    def request = aliceService.interactions.first().request
    def response = aliceService.interactions.first().response

    then:
    request.body.valueAsString() == '{"name":"harry","surname":"larry","position":"staff","happy":true}'
    response.body.valueAsString() == '{"name":"harry"}'
  }

  def 'does not pretty print bodies if the mimetype corresponds to one that requires compact bodies'() {
    given:
    aliceService {
      uponReceiving('a request')
      withAttributes(method: 'get', path: '/', body: [
        name: 'harry',
        surname: 'larry',
        position: 'staff',
        happy: true
      ], headers: ['Content-Type': 'application/x-thrift+json'])
      willRespondWith(status: 200, body: [name: 'harry'], headers: ['Content-Type': 'application/x-thrift+json'])
    }

    when:
    aliceService.updateInteractions()
    def request = aliceService.interactions.first().request
    def response = aliceService.interactions.first().response

    then:
    request.body.valueAsString() == '{"name":"harry","surname":"larry","position":"staff","happy":true}'
    response.body.valueAsString() == '{"name":"harry"}'
  }

  def 'does not overwrite the content type if it has been set in a header'() {
    given:
    aliceService {
      uponReceiving('a request for HAL')
      withAttributes(method: 'get', path: '/')
      willRespondWith(status: 200, headers: ['Content-Type': 'application/hal+json'])
      withBody {
        i 'am a body'
      }
    }

    when:
    aliceService.updateInteractions()
    def response = aliceService.interactions.first().response

    then:
    response.headers['Content-Type'] == ['application/hal+json']
  }

  @Issue('#1287')
  def 'provider states should be able to be set before ar after the uponReceiving call'() {
    given:
    aliceService {
      given('provider state one')
      uponReceiving('a request with provider states')
      given('provider state two')
      willRespondWith(
        status: 200,
      )
    }

    when:
    aliceService.updateInteractions()

    then:
    aliceService.interactions.size() == 1
    aliceService.interactions[0].providerStates == [
      new ProviderState('provider state one'), new ProviderState('provider state two')
    ]
  }

  @Issue('#443')
  def 'supports regex matcher on plain text body'() {
    given:
    aliceService {
      uponReceiving('a request with plain test')
      withAttributes(
        method: 'post',
        path: '/random',
        body: regexp(~/\w+/, 'randomText'))
      willRespondWith(
        status: 200,
      )
    }

    when:
    aliceService.updateInteractions()

    then:
    aliceService.interactions.size() == 1
    aliceService.interactions[0].request.body.valueAsString() == 'randomText'
    aliceService.interactions[0].request.headers == [
      'Content-Type': ['text/plain; charset=ISO-8859-1']
    ]
    aliceService.interactions[0].request.matchingRules.toV3Map(null) == [
      body: [
        '$': [
          matchers: [[match: 'regex', regex: '\\w+']], combine: 'AND']
        ]
    ]
  }
}
