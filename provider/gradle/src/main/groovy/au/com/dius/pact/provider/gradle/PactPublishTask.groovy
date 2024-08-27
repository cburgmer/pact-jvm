package au.com.dius.pact.provider.gradle

import au.com.dius.pact.core.pactbroker.PactBrokerClient
import au.com.dius.pact.core.pactbroker.PactBrokerClientConfig
import au.com.dius.pact.core.pactbroker.PublishConfiguration
import au.com.dius.pact.core.pactbroker.RequestFailedException
import au.com.dius.pact.core.support.Result
import groovy.io.FileType
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.StringUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleScriptException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Task to push pact files to a pact broker
 */
@SuppressWarnings(['Println', 'AbcMetric'])
abstract class PactPublishTask extends DefaultTask {

    @Input
    abstract Property<PactPublish> getPactPublish()

    @Input
    @Optional
    abstract Property<Broker> getBroker()

    @Input
    abstract Property<Object> getProjectVersion()

    @Input
    abstract Property<File> getPactDir()

    @TaskAction
    void publishPacts() {
        PactPublish pactPublish = pactPublish.getOrElse(null)
        if (pactPublish == null) {
            throw new GradleScriptException('You must add a pact publish configuration to your build before you can ' +
                'use the pactPublish task', null)
        }

        if (pactPublish.pactDirectory == null) {
            pactPublish.pactDirectory = pactDir.get()
        }
        def version = pactPublish.consumerVersion
        if (version == null) {
          version = projectVersion.get()
        } else if (version instanceof Closure) {
          version = version.call()
        }

        if (version == null || version.toString().empty) {
          throw new GradleScriptException('The consumer version is required to publish Pact files', null)
        }

        Broker broker = broker.getOrElse(null)
        def brokerConfig = broker ?: pactPublish
        def options = [:]
        if (StringUtils.isNotEmpty(brokerConfig.pactBrokerToken)) {
            options.authentication = [brokerConfig.pactBrokerAuthenticationScheme ?: 'bearer',
                                      brokerConfig.pactBrokerToken, brokerConfig.pactBrokerAuthenticationHeader]
        }
        else if (StringUtils.isNotEmpty(brokerConfig.pactBrokerUsername)) {
          options.authentication = [brokerConfig.pactBrokerAuthenticationScheme ?: 'basic',
                                    brokerConfig.pactBrokerUsername, brokerConfig.pactBrokerPassword]
        }

        def publishConfig = new PublishConfiguration(version.toString(), pactPublish.tags, pactPublish.consumerBranch,
          pactPublish.consumerBuildUrl)

        PactBrokerClientConfig brokerClientConfig = configureBrokerClient(pactPublish, broker)
        def brokerClient = new PactBrokerClient(brokerConfig.pactBrokerUrl, options, brokerClientConfig)

        File pactDirectory = pactPublish.pactDirectory as File
        boolean anyFailed = false
        pactDirectory.eachFileMatch(FileType.FILES, ~/.*\.json/) { pactFile ->
          if (pactFileIsExcluded(pactPublish, pactFile)) {
            println("Not publishing '${pactFile.name}' as it matches an item in the excluded list")
          } else {
            def result
            if (pactPublish.tags) {
              println "Publishing '${pactFile.name}' with tags ${pactPublish.tags.join(', ')} ... "
            } else {
              println "Publishing '${pactFile.name}' ... "
            }
            result = brokerClient.uploadPactFile(pactFile, publishConfig)
            if (result instanceof Result.Ok) {
              println('OK')
            } else {
              println("Failed - ${result.error.message}")
              if (result.error instanceof RequestFailedException && result.error.body) {
                println(result.error.body)
              }
              anyFailed = true
            }
          }
        }

        if (anyFailed) {
          throw new GradleScriptException('One or more of the pact files were rejected by the pact broker', null)
        }
    }

  private static PactBrokerClientConfig configureBrokerClient(PactPublish pactPublish, Broker broker) {
    def brokerClientConfig = new PactBrokerClientConfig()
    if (pactPublish.pactBrokerInsecureTLS != null) {
      brokerClientConfig.insecureTLS = pactPublish.pactBrokerInsecureTLS
    } else if (broker?.pactBrokerInsecureTLS != null) {
      brokerClientConfig.insecureTLS = broker.pactBrokerInsecureTLS
    }
    brokerClientConfig
  }

  static boolean pactFileIsExcluded(PactPublish pactPublish, File pactFile) {
    pactPublish.excludes.any {
      FilenameUtils.getBaseName(pactFile.name) ==~ it
    }
  }
}
