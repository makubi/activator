/**
 *   Copyright (C) 2012 Typesafe Inc. <http://typesafe.com>
 */
package test

import org.junit.Assert._
import org.junit._
import java.io.File
import play.api.test._
import play.api.libs.json._
import play.api.test.Helpers._
import language.implicitConversions
import play.api.mvc._
import play.api.http._
import scala.concurrent.{ Await, Future }
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import snap.AppManager
import activator.ProcessResult
import activator.ProcessSuccess
import activator.ProcessFailure

// TODO - With play's switch to everything being in a future, we botched up this code transition.
// We should probably not use the de-asynch method as often as we do.
class SbtTest {

  val testUtil = new com.typesafe.sbtrc.TestUtil(scratchDir = new File("ui/target/scratch"))

  import testUtil._

  implicit val timeout: Timeout = Timeout(120, TimeUnit.SECONDS)

  private def deAsync(result: Future[Result]): Result = {
    Await.result(result, timeout.duration)
  }

  private def loadAppIdFromLocation(location: File): ProcessResult[String] = {
    implicit val timeout = Timeout(120, TimeUnit.SECONDS)
    Await.result(AppManager.loadAppIdFromLocation(location), timeout.duration)
  }

  // the "body" and "Writeable" args are a workaround for
  // https://play.lighthouseapp.com/projects/82401/tickets/770-fakerequestwithjsonbody-no-longer-works
  // TODO drop this hack when upgrading past Play 2.1-RC1
  private def routeThrowingIfNotSuccess[B](req: FakeRequest[_], body: B)(implicit w: Writeable[B]): Result = {
    route(req, body) map deAsync match {
      case Some(result) if result.header.status == Status.OK => result
      case None =>
        throw new RuntimeException("got None back from request: " + req)
    }
  }

  private def routeExpectingAnError[B](req: FakeRequest[_], body: B)(implicit w: Writeable[B]): String = {
    route(req, body) map deAsync match {
      case Some(result) if result.header.status != Status.OK => contentAsString(Future.successful(result))(timeout)
      case None =>
        throw new RuntimeException("got None back from request: " + req)
    }
  }

  private def routeThrowingIfNotJson[B](req: FakeRequest[_], body: B)(implicit w: Writeable[B]): JsValue = {
    val result = routeThrowingIfNotSuccess(req, body)
    if (contentType(Future.successful(result))(timeout) != Some("application/json"))
      throw new RuntimeException("Wrong content type: " + contentType(Future.successful(result))(timeout))
    Json.parse(contentAsString(Future.successful(result))(timeout))
  }

  private def childTest(projectMaker: (String, String) => File, projectName: String)(assertions: JsValue => Unit): Unit = {
    val project = projectMaker(projectName, "0.13.0")
    running(FakeApplication()) {
      val appId = loadAppIdFromLocation(project) match {
        case ProcessSuccess(id) => id
        case whatever => throw new RuntimeException("bad result, got: " + whatever)
      }

      val runJson = JsObject(Seq("appId" -> JsString(appId),
        "taskId" -> JsString("test-" + projectName + "-task-id"),
        "description" -> JsString(projectName + " Test"),
        "task" -> JsObject(Seq("type" -> JsString("GenericRequest"), "name" -> JsString("run")))))

      val runReq = FakeRequest(method = "POST", uri = "/api/sbt/task", body = AnyContentAsJson(runJson),
        headers = FakeHeaders(Seq(
          HeaderNames.CONTENT_TYPE -> Seq("application/json; charset=utf-8"))))

      val taskJson = routeThrowingIfNotJson(runReq, runJson)

      assertions(taskJson)
    }
  }

  private def printOnFail[T](thing: Any)(block: => T): T = {
    try {
      block
    } catch {
      case e: Throwable =>
        System.err.println("failure: " + e.getClass.getName + ": " + e.getMessage)
        System.err.println("failed on: " + thing)
        throw e
    }
  }

  // we are supposed to fail to "import" an empty directory
  @Test
  def testHandleEmptyDirectory(): Unit = {
    val dummy = makeDummyEmptyDirectory("notAnSbtProject")
    running(FakeApplication()) {
      val result = loadAppIdFromLocation(dummy)
      result match {
        case ProcessFailure(errors) if errors exists (_.msg contains "Directory does not contain an sbt build") =>
        case x: ProcessFailure => throw new AssertionError(s"Got wrong error msgs: $x")
        case _: ProcessSuccess[_] => throw new AssertionError("Should not have found an sbt project.")
      }
    }
  }

  @Test
  def testRunChild(): Unit = {
    childTest(makeDummySbtProject, "runChild") { taskJson =>
      printOnFail(taskJson) {
        assertEquals(JsString("RequestReceivedEvent"), taskJson \ "type")
        // TODO somehow we need to test that the websocket gets a RunReponse
      }
    }
  }

  @Test
  def testRunChildBrokenBuild(): Unit = {
    childTest(makeDummySbtProjectWithBrokenBuild, "runChildBrokenBuild") { taskJson =>
      printOnFail(taskJson) {
        assertEquals(JsString("ErrorResponse"), taskJson \ "type")
        assertEquals(JsString("sbt process never got in touch, so unable to handle request GenericRequest(true,run,Map())"), taskJson \ "error")
      }
    }
  }

  @Test
  def testRunChildMissingMain(): Unit = {
    childTest(makeDummySbtProjectWithNoMain, "runChildMissingMain") { taskJson =>
      printOnFail(taskJson) {
        assertEquals(JsString("RequestReceivedEvent"), taskJson \ "type")
        // TODO somehow we need to test that the websocket gets an ErrorResponse
      }
    }
  }

  @Test
  def testForgetApp(): Unit = {
    val project = makeDummySbtProject("appToForget", "0.13.0")
    running(FakeApplication()) {

      def getHistory(): Seq[String] = {
        val getReq = FakeRequest(method = "GET", uri = "/api/app/history", body = AnyContentAsJson(Json.obj()),
          headers = FakeHeaders(Nil))
        val result = routeThrowingIfNotJson(getReq, AnyContentAsJson(Json.obj()))
        result match {
          case JsArray(apps) =>
            apps map { app =>
              app \ "id" match {
                case JsString(s) => s
                case whatever =>
                  throw new RuntimeException("bad json " + whatever)
              }
            }
          case whatever =>
            throw new RuntimeException("bad json " + whatever)
        }
      }

      getHistory()

      val appId = loadAppIdFromLocation(project) match {
        case ProcessSuccess(id) => id
        case whatever => throw new RuntimeException("bad result, got: " + whatever)
      }

      assertTrue("new app is in the history", getHistory().contains("appToForget"))

      val forgetReq = FakeRequest(method = "DELETE", uri = "/api/app/history/" + appId,
        body = AnyContentAsJson(Json.obj()),
        headers = FakeHeaders(Nil))

      routeThrowingIfNotSuccess(forgetReq, AnyContentAsJson(Json.obj()))

      assertTrue("forgotten app is no longer in the history", !getHistory().contains("appToForget"))
    }
  }
}
