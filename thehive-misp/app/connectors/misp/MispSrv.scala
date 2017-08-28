package connectors.misp

import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.{ Inject, Provider, Singleton }

import scala.collection.immutable
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

import play.api.inject.ApplicationLifecycle
import play.api.libs.json.JsLookupResult.jsLookupResultToJsLookup
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.json._
import play.api.libs.ws.WSBodyWritables.writeableOf_JsValue
import play.api.{ Configuration, Environment, Logger }

import akka.NotUsed
import akka.actor.{ Actor, ActorSystem }
import akka.stream.Materializer
import akka.stream.scaladsl.{ FileIO, Sink, Source }
import connectors.misp.JsonFormat._
import models._
import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import services._

import org.elastic4play.controllers.{ Fields, FileInputValue }
import org.elastic4play.models.JsonFormat.baseModelEntityWrites
import org.elastic4play.services.{ UserSrv ⇒ _, _ }
import org.elastic4play.utils.{ RichFuture, RichJson }
import org.elastic4play.{ InternalError, NotFoundError }

class MispConfig(val interval: FiniteDuration, val connections: Seq[MispConnection]) {

  def this(configuration: Configuration, defaultCaseTemplate: Option[String], globalWS: CustomWSAPI) = this(
    configuration.getOptional[FiniteDuration]("misp.interval").getOrElse(1.hour),

    for {
      cfg ← configuration.getOptional[Configuration]("misp").toSeq
      mispWS = globalWS.withConfig(cfg)

      defaultArtifactTags = cfg.getOptional[Seq[String]]("tags").getOrElse(Nil)
      name ← cfg.subKeys

      mispConnectionConfig ← Try(cfg.get[Configuration](name)).toOption.toSeq
      url ← mispConnectionConfig.getOptional[String]("url")
      key ← mispConnectionConfig.getOptional[String]("key")
      instanceWS = mispWS.withConfig(mispConnectionConfig)
      artifactTags = mispConnectionConfig.getOptional[Seq[String]]("tags").getOrElse(defaultArtifactTags)
      caseTemplate = mispConnectionConfig.getOptional[String]("caseTemplate").orElse(defaultCaseTemplate)
    } yield MispConnection(name, url, key, instanceWS, caseTemplate, artifactTags))

  @Inject def this(configuration: Configuration, httpSrv: CustomWSAPI) =
    this(
      configuration,
      configuration.getOptional[String]("misp.caseTemplate"),
      httpSrv)

  def getConnection(name: String): Option[MispConnection] = connections.find(_.name == name)
}

case class MispConnection(
    name: String,
    baseUrl: String,
    key: String,
    ws: CustomWSAPI,
    caseTemplate: Option[String],
    artifactTags: Seq[String]) {

  private[MispConnection] lazy val logger = Logger(getClass)

  logger.info(
    s"""Add MISP connection $name
       |\turl:           $baseUrl
       |\tproxy:         ${ws.proxy}
       |\tcase template: ${caseTemplate.getOrElse("<not set>")}
       |\tartifact tags: ${artifactTags.mkString}""".stripMargin)

  private[misp] def apply(url: String) =
    ws.url(s"$baseUrl/$url")
      .withHttpHeaders(
        "Authorization" → key,
        "Accept" → "application/json")

}

/**
 * This actor listens message from migration (message UpdateMispAlertArtifact) which indicates that artifacts in
 * MISP event must be retrieved in inserted in alerts.
 *
 * @param eventSrv event bus used to receive migration message
 * @param userSrv user service used to do operations on database without real user request
 * @param mispSrv misp service to invoke artifact update action
 * @param ec execution context
 */
class UpdateMispAlertArtifactActor @Inject() (
    eventSrv: EventSrv,
    userSrv: UserSrv,
    mispSrv: MispSrv,
    implicit val ec: ExecutionContext) extends Actor {

  private[UpdateMispAlertArtifactActor] lazy val logger = Logger(getClass)
  override def preStart(): Unit = {
    eventSrv.subscribe(self, classOf[UpdateMispAlertArtifact])
    super.preStart()
  }

  override def postStop(): Unit = {
    eventSrv.unsubscribe(self)
    super.postStop()
  }

  override def receive: Receive = {
    case UpdateMispAlertArtifact() ⇒
      logger.info("UpdateMispAlertArtifact")
      userSrv
        .inInitAuthContext { implicit authContext ⇒
          mispSrv.updateMispAlertArtifact()
        }
        .onComplete {
          case Success(_)     ⇒ logger.info("Artifacts in MISP alerts updated")
          case Failure(error) ⇒ logger.error("Update MISP alert artifacts error :", error)
        }
      ()
    case msg ⇒
      logger.info(s"Receiving unexpected message: $msg (${msg.getClass})")
  }
}
@Singleton
class MispSrv @Inject() (
    mispConfig: MispConfig,
    alertSrvProvider: Provider[AlertSrv],
    caseSrv: CaseSrv,
    artifactSrv: ArtifactSrv,
    userSrv: UserSrv,
    attachmentSrv: AttachmentSrv,
    tempSrv: TempSrv,
    eventSrv: EventSrv,
    migrationSrv: MigrationSrv,
    httpSrv: CustomWSAPI,
    environment: Environment,
    lifecycle: ApplicationLifecycle,
    implicit val system: ActorSystem,
    implicit val materializer: Materializer,
    implicit val ec: ExecutionContext) {

  private[misp] lazy val logger = Logger(getClass)
  private[misp] lazy val alertSrv = alertSrvProvider.get

  private[misp] def getInstanceConfig(name: String): Future[MispConnection] = mispConfig.connections
    .find(_.name == name)
    .fold(Future.failed[MispConnection](NotFoundError(s"""Configuration of MISP server "$name" not found"""))) { instanceConfig ⇒
      Future.successful(instanceConfig)
    }

  private[misp] def initScheduler(): Unit = {
    val task = system.scheduler.schedule(0.seconds, mispConfig.interval) {
      if (migrationSrv.isReady) {
        logger.info("Update of MISP events is starting ...")
        userSrv
          .inInitAuthContext { implicit authContext ⇒
            synchronize().andThen { case _ ⇒ tempSrv.releaseTemporaryFiles() }
          }
          .onComplete {
            case Success(a) ⇒
              logger.info("Misp synchronization completed")
              a.collect {
                case Failure(t) ⇒ logger.warn(s"Update MISP error", t)
              }
            case Failure(t) ⇒ logger.info("Misp synchronization failed", t)
          }
      }
      else {
        logger.info("MISP synchronization cancel, database is not ready")
      }
    }
    lifecycle.addStopHook { () ⇒
      logger.info("Stopping MISP fetching ...")
      task.cancel()
      Future.successful(())
    }
  }

  initScheduler()

  def synchronize()(implicit authContext: AuthContext): Future[Seq[Try[Alert]]] = {
    import org.elastic4play.services.QueryDSL._

    // for each MISP server
    Source(mispConfig.connections.toList)
      // get last synchronization
      .mapAsyncUnordered(1) { mispConnection ⇒
        alertSrv.stats(and("type" ~= "misp", "source" ~= mispConnection.name), Seq(selectMax("lastSyncDate")))
          .map { maxLastSyncDate ⇒ mispConnection → new Date((maxLastSyncDate \ "max_lastSyncDate").as[Long]) }
          .recover { case _ ⇒ mispConnection → new Date(0) }
      }
      .flatMapConcat {
        case (mispConnection, lastSyncDate) ⇒
          synchronize(mispConnection, Some(lastSyncDate))
      }
      .runWith(Sink.seq)
  }

  def fullSynchronize()(implicit authContext: AuthContext): Future[immutable.Seq[Try[Alert]]] = {
    Source(mispConfig.connections.toList)
      .flatMapConcat(mispConnection ⇒ synchronize(mispConnection, None))
      .runWith(Sink.seq)
  }

  def synchronize(mispConnection: MispConnection, lastSyncDate: Option[Date])(implicit authContext: AuthContext): Source[Try[Alert], NotUsed] = {
    logger.info(s"Synchronize MISP ${mispConnection.name} from $lastSyncDate")
    // get events that have been published after the last synchronization
    getEventsFromDate(mispConnection, lastSyncDate.getOrElse(new Date(0)))
      // get related alert
      .mapAsyncUnordered(1) { event ⇒
        logger.trace(s"Looking for alert misp:${event.source}:${event.sourceRef}")
        alertSrv.get("misp", event.source, event.sourceRef)
          .map((event, _))
      }
      .mapAsyncUnordered(1) {
        case (event, alert) ⇒
          logger.trace(s"MISP synchro ${mispConnection.name}, event ${event.sourceRef}, alert ${alert.fold("no alert")(a ⇒ "alert " + a.alertId() + "last sync at " + a.lastSyncDate())}")
          logger.info(s"getting MISP event ${event.source}:${event.sourceRef}")
          getAttributes(mispConnection, event.sourceRef, lastSyncDate.flatMap(_ ⇒ alert.map(_.lastSyncDate())))
            .map((event, alert, _))
      }
      .mapAsyncUnordered(1) {
        // if there is no related alert, create a new one
        case (event, None, attrs) ⇒
          logger.info(s"MISP event ${event.source}:${event.sourceRef} has no related alert, create it with ${attrs.size} observable(s)")
          val alertJson = Json.toJson(event).as[JsObject] +
            ("type" → JsString("misp")) +
            ("caseTemplate" → mispConnection.caseTemplate.fold[JsValue](JsNull)(JsString)) +
            ("artifacts" → JsArray(attrs))
          alertSrv.create(Fields(alertJson))
            .map(Success(_))
            .recover { case t ⇒ Failure(t) }

        // if a related alert exists and we follow it or a fullSync, update it
        case (event, Some(alert), attrs) if alert.follow() || lastSyncDate.isEmpty ⇒
          logger.info(s"MISP event ${event.source}:${event.sourceRef} has related alert, update it with ${attrs.size} observable(s)")
          val alertJson = Json.toJson(event).as[JsObject] -
            "type" -
            "source" -
            "sourceRef" -
            "caseTemplate" -
            "date" +
            ("artifacts" → JsArray(attrs)) +
            // if this is a full synchronization,  don't update alert status
            ("status" → (if (lastSyncDate.isEmpty) Json.toJson(alert.status())
            else alert.status() match {
              case AlertStatus.New ⇒ Json.toJson(AlertStatus.New)
              case _               ⇒ Json.toJson(AlertStatus.Updated)
            }))
          logger.debug(s"Update alert ${alert.id} with\n$alertJson")
          val fAlert = alertSrv.update(alert.id, Fields(alertJson))
          // if a case have been created, update it
          (alert.caze() match {
            case None ⇒ fAlert
            case Some(caze) ⇒
              for {
                a ← fAlert
                // if this is a full synchronization, don't update case status
                caseFields = if (lastSyncDate.isEmpty) Fields(alert.toCaseJson).unset("status")
                else Fields(alert.toCaseJson)
                _ ← caseSrv.update(caze, caseFields)
                _ ← artifactSrv.create(caze, attrs.map(Fields.apply))
              } yield a
          })
            .map(Success(_))
            .recover { case t ⇒ Failure(t) }

        // if the alert is not followed, do nothing
        case (_, Some(alert), _) ⇒
          Future.successful(Success(alert))
      }
  }

  def getEventsFromDate(mispConnection: MispConnection, fromDate: Date): Source[MispAlert, NotUsed] = {
    val date = fromDate.getTime / 1000
    Source
      .fromFuture {
        mispConnection("events/index")
          .post(Json.obj("searchpublish_timestamp" → date))
      }
      .mapConcat { response ⇒
        val eventJson = Json.parse(response.body)
          .asOpt[Seq[JsValue]]
          .getOrElse {
            logger.warn(s"Invalid MISP event format:\n${response.body}")
            Nil
          }
        val events = eventJson
          .flatMap { j ⇒
            j.asOpt[MispAlert]
              .map(_.copy(source = mispConnection.name))
              .orElse {
                logger.warn(s"MISP event can't be parsed\n$j")
                None
              }
          }

        val eventJsonSize = eventJson.size
        val eventsSize = events.size
        if (eventJsonSize != eventsSize)
          logger.warn(s"MISP returns $eventJsonSize events but only $eventsSize contain valid data")
        events.toList
      }
  }

  def getAttributes(
    mispConnection: MispConnection,
    eventId: String,
    fromDate: Option[Date]): Future[Seq[JsObject]] = {

    val date = fromDate.fold(0L)(_.getTime / 1000)

    mispConnection(s"attributes/restSearch/json")
      .post(Json.obj(
        "request" → Json.obj(
          "timestamp" → date,
          "eventid" → eventId)))
      // add ("deleted" → 1) to see also deleted attributes
      // add ("deleted" → "only") to see only deleted attributes
      .map { response ⇒
        val refDate = fromDate.getOrElse(new Date(0))
        val artifactTags = JsString(s"src:${mispConnection.name}") +: JsArray(mispConnection.artifactTags.map(JsString))
        (Json.parse(response.body) \ "response" \\ "Attribute")
          .flatMap(_.as[Seq[MispAttribute]])
          .filter(_.date after refDate)
          .flatMap {
            case a if a.tpe == "attachment" || a.tpe == "malware-sample" ⇒
              Seq(
                Json.obj(
                  "dataType" → "file",
                  "message" → a.comment,
                  "tags" → (artifactTags.value ++ a.tags.map(JsString)),
                  "remoteAttachment" → Json.obj(
                    "filename" → a.value,
                    "reference" → a.id,
                    "type" → a.tpe),
                  "startDate" → a.date))
            case a ⇒ convertAttribute(a).map { j ⇒
              val tags = artifactTags ++ (j \ "tags").asOpt[JsArray].getOrElse(JsArray(Nil))
              j.setIfAbsent("tlp", 2L) + ("tags" → tags)
            }
          }
      }
  }

  def attributeToArtifact(
    mispConnection: MispConnection,
    alert: Alert,
    attr: JsObject)(implicit authContext: AuthContext): Option[Future[Fields]] = {
    (for {
      dataType ← (attr \ "dataType").validate[String]
      data ← (attr \ "data").validateOpt[String]
      message ← (attr \ "message").validate[String]
      startDate ← (attr \ "startDate").validate[Date]
      attachmentReference ← (attr \ "remoteAttachment" \ "reference").validateOpt[String]
      attachmentType ← (attr \ "remoteAttachment" \ "type").validateOpt[String]
      attachment = attachmentReference
        .flatMap {
          case ref if dataType == "file" ⇒ Some(downloadAttachment(mispConnection, ref))
          case _                         ⇒ None
        }
        .map {
          case f if attachmentType.contains("malware-sample") ⇒ f.map(extractMalwareAttachment)
          case f                                              ⇒ f
        }
      tags = (attr \ "tags").asOpt[Seq[String]].getOrElse(Nil)
      tlp = tags.map(_.toLowerCase)
        .collectFirst {
          case "tlp:white" ⇒ JsNumber(0)
          case "tlp:green" ⇒ JsNumber(1)
          case "tlp:amber" ⇒ JsNumber(2)
          case "tlp:red"   ⇒ JsNumber(3)
        }
        .getOrElse(JsNumber(alert.tlp()))
      fields = Fields.empty
        .set("dataType", dataType)
        .set("message", message)
        .set("startDate", Json.toJson(startDate))
        .set("tags", JsArray(
          tags
            .filterNot(_.toLowerCase.startsWith("tlp:"))
            .map(JsString)))
        .set("tlp", tlp)
      if attachment.isDefined != data.isDefined
    } yield attachment.fold(Future.successful(fields.set("data", data.get)))(_.map { fiv ⇒
      fields.set("attachment", fiv)
    })) match {
      case JsSuccess(r, _) ⇒ Some(r)
      case e: JsError ⇒
        logger.warn(s"Invalid attribute in alert ${alert.id}: $e\n$attr")
        None
    }
  }

  def createCase(alert: Alert, customCaseTemplate: Option[String])(implicit authContext: AuthContext): Future[Case] = {
    alert.caze() match {
      case Some(id) ⇒ caseSrv.get(id)
      case None ⇒
        for {
          caseTemplate ← alertSrv.getCaseTemplate(alert, customCaseTemplate)
          caze ← caseSrv.create(Fields(alert.toCaseJson), caseTemplate)
          _ ← importArtifacts(alert, caze)
        } yield caze
    }
  }

  def importArtifacts(alert: Alert, caze: Case)(implicit authContext: AuthContext): Future[Case] = {
    for {
      instanceConfig ← getInstanceConfig(alert.source())
      artifacts ← Future.sequence(alert.artifacts().flatMap(attributeToArtifact(instanceConfig, alert, _)))
      _ ← artifactSrv.create(caze, artifacts)
    } yield caze
  }

  def mergeWithCase(alert: Alert, caze: Case)(implicit authContext: AuthContext): Future[Case] = {
    for {
      _ ← importArtifacts(alert, caze)
      description = caze.description() + s"\n  \n#### Merged with MISP event ${alert.title()}"
      updatedCase ← caseSrv.update(caze, Fields.empty.set("description", description))
    } yield updatedCase
  }

  def updateMispAlertArtifact()(implicit authContext: AuthContext): Future[Unit] = {
    import org.elastic4play.services.QueryDSL._
    logger.info("Update MISP attributes in alerts")
    val (alerts, _) = alertSrv.find("type" ~= "misp", Some("all"), Nil)
    alerts.mapAsyncUnordered(5) { alert ⇒
      if (alert.artifacts().nonEmpty) {
        logger.info(s"alert ${alert.id} has artifacts, ignore it")
        Future.successful(alert → Nil)
      }
      else {
        getInstanceConfig(alert.source())
          .flatMap { mcfg ⇒
            getAttributes(mcfg, alert.sourceRef(), None)
          }
          .map(alert → _)
          .recover {
            case NotFoundError(m) ⇒
              logger.error(s"Retrieve MISP attribute of event ${alert.id} error: $m")
              alert → Nil
            case error ⇒
              logger.error(s"Retrieve MISP attribute of event ${alert.id} error", error)
              alert → Nil
          }
      }
    }
      .filterNot(_._2.isEmpty)
      .mapAsyncUnordered(5) {
        case (alert, artifacts) ⇒
          logger.info(s"Updating alert ${alert.id}")
          alertSrv.update(alert.id, Fields.empty.set("artifacts", JsArray(artifacts)))
            .recover {
              case t ⇒ logger.error(s"Update alert ${alert.id} fail", t)
            }
      }
      .runWith(Sink.ignore)
      .map(_ ⇒ ())
  }

  def extractMalwareAttachment(file: FileInputValue)(implicit authContext: AuthContext): FileInputValue = {
    import scala.collection.JavaConverters._
    try {
      val zipFile = new ZipFile(file.filepath.toFile)

      if (zipFile.isEncrypted)
        zipFile.setPassword("infected")

      // Get the list of file headers from the zip file
      val fileHeaders = zipFile.getFileHeaders.asScala.toList.asInstanceOf[List[FileHeader]]
      val (fileNameHeaders, contentFileHeaders) = fileHeaders.partition { fileHeader ⇒
        fileHeader.getFileName.endsWith(".filename.txt")
      }
      (for {
        fileNameHeader ← fileNameHeaders
          .headOption
          .orElse {
            logger.warn(s"Format of malware attribute ${file.name} is invalid : file containing filename not found")
            None
          }
        buffer = Array.ofDim[Byte](128)
        len = zipFile.getInputStream(fileNameHeader).read(buffer)
        filename = new String(buffer, 0, len)

        contentFileHeader ← contentFileHeaders
          .headOption
          .orElse {
            logger.warn(s"Format of malware attribute ${file.name} is invalid : content file not found")
            None
          }

        tempFile = tempSrv.newTemporaryFile("misp_malware", file.name)
        _ = logger.info(s"Extract malware file ${file.filepath} in file $tempFile")
        _ = zipFile.extractFile(contentFileHeader, tempFile.getParent.toString, null, tempFile.getFileName.toString)
      } yield FileInputValue(filename, tempFile, "application/octet-stream")).getOrElse(file)
    }
    catch {
      case e: ZipException ⇒
        logger.warn(s"Format of malware attribute ${file.name} is invalid : zip file is unreadable", e)
        file
    }
  }

  def downloadAttachment(
    mispConnection: MispConnection,
    attachmentId: String)(implicit authContext: AuthContext): Future[FileInputValue] = {
    val fileNameExtractor = """attachment; filename="(.*)"""".r

    mispConnection(s"attributes/download/$attachmentId")
      .withMethod("GET")
      .stream()
      .flatMap {
        case response if response.status != 200 ⇒
          val status = response.status
          logger.warn(s"MISP attachment $attachmentId can't be downloaded (status $status) : ${response.body}")
          Future.failed(InternalError(s"MISP attachment $attachmentId can't be downloaded (status $status)"))
        case response ⇒
          val tempFile = tempSrv.newTemporaryFile("misp_attachment", attachmentId)
          response.bodyAsSource
            .runWith(FileIO.toPath(tempFile))
            .map { ioResult ⇒
              if (!ioResult.wasSuccessful) // throw an exception if transfer failed
                throw ioResult.getError
              val contentType = response.headers.getOrElse("Content-Type", Seq("application/octet-stream")).head
              val filename = response.headers
                .get("Content-Disposition")
                .flatMap(_.collectFirst { case fileNameExtractor(name) ⇒ name })
                .getOrElse("noname")
              FileInputValue(filename, tempFile, contentType)
            }
      }
  }

  def exportStatus(caseId: String): Future[Seq[(String, String)]] = {
    import org.elastic4play.services.QueryDSL._
    alertSrv.find(and("type" ~= "misp", "case" ~= caseId), Some("all"), Nil)
      ._1
      .map { alert ⇒
        alert.source() → alert.sourceRef()
      }
      .runWith(Sink.seq)
  }

  def relatedMispEvent(mispName: String, caseId: String): Future[(Option[String], Option[String])] = {
    import org.elastic4play.services.QueryDSL._
    alertSrv.find(and("type" ~= "misp", "case" ~= caseId, "source" ~= mispName), Some("0-1"), Nil)
      ._1
      .map { alert ⇒ alert.id → alert.sourceRef() }
      .runWith(Sink.headOption)
      .map(alertIdSource ⇒ alertIdSource.map(_._1) → alertIdSource.map(_._2))
  }

  def export(mispName: String, caze: Case)(implicit authContext: AuthContext): Future[(String, Seq[Try[Artifact]])] = {
    val mispConnection = mispConfig.getConnection(mispName).getOrElse(sys.error("MISP instance not found"))
    val dateFormat = new SimpleDateFormat("yy-MM-dd")

    def buildAttributeList(): Future[Seq[ExportedMispAttribute]] = {
      import org.elastic4play.services.QueryDSL._
      artifactSrv
        .find(parent("case", withId(caze.id)), Some("all"), Nil)
        ._1
        .map { artifact ⇒
          val (category, tpe) = artifact2attribute(artifact.dataType(), artifact.data())
          val value = (artifact.data(), artifact.attachment()) match {
            case (Some(data), None)       ⇒ Left(data)
            case (None, Some(attachment)) ⇒ Right(attachment)
            case _                        ⇒ sys.error("???")
          }
          ExportedMispAttribute(artifact, tpe, category, value, artifact.message())
        }
        .runWith(Sink.seq)
    }

    def removeDuplicateAttributes(attributes: Seq[ExportedMispAttribute]): Seq[ExportedMispAttribute] = {
      val attrIndex = attributes.zipWithIndex

      attrIndex
        .filter {
          case (ExportedMispAttribute(_, category, tpe, value, _), index) ⇒ attrIndex.exists {
            case (ExportedMispAttribute(_, `category`, `tpe`, `value`, _), otherIndex) ⇒ otherIndex >= index
            case _ ⇒ true
          }
        }
        .map(_._1)
    }

    def createEvent(title: String, severity: String, date: Date, attributes: Seq[ExportedMispAttribute]): Future[(String, Seq[ExportedMispAttribute])] = {
      val mispEvent = Json.obj(
        "Event" → Json.obj(
          "distribution" → 0,
          "threat_level_id" → caze.severity().toString,
          "analysis" → 0,
          "info" → caze.title(),
          "date" → dateFormat.format(caze.startDate()),
          "published" → false,
          "Attribute" → attributes))
      mispConnection("events")
        .post(mispEvent)
        .map { mispResponse ⇒
          val eventId = (mispResponse.json \ "Event" \ "id")
            .asOpt[String]
            .getOrElse(throw InternalError(s"Unexpected MISP response: ${mispResponse.status} ${mispResponse.statusText}\n${mispResponse.body}"))
          val messages = (mispResponse.json \ "errors" \ "Attribute")
            .asOpt[JsObject]
            .getOrElse(JsObject(Nil))
            .fields
            .toMap
            .mapValues { m ⇒
              (m \ "value")
                .asOpt[Seq[String]]
                .flatMap(_.headOption)
                .getOrElse(s"Unexpected message format: $m")
            }
          val exportedAttributes = attributes.zipWithIndex.collect {
            case (attr, index) if !messages.contains(index.toString) ⇒ attr
          }
          eventId → exportedAttributes
        }
    }

    def exportAttribute(eventId: String, attribute: ExportedMispAttribute): Future[Artifact] = {
      val mispResponse = attribute match {
        case ExportedMispAttribute(_, _, _, Right(attachment), comment) ⇒
          attachmentSrv
            .source(attachment.id)
            .runReduce(_ ++ _)
            .flatMap { data ⇒
              val b64data = java.util.Base64.getEncoder.encodeToString(data.toArray[Byte])
              val body = Json.obj(
                "request" → Json.obj(
                  "event_id" → eventId.toInt,
                  "category" → "Payload delivery",
                  "type" → "malware-sample",
                  "comment" → comment,
                  "files" → Json.arr(
                    Json.obj(
                      "filename" → attachment.name,
                      "data" → b64data))))
              mispConnection("events/upload_sample").post(body)
            }
        case attr ⇒ mispConnection(s"attributes/add/$eventId").post(Json.toJson(attr))

      }

      mispResponse.map {
        case response if response.status / 100 == 2 ⇒ attribute.artifact
        case response ⇒
          val json = response.json
          val message = (json \ "message").asOpt[String]
          val error = (json \ "errors" \ "value").head.asOpt[String]
          val errorMessage = for (m ← message; e ← error) yield s"$m $e"
          throw MispExportError(errorMessage orElse message orElse error getOrElse s"Unexpected MISP response: ${response.status} ${response.statusText}\n${response.body}", attribute.artifact)
      }
    }

    for {
      (maybeAlertId, maybeEventId) ← relatedMispEvent(mispName, caze.id)
      attributes ← buildAttributeList()
      uniqueAttributes = removeDuplicateAttributes(attributes)
      simpleAttributes = uniqueAttributes.filter(_.value.isLeft) // FIXME used only if event doesn't exist
      (eventId, existingAttributes) ← maybeEventId.fold {
        // if no event is associated to this case, create a new one
        createEvent(caze.title(), caze.severity().toString, caze.startDate(), simpleAttributes).map {
          case (eventId, exportedAttributes) ⇒ eventId → exportedAttributes.map(_.value.left.get)
        }
      } { eventId ⇒ // if an event already exists, retrieve its attributes in order to export only new one
        getAttributes(mispConnection, eventId, None).map { attributes ⇒
          eventId → attributes.map { attribute ⇒
            (attribute \ "data").asOpt[String].getOrElse((attribute \ "remoteAttachment" \ "filename").as[String])
          }
        }
      }
      newAttributes = uniqueAttributes.filterNot(attr ⇒ existingAttributes.contains(attr.value.fold(identity, _.name)))
      exportedArtifact ← Future.traverse(newAttributes)(attr ⇒ exportAttribute(eventId, attr).toTry)
      alertFields = Fields(Json.obj(
        "type" → "misp",
        "source" → mispName,
        "sourceRef" → eventId,
        "date" → caze.startDate(),
        "lastSyncDate" → new Date(0),
        "case" → caze.id,
        "title" → caze.title(),
        "description" → "Case have been exported to MISP",
        "severity" → caze.severity(),
        "tags" → caze.tags(),
        "tlp" → caze.tlp(),
        "artifacts" → uniqueAttributes.map(_.artifact),
        "status" → "Imported",
        "follow" → false))
      alert ← maybeAlertId.fold(alertSrv.create(alertFields))(alertId ⇒ alertSrv.update(alertId, alertFields))
    } yield alert.id → exportedArtifact
  }

  def convertAttribute(mispAttribute: MispAttribute): Seq[JsObject] = {
    val dataType = attribute2artifact(mispAttribute.tpe)
    val fields = Json.obj(
      "data" → mispAttribute.value,
      "dataType" → dataType,
      "message" → mispAttribute.comment,
      "startDate" → mispAttribute.date,
      "tags" → Json.arr(s"MISP:type=${
        mispAttribute.tpe
      }", s"MISP:category=${
        mispAttribute.category
      }"))

    val types = mispAttribute.tpe.split('|').toSeq
    if (types.length > 1) {
      val values = mispAttribute.value.split('|').toSeq
      val typesValues = types.zipAll(values, "noType", "noValue")
      val additionnalMessage = typesValues
        .map {
          case (t, v) ⇒ s"$t: $v"
        }
        .mkString("\n")
      typesValues.map {
        case (tpe, value) ⇒
          fields +
            ("dataType" → JsString(attribute2artifact(tpe))) +
            ("data" → JsString(value)) +
            ("message" → JsString(mispAttribute.comment + "\n" + additionnalMessage))
      }
    }
    else {
      Seq(fields)
    }
  }

  private def artifact2attribute(dataType: String, data: Option[String]): (String, String) = {
    dataType match {
      case "filename"     ⇒ "Payload delivery" → "filename"
      case "fqdn"         ⇒ "Network activity" → "hostname"
      case "url"          ⇒ "External analysis" → "url"
      case "user-agent"   ⇒ "Network activity" → "user-agent"
      case "domain"       ⇒ "Network activity" → "domain"
      case "ip"           ⇒ "Network activity" → "ip-src"
      case "mail_subject" ⇒ "Payload delivery" → "email-subject"
      case "hash" ⇒ data.fold(0)(_.length) match {
        case 32  ⇒ "Payload delivery" → "md5"
        case 40  ⇒ "Payload delivery" → "sha1"
        case 64  ⇒ "Payload delivery" → "sha256"
        case 56  ⇒ "Payload delivery" → "sha224"
        case 71  ⇒ "Payload delivery" → "sha384"
        case 128 ⇒ "Payload delivery" → "sha512"
        case _   ⇒ "Payload delivery" → "other"
      }
      case "mail"     ⇒ "Payload delivery" → "email-src"
      case "registry" ⇒ "Persistence mechanism" → "regkey"
      case "uri_path" ⇒ "Network activity" → "uri"
      case "regexp"   ⇒ "Other" → "other"
      case "other"    ⇒ "Other" → "other"
      case "file"     ⇒ "Payload delivery" → "malware-sample"
      case _          ⇒ "Other" → "other"
    }
  }

  private def attribute2artifact(tpe: String): String = attribute2artifactLookup.getOrElse(tpe, "other")

  private lazy val attribute2artifactLookup = Map(
    "md5" → "hash",
    "sha1" → "hash",
    "sha256" → "hash",
    "filename" → "filename",
    "pdb" → "other",
    "filename|md5" → "other",
    "filename|sha1" → "other",
    "filename|sha256" → "other",
    "ip-src" → "ip",
    "ip-dst" → "ip",
    "hostname" → "fqdn",
    "domain" → "domain",
    "domain|ip" → "other",
    "email-src" → "mail",
    "email-dst" → "mail",
    "email-subject" → "mail_subject",
    "email-attachment" → "other",
    "float" → "other",
    "url" → "url",
    "http-method" → "other",
    "user-agent" → "user-agent",
    "regkey" → "registry",
    "regkey|value" → "registry",
    "AS" → "other",
    "snort" → "other",
    "pattern-in-file" → "other",
    "pattern-in-traffic" → "other",
    "pattern-in-memory" → "other",
    "yara" → "other",
    "sigma" → "other",
    "vulnerability" → "other",
    "attachment" → "file",
    "malware-sample" → "file",
    "link" → "other",
    "comment" → "other",
    "text" → "other",
    "hex" → "other",
    "other" → "other",
    "named" → "other",
    "mutex" → "other",
    "target-user" → "other",
    "target-email" → "mail",
    "target-machine" → "fqdn",
    "target-org" → "other",
    "target-location" → "other",
    "target-external" → "other",
    "btc" → "other",
    "iban" → "other",
    "bic" → "other",
    "bank-account-nr" → "other",
    "aba-rtn" → "other",
    "bin" → "other",
    "cc-number" → "other",
    "prtn" → "other",
    "threat-actor" → "other",
    "campaign-name" → "other",
    "campaign-id" → "other",
    "malware-type" → "other",
    "uri" → "uri_path",
    "authentihash" → "other",
    "ssdeep" → "hash",
    "imphash" → "hash",
    "pehash" → "hash",
    "impfuzzy" → "hash",
    "sha224" → "hash",
    "sha384" → "hash",
    "sha512" → "hash",
    "sha512/224" → "hash",
    "sha512/256" → "hash",
    "tlsh" → "other",
    "filename|authentihash" → "other",
    "filename|ssdeep" → "other",
    "filename|imphash" → "other",
    "filename|impfuzzy" → "other",
    "filename|pehash" → "other",
    "filename|sha224" → "other",
    "filename|sha384" → "other",
    "filename|sha512" → "other",
    "filename|sha512/224" → "other",
    "filename|sha512/256" → "other",
    "filename|tlsh" → "other",
    "windows-scheduled-task" → "other",
    "windows-service-name" → "other",
    "windows-service-displayname" → "other",
    "whois-registrant-email" → "mail",
    "whois-registrant-phone" → "other",
    "whois-registrant-name" → "other",
    "whois-registrar" → "other",
    "whois-creation-date" → "other",
    "x509-fingerprint-sha1" → "other",
    "dns-soa-email" → "other",
    "size-in-bytes" → "other",
    "counter" → "other",
    "datetime" → "other",
    "cpe" → "other",
    "port" → "other",
    "ip-dst|port" → "other",
    "ip-src|port" → "other",
    "hostname|port" → "other",
    "email-dst-display-name" → "other",
    "email-src-display-name" → "other",
    "email-header" → "other",
    "email-reply-to" → "other",
    "email-x-mailer" → "other",
    "email-mime-boundary" → "other",
    "email-thread-index" → "other",
    "email-message-id" → "other",
    "github-username" → "other",
    "github-repository" → "other",
    "github-organisation" → "other",
    "jabber-id" → "other",
    "twitter-id" → "other",
    "first-name" → "other",
    "middle-name" → "other",
    "last-name" → "other",
    "date-of-birth" → "other",
    "place-of-birth" → "other",
    "gender" → "other",
    "passport-number" → "other",
    "passport-country" → "other",
    "passport-expiration" → "other",
    "redress-number" → "other",
    "nationality" → "other",
    "visa-number" → "other",
    "issue-date-of-the-visa" → "other",
    "primary-residence" → "other",
    "country-of-residence" → "other",
    "special-service-request" → "other",
    "frequent-flyer-number" → "other",
    "travel-details" → "other",
    "payment-details" → "other",
    "place-port-of-original-embarkation" → "other",
    "place-port-of-clearance" → "other",
    "place-port-of-onward-foreign-destination" → "other",
    "passenger-name-record-locator-number" → "other",
    "mobile-application-id" → "other")
}
