package au.com.dius.pact.core.model

import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

@SuppressWarnings('LineLength')
class ProviderStateSpec extends Specification {

  @SuppressWarnings(['PublicInstanceField', 'NonFinalPublicField'])
  static class Pojo {
    public int v = 1
    public String s = 'one'
    public boolean b = false
    public vals = [1, 2, 'three']
  }

  @Unroll
  def 'generates a map of the state'() {
    expect:
    state.toMap() == map

    where:

    state                                              | map
    new ProviderState('test')                          | [name: 'test']
    new ProviderState('test', [:])                     | [name: 'test']
    new ProviderState('test', [a: 'B', b: 1, c: true]) | [name: 'test', params: [a: 'B', b: 1, c: true]]
    new ProviderState('test', [a: [b: ['B', 'C']]])    | [name: 'test', params: [a: [b: ['B', 'C']]]]
    new ProviderState('test', [a: new Pojo()])         | [name: 'test', params: [a: [v: 1, s: 'one', b: false, vals: [1, 2, 'three']]]]
  }

  def 'uniqueKey should only include parameter keys'() {
    given:
    def state1 = new ProviderState('test', [a: 'B', b: 1, c: '2020-03-04'])
    def state2 = new ProviderState('test', [a: 'B', b: 1, c: '2020-03-03'])
    def state3 = new ProviderState('test', [a: 'B', b: 1])
    def state4 = new ProviderState('test', [a: 'B', b: 1, d: '2020-03-04'])

    expect:
    state1.uniqueKey() == state2.uniqueKey()
    state1.uniqueKey() != state3.uniqueKey()
    state1.uniqueKey() != state4.uniqueKey()
  }

  @Issue('#1717')
  def 'uniqueKey should be deterministic'() {
    given:
    def state = new ProviderState('a user profile exists', [
      email_address: 'test@email.com',
      family_name: 'Test'
    ])
    def state2 = new ProviderState('a user profile exists', [
      family_name: 'Test',
      email_address: 'test@email.com'
    ])

    expect:
    state.uniqueKey() == state.uniqueKey()
    state.uniqueKey() == state2.uniqueKey()
  }
}
