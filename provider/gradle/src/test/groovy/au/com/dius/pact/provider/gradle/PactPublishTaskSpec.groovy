package au.com.dius.pact.provider.gradle

import au.com.dius.pact.core.pactbroker.PactBrokerClient
import au.com.dius.pact.core.pactbroker.PublishConfiguration
import au.com.dius.pact.core.support.Result
import au.com.dius.pact.core.support.Version
import org.apache.commons.io.IOUtils
import org.gradle.api.GradleScriptException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

import java.nio.charset.Charset

class PactPublishTaskSpec extends Specification {

  private PactPlugin plugin
  private Project project
  private PactBrokerClient brokerClient
  private File pactFile

  def setup() {
    project = ProjectBuilder.builder().build()
    plugin = new PactPlugin()
    plugin.apply(project)

    project.file("${project.buildDir}/pacts").mkdirs()
    pactFile = project.file("${project.buildDir}/pacts/test_pact.json")
    pactFile.withWriter {
      IOUtils.copy(PactPublishTaskSpec.getResourceAsStream('/pacts/foo_pact.json'), it, Charset.forName('UTF-8'))
    }

    brokerClient = GroovySpy(PactBrokerClient, global: true, constructorArgs: ['baseUrl'])
  }

  def 'raises an exception if no pact publish configuration is found'() {
    when:
    project.tasks.pactPublish.publishPacts()

    then:
    thrown(GradleScriptException)
  }

  def 'successful publish'() {
    given:
    project.pact {
      publish {
        pactBrokerUrl = 'pactBrokerUrl'
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * brokerClient.uploadPactFile(_, _) >> new Result.Ok(null)
  }

  def 'failure to publish'() {
    given:
    project.pact {
      publish {
        pactBrokerUrl = 'pactBrokerUrl'
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * brokerClient.uploadPactFile(_, _) >> new Result.Err(new RuntimeException('Boom'))
    thrown(GradleScriptException)
  }

  def 'passes in basic authentication creds to the broker client'() {
    given:
    project.pact {
      publish {
        pactBrokerUsername = 'my user name'
        pactBrokerUrl = 'pactBrokerUrl'
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * new PactBrokerClient(_, ['authentication': ['basic', 'my user name', null]], _) >> brokerClient
    1 * brokerClient.uploadPactFile(_, _) >> new Result.Ok(null)
  }

  def 'passes in bearer token to the broker client'() {
    given:
    project.pact {
      publish {
        pactBrokerToken = 'token1234'
        pactBrokerUrl = 'pactBrokerUrl'
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * new PactBrokerClient(_, ['authentication': ['bearer', 'token1234', 'Authorization']], _) >> brokerClient
    1 * brokerClient.uploadPactFile(_, _) >> new Result.Ok(null)
  }

  def 'passes in bearer token to the broker client with custom auth header'() {
    given:
    project.pact {
      publish {
        pactBrokerToken = 'token1234'
        pactBrokerUrl = 'pactBrokerUrl'
        pactBrokerAuthenticationHeader = 'custom-header'
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * new PactBrokerClient(_, ['authentication': ['bearer', 'token1234', 'custom-header']], _) >> brokerClient
    1 * brokerClient.uploadPactFile(_, _) >> new Result.Ok(null)
  }

  def 'passes in any tags to the broker client'() {
    given:
    project.pact {
      publish {
        consumerVersion = '1'
        tags = ['tag1']
        pactBrokerUrl = 'pactBrokerUrl'
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * brokerClient.uploadPactFile(_, new PublishConfiguration('1', ['tag1'])) >> new Result.Ok(null)
  }

  def 'allows pact files to be excluded from publishing'() {
    given:
    project.pact {
      publish {
        excludes = ['other-pact', 'pact\\-\\d+']
        pactBrokerUrl = 'pactBrokerUrl'
      }
    }
    project.evaluate()

    List<File> excluded = ['pact-1', 'pact-2', 'other-pact'].collect { pactName ->
      def file = project.file("${project.buildDir}/pacts/${pactName}.json")
      file.withWriter {
        IOUtils.copy(PactPublishTaskSpec.getResourceAsStream('/pacts/foo_pact.json'), it, Charset.forName('UTF-8'))
      }
      file
    }

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * brokerClient.uploadPactFile(pactFile, _) >> new Result.Ok(null)
    0 * brokerClient.uploadPactFile(excluded[0], _)
    0 * brokerClient.uploadPactFile(excluded[1], _)
    0 * brokerClient.uploadPactFile(excluded[2], _)
  }

  def 'supports versions that are not string values'() {
    given:
    project.pact {
      publish {
        pactBrokerUrl = 'pactBrokerUrl'
        consumerVersion = Version.parse('1.2.3').get()
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * brokerClient.uploadPactFile(_, new PublishConfiguration('1.2.3')) >> new Result.Ok(null)
  }

  def 'allows insecure TLS to be set'() {
    given:
    project.pact {
      publish {
        pactBrokerToken = 'token1234'
        pactBrokerUrl = 'pactBrokerUrl'
        pactBrokerInsecureTLS = true
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * new PactBrokerClient(_, _, { it.insecureTLS == true }) >> brokerClient
    1 * brokerClient.uploadPactFile(_, _) >> new Result.Ok(null)
  }

  def 'allows insecure TLS to be set on the broker block'() {
    given:
    project.pact {
      broker {
        pactBrokerInsecureTLS = true
      }
      publish {
        pactBrokerToken = 'token1234'
        pactBrokerUrl = 'pactBrokerUrl'
      }
    }
    project.evaluate()

    when:
    project.tasks.pactPublish.publishPacts()

    then:
    1 * new PactBrokerClient(_, _, { it.insecureTLS == true }) >> brokerClient
    1 * brokerClient.uploadPactFile(_, _) >> new Result.Ok(null)
  }
}
