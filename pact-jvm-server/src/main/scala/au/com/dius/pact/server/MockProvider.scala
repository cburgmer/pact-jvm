package au.com.dius.pact.server

import au.com.dius.pact.consumer.model.{MockHttpsKeystoreProviderConfig, MockHttpsProviderConfig, MockProviderConfig}
import au.com.dius.pact.core.model.{IResponse, PactSpecVersion, Request, Response, Pact => PactModel}
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

trait MockProvider {
  def config: MockProviderConfig
  def session: PactSession
  def start(pact: PactModel): Unit
  def run[T](code: => T): Try[T]
  def runAndClose[T](pact: PactModel)(code: => T): Try[(T, PactSessionResults)]
  def stop(): Unit
}

object DefaultMockProvider {

  def withDefaultConfig(pactVersion: PactSpecVersion = PactSpecVersion.V3) =
    apply(MockProviderConfig.createDefault(pactVersion))

  // Constructor providing a default implementation of StatefulMockProvider.
  // Users should not explicitly be forced to choose a variety.
  def apply(config: MockProviderConfig): StatefulMockProvider =
    config match {
      case httpsConfig: MockHttpsProviderConfig => new UnfilteredHttpsMockProvider(httpsConfig)
      case httpsKeystoreConfig: MockHttpsKeystoreProviderConfig => new UnfilteredHttpsKeystoreMockProvider(httpsKeystoreConfig)
      case _ => new UnfilteredMockProvider(config)
    }
}

// TODO: eliminate horrid state mutation and synchronisation.  Reactive stuff to the rescue?
abstract class StatefulMockProvider extends MockProvider with StrictLogging {
  private var sessionVar = PactSession.getEmpty
  private var pactVar: Option[PactModel] = None

  private def waitForRequestsToFinish() = Thread.sleep(100)

  def session: PactSession  = sessionVar
  def pact: Option[PactModel] = pactVar

  def start(): Unit

  override def start(pact: PactModel): Unit = synchronized {
    pactVar = Some(pact)
    sessionVar = PactSession.forPact(pact)
    start()
  }

  override def run[T](code: => T): Try[T] = {
    Try {
      val codeResult = code
      waitForRequestsToFinish()
      codeResult
    }
  }

  override def runAndClose[T](pact: PactModel)(code: => T): Try[(T, PactSessionResults)] = {
    Try {
      try {
        start(pact)
        val codeResult = code
        waitForRequestsToFinish()
        (codeResult, session.remainingResults)
      } finally {
        stop()
      }
    }
  }

  final def handleRequest(req: Request): IResponse = synchronized {
    logger.debug("Received request: " + req)
    val result = session.receiveRequest(req)
    val response = result.getFirst
    val newSession = result.getSecond
    logger.debug("Generating response: " + response)
    sessionVar = newSession
    response
  }
}
