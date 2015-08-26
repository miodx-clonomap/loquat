package ohnosequences.loquat

import dataMappings._, instructions._, configs._, utils._

import ohnosequences.statika.bundles._

import ohnosequences.awstools.AWSClients
import ohnosequences.awstools.sqs._
import ohnosequences.awstools.autoscaling._

import com.amazonaws.services.autoscaling.model._
import com.typesafe.scalalogging.LazyLogging

import scala.util._


trait AnyLoquat { loquat =>

  type Config <: AnyLoquatConfig
  val  config: Config

  type InstructionsBundle <: AnyInstructionsBundle
  val  instructionsBundle: InstructionsBundle

  lazy val fullName: String = this.getClass.getName.split("\\$").mkString(".")

  // Bundles hierarchy:
  case object worker extends WorkerBundle(instructionsBundle, config)

  case object manager extends ManagerBundle(worker) {
    override lazy val fullName: String = s"${loquat.fullName}.${this.toString}"
  }

  case object managerCompat extends CompatibleWithPrefix(fullName)(config.ami, manager, config.metadata)

  final def deploy(): Unit = LoquatOps.deploy(config, managerCompat.userScript)
  final def undeploy(): Unit = LoquatOps.undeploy(config)
}

abstract class Loquat[
  C <: AnyLoquatConfig,
  I <: AnyInstructionsBundle
](val config: C, val instructionsBundle: I) extends AnyLoquat {

  type Config = C
  type InstructionsBundle = I
}



protected[loquat] case object LoquatOps extends LazyLogging {

  def deploy(
    config: AnyLoquatConfig,
    managerUserScript: String
  ): Unit = {
    logger.info(s"deploying loquat: ${config.loquatName} v${config.loquatVersion}")

    val aws = AWSClients.create(config.localCredentials)
    val names = config.resourceNames

    if(config.validate.nonEmpty)
      logger.error("Config validation failed. Fix config and try to deploy again.")
    else {

      Seq(
        Step( s"Creating input queue: ${names.inputQueue}" )(
          Try { aws.sqs.createQueue(names.inputQueue) }
        ),
        Step( s"Creating output queue: ${names.outputQueue}" )(
          Try { aws.sqs.createQueue(names.outputQueue) }
        ),
        Step( s"Creating error queue: ${names.errorQueue}" )(
          Try { aws.sqs.createQueue(names.errorQueue) }
        ),
        Step( s"Creating temporary bucket: ${names.bucket}" )(
          Try { aws.s3.createBucket(names.bucket) }
        ),
        Step( s"Creating notification topic: ${config.notificationTopic}" )(
          Try { aws.sns.createTopic(config.notificationTopic) }
            .map { topic =>
              if (!topic.isEmailSubscribed(config.email.toString)) {
                logger.info(s"subscribing [${config.email}] to the notification topic")
                topic.subscribeEmail(config.email.toString)
                logger.info("check your email and confirm subscription")
              }
            }
        ),
        Step( s"Creating manager group: ${config.managerAutoScalingGroup.name}" )(
          Try { aws.as.fixAutoScalingGroupUserData(config.managerAutoScalingGroup, managerUserScript) }
            .map { managerGroup =>
              aws.as.createAutoScalingGroup(managerGroup)
              utils.tagAutoScalingGroup(aws.as, managerGroup.name, "manager")
            }
        ),
        Step("loquat is running, now go to the amazon console and keep an eye on the progress")(Success(true))
      ).foldLeft[Try[_]](
        { logger.info("creating resources..."); Success(true) }
      ) { (result: Try[_], next: Step[_]) =>
        result.flatMap(_ => next.execute)
      }

    }
  }


  def undeploy(config: AnyLoquatConfig): Unit = {
    logger.info(s"undeploying loquat: ${config.loquatName} v${config.loquatVersion}")

    val aws = AWSClients.create(config.localCredentials)
    val names = config.resourceNames

    Step("sending notification on your email")(
      Try {
        val subject = "Loquat " + config.loquatId + " is terminated"
        val notificationTopic = aws.sns.createTopic(config.notificationTopic)
        notificationTopic.publish("manual termination", subject)
      }
    ).execute

    Step(s"deleting workers group: ${config.workersAutoScalingGroup.name}")(
      Try { aws.as.deleteAutoScalingGroup(config.workersAutoScalingGroup) }
    ).execute

    Step(s"deleting temporary bucket: ${names.bucket}")(
      Try { aws.s3.deleteBucket(names.bucket) }
    ).execute

    Step(s"deleting error queue: ${names.errorQueue}")(
      Try { aws.sqs.getQueueByName(names.errorQueue).foreach(_.delete) }
    ).execute

    Step(s"deleting output queue: ${names.outputQueue}")(
      Try { aws.sqs.getQueueByName(names.outputQueue).foreach(_.delete) }
    ).execute

    Step(s"deleting input queue: ${names.inputQueue}")(
      Try { aws.sqs.getQueueByName(names.inputQueue).foreach(_.delete) }
    ).execute

    Step(s"deleting manager group: ${config.managerAutoScalingGroup.name}")(
      Try { aws.as.deleteAutoScalingGroup(config.managerAutoScalingGroup) }
    ).execute

    logger.info("loquat is undeployed")
  }


  // These ops are useful for a running loquat. Use them from REPL (sbt console)
  // TODO: restore this code

  // def addDataMappings(loquat: AnyLoquat, dataMappings: List[AnyDataMapping]): Unit = {
  //
  //   val sqs = SQS.create(loquat.config.localCredentials)
  //   val inputQueue = sqs.getQueueByName(loquat.config.resourceNames.inputQueue).get
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