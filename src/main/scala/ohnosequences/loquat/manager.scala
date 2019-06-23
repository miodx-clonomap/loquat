package ohnosequences.loquat

import utils._

import ohnosequences.statika._

import com.typesafe.scalalogging.LazyLogging

import com.amazonaws.auth._
import com.amazonaws.PredefinedClientConfigurations
import com.amazonaws.services.autoscaling.model._
import ohnosequences.awstools._, sqs._, ec2._, autoscaling._, regions._

import java.util.concurrent.Executors
import scala.concurrent._, duration._
import scala.util.Try


// We don't want it to be used outside of this project
trait AnyManagerBundle extends AnyBundle with LazyLogging { manager =>

  val fullName: String

  type Worker <: AnyWorkerBundle
  val  worker: Worker

  val config = worker.config

  val dataMappings: List[DataMapping[Worker#DataProcessingBundle]]

  case object workerCompat extends CompatibleWithPrefix(fullName)(
    environment = config.amiEnv,
    bundle = worker,
    metadata = config.metadata
  ) {
    override lazy val fullName: String = s"${manager.fullName}.${this.toString}"
  }

  lazy final val scheduler = Scheduler(2)

  lazy val loggerBundle = LogUploaderBundle(config, scheduler)
  lazy val terminationBundle = TerminationDaemonBundle(config, scheduler, dataMappings.length)

  val bundleDependencies: List[AnyBundle] = List[AnyBundle](
    loggerBundle,
    terminationBundle
  )

  lazy val aws = AWSClients.withRegion(config.region)

  lazy val names = config.resourceNames


  def uploadInitialDataMappings(credentials: AWSCredentialsProvider): Try[Unit] = {

    val sqsClient = sqs.clientBuilder
      .withCredentials(credentials)
      .withRegion(config.region.getName)
      .withClientConfiguration(
        // TODO: 100 connections? more?
        PredefinedClientConfigurations.defaultConfig.withMaxConnections(100)
      ).build()

    val queue: Try[Queue] = sqsClient.getQueue(names.inputQueue)
      .recoverWith { case t =>
        logger.error(s"Couldn't access input queue: ${names.inputQueue}")
        scala.util.Failure[Queue](t)
      }

    val msgs: Iterator[String] = dataMappings.toIterator.zipWithIndex.map { case (dataMapping, ix) =>
      SimpleDataMapping(
        id = ix.toString,
        inputs = toMap(dataMapping.remoteInput),
        outputs = toMap(dataMapping.remoteOutput)
      ).serialize
    }


    // Sending initial datamappings in parallel
    val executor = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(30))

    val tryToSend: Try[SendBatchResult] = queue.map { inputQueue =>
      logger.debug("Adding initial dataMappings to SQS")

      Await.result(inputQueue.sendBatch(msgs)(executor), 1.hour)
    }

    executor.shutdown()

    tryToSend.flatMap { result =>
      if (result.failures.nonEmpty) {
        // Probably printing all errors is too much, this is just to see which kinds of errors occure
        logger.error("Failed to batch send initial dataMappings:")
        result.failures.take(10).foreach { case (msg, err) =>
          logger.error(s"Error ${err.getCode}: ${err.getMessage} (sender fault: ${err.getSenderFault}). \n${msg}\n")
        }
        // TODO: better exception here?
        scala.util.Failure(new RuntimeException("Failed to upload initial dataMappings"))
      } else {
        logger.info("Initial dataMappings are ready")
        scala.util.Success( () )
      }
    }

  }

  // NOTE: it can take either manager.aws or those based on the local user credentials
  def prepareWorkers(aws: AWSClients, keypairName: String): AnyInstructions = {
    LazyTry {
      logger.debug("Creating workers launch configuration")

      aws.as.createLaunchConfig(
        names.workersLaunchConfig,
        config.workersConfig.purchaseModel,
        LaunchSpecs(
          ami = config.workersConfig.ami,
          instanceType = config.workersConfig.instanceType,
          keyName = keypairName,
          userData = workerCompat.userScript,
          iamProfileName = Some(config.iamRoleName),
          deviceMappings = config.workersConfig.deviceMapping
        )(config.workersConfig.supportsAMI)
      ).recover {
        case _: AlreadyExistsException => logger.warn(s"Workers launch configuration already exists")
      }.get
    } -&-
    LazyTry {
      logger.debug("Creating workers autoscaling group")
      aws.as.createGroup(
        names.workersGroup,
        names.workersLaunchConfig,
        config.workersConfig.groupSize,
        if  (config.workersConfig.availabilityZones.isEmpty) aws.ec2.getAllAvailableZones
        else config.workersConfig.availabilityZones
      ).recover {
        case _: AlreadyExistsException => logger.warn(s"Autoscaling group already exists")
      }.get
    } -&-
    LazyTry {
      logger.debug("Creating tags for workers autoscaling group")
      aws.as.setTags(names.workersGroup, Map(
        "product" -> "loquat",
        "group"   -> names.workersGroup,
        StatusTag.label -> StatusTag.running.status
      )).recover {
        case _: AlreadyExistsException => logger.warn(s"Tags already exists")
      }.get
    }
  }

  def localInstructions(user: LoquatUser): AnyInstructions = {
    LazyTry {
      logger.debug("Uploading initial dataMappings to the input queue")
      uploadInitialDataMappings(user.localCredentials).get
    } -&-
    prepareWorkers(
      AWSClients.withCredentials(user.localCredentials),
      user.keypairName
    )
  }

  private def normalScenario: Instructions[Unit] = {
    LazyTry {
      logger.debug("Uploading initial dataMappings to the input queue")

      manager.aws.as.tagValue(
        names.managerGroup,
        StatusTag.label
      ) match {
        case scala.util.Success(StatusTag.running.status) => {
          logger.info("DataMappings are supposed to be in the queue already")
        }
        case _ => uploadInitialDataMappings(new DefaultAWSCredentialsProviderChain()) map { _ =>
          logger.info("Tagging manager group as running")
          manager.aws.as.setTags(
            names.managerGroup,
            Map(StatusTag.label -> StatusTag.running.status)
          )
        }
      }
    } -&-
    prepareWorkers(
      manager.aws,
      manager.aws.as.getLaunchConfig(names.managerLaunchConfig).map(_.getKeyName).get
    ) -&-
    say("manager installed")
  }

  private def failScenario: Instructions[Unit] = {
    LazyTry {
      logger.error("Manager failed, trying to restart it")

      loggerBundle.failureNotification(
        s"Loquat ${config.loquatId} manager failed during installation and will be restarted"
      )

      manager.aws.ec2.getCurrentInstance.foreach(_.terminate)
    } -&-
    failure[Unit]("Manager failed during installation")
  }

  def instructions: AnyInstructions = normalScenario -|- failScenario
}

abstract class ManagerBundle[W <: AnyWorkerBundle](val worker: W)
  (val dataMappings: List[DataMapping[W#DataProcessingBundle]])
  extends AnyManagerBundle { type Worker = W }
