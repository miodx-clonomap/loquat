package ohnosequences.loquat

import utils._, files._
import ohnosequences.statika._
import ohnosequences.datasets._
import ohnosequences.awstools._, s3._, ec2._, regions._
import com.amazonaws.services.sqs.model.ReceiptHandleIsInvalidException
import ohnosequences.awstools._, sqs._, s3._, ec2._

import com.typesafe.scalalogging.LazyLogging
import scala.concurrent._, duration._
import scala.util, util.Try
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors

trait AnyWorkerBundle extends AnyBundle {

  type DataProcessingBundle <: AnyDataProcessingBundle
  val  instructionsBundle: DataProcessingBundle

  type Config <: AnyLoquatConfig
  val  config: Config

  lazy val loggerBundle = LogUploaderBundle(config, Scheduler(1))

  val bundleDependencies: List[AnyBundle] = List(
    loggerBundle,
    instructionsBundle
  )

  lazy val workerContex = WorkerContext(config, instructionsBundle)

  def instructions: AnyInstructions = LazyTry {
    // TODO: choose better execution context
    // workerContex.taskLoop(ExecutionContext.Implicits.global)
    workerContex.taskLoop(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3)))
  }
}

abstract class WorkerBundle[
  I <: AnyDataProcessingBundle,
  C <: AnyLoquatConfig
](val instructionsBundle: I,
  val config: C
) extends AnyWorkerBundle {

  type DataProcessingBundle = I
  type Config = C
}


/* General context per-worker */
case class WorkerContext(
  val config: AnyLoquatConfig,
  val instructionsBundle: AnyDataProcessingBundle
) extends LazyLogging { workerContex =>
  // This scheduler wiil be used to keep SQS message in-flight
  val scheduler = Scheduler(1)

  lazy val workingDir: File = file("/media/ephemeral0/applicator/loquat")
  lazy val inputDir:  File = workingDir / "input"
  lazy val outputDir: File = workingDir / "output"

  lazy val aws = AWSClients.withRegion(config.region)

  val inputQueue  = aws.sqs.getQueue(config.resourceNames.inputQueue).get
  val errorQueue  = aws.sqs.getQueue(config.resourceNames.errorQueue).get
  val outputQueue = aws.sqs.getQueue(config.resourceNames.outputQueue).get

  val transferManager = aws.s3.createTransferManager
  // TODO: it should be shutdown explicitly at some point
  // transferManager.shutdown()

  val instance = aws.ec2.getCurrentInstance.get

  /* This metadata will be attached to each uploaded S3 object */
  val s3Metadata: Map[String, String] = Map(
    "artifact-org"     -> config.metadata.organization,
    "artifact-name"    -> config.metadata.artifact,
    "artifact-version" -> config.metadata.version,
    "artifact-url"     -> config.metadata.artifactUrl
  )

  /* Waits for a task-message from the input queue. It is supposed to wait and send requests as long as needed. */
  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def receiveMessage: Try[Message] = {
    logger.info("Data processor is waiting for new data...")

    inputQueue.poll(
      timeout = Duration.Inf,
      amountLimit = Some(1),
      adjustRequest = { _.withWaitTimeSeconds(10) }
    ).map { _.head }
  }

  /* This is a _full_ iteration of the task processing loop */
  def taskLoop(implicit ec: ExecutionContext): Future[FiniteDuration] = {

    Future.fromTry(receiveMessage)
      .flatMap { message =>
        TaskContext(
          message,
          Deadline.now
        )(workerContex).processTask
      }
      .flatMap { _ =>
        taskLoop
      }
  }
}


/* Local context per-task */
case class TaskContext(
  val message: Message,
  val startTime: Deadline
)(val workerContex: WorkerContext) extends LazyLogging {
  import workerContex._

  val dataMapping = SimpleDataMapping.deserialize(message.body)

  def timeSpent: FiniteDuration = -startTime.timeLeft

  // This timer keeps task message in-flight while the data is being processed
  val keeper: ScheduledFuture[_] = scheduler.repeat(
    after = 0.seconds,
    every = (config.sqsInitialTimeout.toMillis * 0.9).millis
  ) {
    logger.debug(s"Keeping message ${dataMapping.id} in flight (${timeSpent.toSeconds}s)")
    message.changeVisibility(config.sqsInitialTimeout).recover {
      case ex: ReceiptHandleIsInvalidException => {
        logger.warn(s"Task ${dataMapping.id} is already done by somebody")
        keeper.cancel(true)
        ()
      }
    }
  }

  /* This is the main method that conbines all the other methods */
  def processTask(implicit ec: ExecutionContext): Future[FiniteDuration] = {
    logger.info(s"Started task ${dataMapping.id}")
    prepareInputData
      .map(processFiles)
      .flatMap(finishTask)
      .map { _ =>
        keeper.cancel(true)
        logger.info(s"Task ${dataMapping.id} took ${timeSpent.toSeconds}s")
        timeSpent
      }
  }

  /* This method downloads one input data resource and returns the created file */
  private def downloadInput(
    name: String,
    resource: AnyRemoteResource
  ): File = resource match {
    /* - if the resource is a message, we just write it to a file */
    case MessageResource(msg) => {
      logger.debug(s"Input [${name}]: writing message to a file")
      val file = (inputDir / name).overwrite(msg)
      logger.debug(s"Input [${name}]: written to ${file.path}")
      file
    }
    /* - if the resource is an S3 object/folder, we download it */
    case S3Resource(s3Address) => {
      // NOTE: if it is an S3 folder, destination will be inputDir/name/<s3Address.key>/
      logger.debug(s"Input [${name}]: downloading [${s3Address}]")
      import ohnosequences.awstools.s3._
      val file = transferManager.download(
        s3Address,
        inputDir / name
      ).get
      logger.debug(s"Input [${name}]: downloaded to [${file.path}]")
      file
    }
  }

  /* This downloads input files for the given datamapping in parallel. */
  def prepareInputData(implicit ec: ExecutionContext): Future[Map[String, File]] = {
    logger.debug(s"Cleaning up...")
    workingDir.deleteRecursively()
    inputDir.createDirectory
    outputDir.createDirectory

    logger.debug("Downloading input data...")
    Future.traverse(dataMapping.inputs) { case (name, resource) =>
      Future(name -> downloadInput(name, resource))
    }.map { _.toMap }
  }

  def processFiles(
    inputFiles: Map[String, File]
  ): Map[String, File] = {
    logger.debug("Processing data...")
    instructionsBundle.runProcess(workingDir, inputFiles) match {
      case Success(_, outputFiles) => outputFiles
      case Failure(errors) => {

        val errorsMessage = errors.mkString("\n")
        logger.error(s"Data processing failed, publishing it to the error queue '${config.resourceNames.errorQueue}'.\nErrors:" + errorsMessage)
        errorQueue.sendOne(
          // TODO: attach normal log
          ProcessingResult(instance.id, errorsMessage).toString
        )
        // TODO: make a specific exception
        throw new Throwable(errorsMessage)
      }
    }
  }

  /* This method uploads one output file and returns the destination S3 address or `None` if the files was empty and could be skept */
  private def uploadOutput(
    file: File,
    destination: AnyS3Address
  ): Option[AnyS3Address] = {
    if (config.skipEmptyResults && file.isEmpty) {
      logger.info(s"Output file [${file.getName}] is empty. Skipping it.")
      None
    } else {
      logger.info(s"Publishing output object: [${file.getName}]")
      transferManager.upload(
        file,
        destination,
        s3Metadata,
        true // silent
      ).toOption
    }
  }

  /* This method uploads all output files, sends a success-message and (!) deletes the task-message from the input queue */
  def finishTask(
    outputFiles: Map[String, File]
  )(implicit ec: ExecutionContext): Future[Unit] = {
    logger.info("Uploading output files...")
    Future.traverse(outputFiles) { case (name, file) =>
      Future(uploadOutput(file, dataMapping.outputs(name).resource))
    }.map { _ =>
      logger.debug("Sending success message to the output queue...")
      outputQueue.sendOne(
        // TODO: attach normall log
        ProcessingResult(instance.id, dataMapping.id).toString
      ).get
    }.map { _ =>
      logger.debug("Deleting task message from the input queue...")
      message.delete().get
    }
  }

  /* Publishes an error message to the SQS queue */
  def publishError(msg: String): Try[MessageId] = {
    logger.error(msg)
    // TODO: should publish relevant part of the log
    // TODO: construct a JSON?
    errorQueue.sendOne(s"Instance: ${instance.id}; Task: ${dataMapping.id}; Reason: ${msg}")
  }

  /* Enter the void... */
  def suicide(reason: Throwable): Unit = {
    // TODO: SNS notifications on critical failures
    publishError(s"Instance is terminated due to a fatal exception: ${reason}")
    instance.terminate
    // throw the suicide note
    throw reason
  }

}
