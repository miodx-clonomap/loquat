
```scala
package ohnosequences.loquat

import utils._
import ohnosequences.statika._
import ohnosequences.datasets._
import ohnosequences.awstools._, s3._, sqs._, sns._, autoscaling._, ec2._, regions._
import com.amazonaws.services.autoscaling.model._

import com.typesafe.scalalogging.LazyLogging
import scala.util.Try
import scala.concurrent.duration._
import collection.JavaConversions._


trait AnyLoquat { loquat =>

  type Config <: AnyLoquatConfig
  val  config: Config

  type DataProcessing <: AnyDataProcessingBundle
  val  dataProcessing: DataProcessing

  val dataMappings: List[DataMapping[DataProcessing]]

  lazy val fullName: String = this.getClass.getName.split("\\$").mkString(".")

  // Bundles hierarchy:
  case object worker extends WorkerBundle(dataProcessing, config)

  case object manager extends ManagerBundle(worker)(dataMappings) {
    override lazy val fullName: String = s"${loquat.fullName}.${this.toString}"
  }

  case object managerCompat extends CompatibleWithPrefix(fullName)(config.amiEnv, manager, config.metadata)

  final def check(user: LoquatUser): Unit = LoquatOps.check(config, user, dataProcessing, dataMappings)
  final def deploy(user: LoquatUser): Unit = LoquatOps.deploy(config, user, dataProcessing, dataMappings, managerCompat.userScript)
  final def undeploy(user: LoquatUser): Unit =
    LoquatOps.undeploy(
      config,
      AWSClients(config.region, user.localCredentials),
      TerminateManually
    )
}

abstract class Loquat[
  C <: AnyLoquatConfig,
  DP <: AnyDataProcessingBundle
](val config: C, val dataProcessing: DP
)(val dataMappings: List[DataMapping[DP]]) extends AnyLoquat {

  type Config = C
  type DataProcessing = DP
}



private[loquat]
case object LoquatOps extends LazyLogging {

  def checkDataKeys[DP <: AnyDataProcessingBundle](dataProcessing: DP): Seq[String] = {

    logger.info("Checking data mapping keys...")

    val inputKeys = dataProcessing.input.keys.types.asList.map { _.label }
    val outputKeys = dataProcessing.output.keys.types.asList.map { _.label }

    val inDiff = inputKeys diff inputKeys.toSet.toList
    val outDiff = outputKeys diff outputKeys.toSet.toList

    val errors = {
      if (inDiff.isEmpty)  Seq() else Seq(s"Input dataset has duplicate key labels: ${inDiff}")
    } ++ {
      if (outDiff.isEmpty) Seq() else Seq(s"Input dataset has duplicate key labels: ${outDiff}")
    }

    errors foreach { msg => logger.error(msg) }
    errors
  }

  def checkInputData(aws: AWSClients, dataMappings: List[AnyDataMapping]): Seq[String] = {

    logger.info("Checking input S3 objects existence...")

    dataMappings flatMap { dataMapping =>

      // if an input object doesn't exist, we return an arror message
      dataMapping.remoteInput flatMap {
        case (dataKey, S3Resource(s3address)) => {
          val exists: Boolean = aws.s3.prefixExists(s3address)

          if (exists) print("+") else print("-")

          if (exists) None
          else Some(s"Input object [${dataKey.label}] doesn't exist at the address: [${s3address}]")
        }
        // if the mapping is not an S3Resource, we don't check
        case _ => None
      }
    }
  }

  def check[DP <: AnyDataProcessingBundle](
    config: AnyLoquatConfig,
    user: LoquatUser,
    dataProcessing: DP,
    dataMappings: List[AnyDataMapping]
  ): Either[String, AWSClients] = {

    if (checkDataKeys(dataProcessing).nonEmpty) {
      Left("DataMapping definition is invalid")

    } else if (Try( user.localCredentials.getCredentials ).isFailure) {
      Left(s"Couldn't load local credentials: ${user.localCredentials}")

    } else {
      val aws = AWSClients(config.region, user.localCredentials)

      if(user.validateWithLogging(aws).nonEmpty) Left("User validation failed")
      else if (config.validateWithLogging(aws).nonEmpty) Left("Config validation failed")
      else {
        logger.info("Checking that data mappings define all the needed data keys...")

        val invalidDM = dataMappings.find { _.checkDataKeys.nonEmpty }

        invalidDM match {

          case Some(dm) => {
            dm.checkDataKeys foreach { msg => logger.error(msg) }
            Left("Some dataMappings are invalid")
          }

          case None => if (config.checkInputObjects) {
            val missingInputs = checkInputData(aws, dataMappings)
            missingInputs foreach { msg => logger.error(msg) }

            if (missingInputs.nonEmpty) Left("Some input data is missing")
            else Right(aws)
          } else Right(aws)
        }
      }
    }

  }


  def deploy[DP <: AnyDataProcessingBundle](
    config: AnyLoquatConfig,
    user: LoquatUser,
    dataProcessing: DP,
    dataMappings: List[AnyDataMapping],
    managerUserScript: String
  ): Unit = {

    LoquatOps.check(config, user, dataProcessing, dataMappings) match {
      case Left(msg) => logger.error(msg)
      case Right(aws) => {

        val names = config.resourceNames

        logger.info(s"Deploying loquat: ${config.loquatId}")


        Seq(
          Step( s"Creating input queue: ${names.inputQueue}" )(
            aws.sqs.getOrCreateQueue(names.inputQueue).map {
              _.setVisibilityTimeout(30.minutes)
            }
          ),
          Step( s"Creating output queue: ${names.outputQueue}" )(
            aws.sqs.getOrCreateQueue(names.outputQueue)
          ),
          Step( s"Creating error queue: ${names.errorQueue}" )(
            aws.sqs.getOrCreateQueue(names.errorQueue)
          ),
          Step( s"Checking logs bucket: ${names.logs}" )(
            Try {
              val logsBucket = names.logs.bucket

              if(aws.s3.doesBucketExist(logsBucket)) {
                logger.info(s"Bucket [${logsBucket}] already exists.")
              } else {
                logger.info(s"Bucket [${logsBucket}] doesn't exists. Trying to create it.")
                aws.s3.createBucket(logsBucket)
              }
            }
          ),
          Step( s"Creating notification topic: ${names.notificationTopic}" )(
            aws.sns.getOrCreateTopic(names.notificationTopic).map { topic =>

              if (!topic.subscribed(Subscriber.email(user.email.toString))) {

                logger.info(s"Subscribing [${user.email}] to the notification topic")
                topic.subscribe(Subscriber.email(user.email.toString))
                logger.info("Check your email and confirm subscription")
              }
            }
          ),
          Step( s"Creating manager launch configuration: ${names.managerLaunchConfig}" )(

            aws.as.createLaunchConfig(
              names.managerLaunchConfig,
              config.managerConfig.purchaseModel,
              LaunchSpecs(
                ami = config.managerConfig.ami,
                instanceType = config.managerConfig.instanceType,
                keyName = user.keypairName,
                userData = managerUserScript,
                iamProfileName = Some(config.iamRoleName),
                deviceMappings = config.managerConfig.deviceMapping
              )(config.managerConfig.supportsAMI)
            ).recover {
              case _: AlreadyExistsException => logger.warn(s"Manager launch configuration already exists")
            }
          ),
          Step( s"Creating manager group: ${names.managerGroup}" )(
            aws.as.createGroup(
              names.managerGroup,
              names.managerLaunchConfig,
              config.managerConfig.groupSize,
              if  (config.managerConfig.availabilityZones.isEmpty) aws.ec2.getAllAvailableZones
              else config.managerConfig.availabilityZones
            )
          ),
          Step( s"Tagging manager group" )(
            aws.as.setTags(names.managerGroup, Map(
              "product" -> "loquat",
              "group"   -> names.managerGroup,
              StatusTag.label -> StatusTag.preparing.status
            ))
          ),
          Step("Loquat is running, now go to the amazon console and keep an eye on the progress")(
            util.Success(true)
          )
        ).foldLeft[Try[_]] {
          logger.info("Creating resources...")
            util.Success(true)
        } { (result: Try[_], next: Step[_]) =>
          result.flatMap(_ => next.execute)
        }

      }

    }

  }


  def undeploy(
    config: AnyLoquatConfig,
    aws: AWSClients,
    reason: AnyTerminationReason
  ): Unit = {
    logger.info(s"Undeploying loquat: ${config.loquatId}")

    val names = config.resourceNames

    Step("Sending notification on your email")(
      aws.sns
        .getOrCreateTopic(names.notificationTopic)
        .map { _.publish(reason.msg, s"Loquat ${config.loquatId} is terminated") }
    ).execute

    Step(s"deleting workers group: ${names.workersGroup}")(
      aws.as.deleteGroup(names.workersGroup)
    ).execute

    Step(s"deleting workers launch config: ${names.workersLaunchConfig}")(
      aws.as.deleteLaunchConfig(names.workersLaunchConfig)
    ).execute

    Step(s"deleting error queue: ${names.errorQueue}")(
      aws.sqs.getQueue(names.errorQueue).flatMap(_.delete)
    ).execute

    Step(s"deleting output queue: ${names.outputQueue}")(
      aws.sqs.getQueue(names.outputQueue).flatMap(_.delete)
    ).execute

    Step(s"deleting input queue: ${names.inputQueue}")(
      aws.sqs.getQueue(names.inputQueue).flatMap(_.delete)
    ).execute

    Step(s"deleting manager group: ${names.managerGroup}")(
      aws.as.deleteGroup(names.managerGroup)
    ).execute

    Step(s"deleting manager launch config: ${names.managerLaunchConfig}")(
      aws.as.deleteLaunchConfig(names.managerLaunchConfig)
    ).execute

    logger.info("Loquat is undeployed")
  }


  // These ops are useful for a running loquat. Use them from REPL (sbt console)
  // TODO: restore this code

  // def addDataMappings(loquat: AnyLoquat, dataMappings: List[AnyDataMapping]): Unit = {
  //
  //   val sqs = SQS.create(loquat.config.localCredentials)
  //   val inputQueue = sqs.get(loquat.config.resourceNames.inputQueue).get
  //   dataMappings.foreach {
  //     t => inputQueue.sendMessage(upickle.default.write[SimpleDataMapping](t))
  //   }
  // }
  //
  // def updateWorkersGroupSize(loquat: AnyLoquat, groupSize: WorkersGroupSize): Unit = {
  //
  //   val asClient = AutoScaling.create(loquat.config.localCredentials, loquat.resources.aws.ec2).as
  //   asClient.updateAutoScalingGroup(new UpdateAutoScalingGroupRequest()
  //     .withAutoScalingGroupName(loquat.config.workersAutoScalingGroup.name)
  //     .withMinSize(groupSize.min)
  //     .withDesiredCapacity(groupSize.desired)
  //     .withMaxSize(groupSize.max)
  //   )
  // }
}

```




[main/scala/ohnosequences/loquat/configs/autoscaling.scala]: configs/autoscaling.scala.md
[main/scala/ohnosequences/loquat/configs/awsClients.scala]: configs/awsClients.scala.md
[main/scala/ohnosequences/loquat/configs/general.scala]: configs/general.scala.md
[main/scala/ohnosequences/loquat/configs/loquat.scala]: configs/loquat.scala.md
[main/scala/ohnosequences/loquat/configs/resources.scala]: configs/resources.scala.md
[main/scala/ohnosequences/loquat/configs/termination.scala]: configs/termination.scala.md
[main/scala/ohnosequences/loquat/configs/user.scala]: configs/user.scala.md
[main/scala/ohnosequences/loquat/dataMappings.scala]: dataMappings.scala.md
[main/scala/ohnosequences/loquat/dataProcessing.scala]: dataProcessing.scala.md
[main/scala/ohnosequences/loquat/logger.scala]: logger.scala.md
[main/scala/ohnosequences/loquat/loquats.scala]: loquats.scala.md
[main/scala/ohnosequences/loquat/manager.scala]: manager.scala.md
[main/scala/ohnosequences/loquat/terminator.scala]: terminator.scala.md
[main/scala/ohnosequences/loquat/utils.scala]: utils.scala.md
[main/scala/ohnosequences/loquat/worker.scala]: worker.scala.md
[test/scala/ohnosequences/loquat/test/config.scala]: ../../../../test/scala/ohnosequences/loquat/test/config.scala.md
[test/scala/ohnosequences/loquat/test/data.scala]: ../../../../test/scala/ohnosequences/loquat/test/data.scala.md
[test/scala/ohnosequences/loquat/test/dataMappings.scala]: ../../../../test/scala/ohnosequences/loquat/test/dataMappings.scala.md
[test/scala/ohnosequences/loquat/test/dataProcessing.scala]: ../../../../test/scala/ohnosequences/loquat/test/dataProcessing.scala.md
[test/scala/ohnosequences/loquat/test/md5.scala]: ../../../../test/scala/ohnosequences/loquat/test/md5.scala.md