package ohnosequences.loquat

import ohnosequences.awstools.ec2._
import ohnosequences.awstools.autoscaling._


trait AnyAutoScalingConfig extends Config() { conf =>

  type InstanceSpecs <: AnyInstanceSpecs
  val  instanceSpecs: InstanceSpecs

  type PurchaseModel <: AnyPurchaseModel
  val  purchaseModel: PurchaseModel

  val groupSize: AutoScalingGroupSize

  /* Preferred availability zones, if empty, set to all available zones */
  val availabilityZones: List[String]

  // TODO: use some better type for this
  val deviceMapping: Map[String, String]

  def validationErrors: Seq[String] = {

    val groupSizeErros: Seq[String] = {
      if ( groupSize.min < 0 ) Seq(s"Minimal autoscaling group size has to be non-negative: ${groupSize.min}")
      else if (
        groupSize.desired < groupSize.min ||
        groupSize.desired > groupSize.max
      ) Seq(s"Desired capacity [${groupSize.desired}] has to be in the interval [${groupSize.min}, ${groupSize.max}]")
      else Seq()
    }

    val purchaseModelErrors: Seq[String] = purchaseModel match {
      case Spot(Some(price), Some(delta)) if price <= 0 || delta < 0 =>
        Seq(s"Spot price has to be positive: ${price}")
      case _ => Seq()
    }

    groupSizeErros ++ purchaseModelErrors
  }

  def autoScalingGroup(
    groupName: String,
    keypairName: String,
    iamRoleName: String
  ): AutoScalingGroup =
    AutoScalingGroup(
      name = groupName,
      size = conf.groupSize,
      launchConfiguration = LaunchConfiguration(
        name = groupName + "-launchConfiguration",
        purchaseModel = conf.purchaseModel,
        launchSpecs = LaunchSpecs(
          conf.instanceSpecs
        )(keyName = keypairName,
          instanceProfile = Some(iamRoleName),
          deviceMapping = conf.deviceMapping
        )
      ),
      availabilityZones = conf.availabilityZones
    )

}

/* Manager autoscaling group configuration */
trait AnyManagerConfig extends AnyAutoScalingConfig {

  val groupSize = AutoScalingGroupSize(1, 1, 1)
  val deviceMapping = Map[String, String]()
}

case class ManagerConfig[
  IS <: AnyInstanceSpecs,
  PM <: AnyPurchaseModel
](instanceSpecs: IS,
  purchaseModel: PM,
  availabilityZones: List[String] = List()
) extends AnyManagerConfig {

  type InstanceSpecs = IS
  type PurchaseModel = PM
}

/* Workers autoscaling group configuration */

trait AnyWorkersConfig extends AnyAutoScalingConfig

case class WorkersConfig[
  IS <: AnyInstanceSpecs,
  PM <: AnyPurchaseModel
](instanceSpecs: IS,
  purchaseModel: PM,
  groupSize: AutoScalingGroupSize,
  availabilityZones: List[String] = List(),
  // TODO: use some better type for this
  deviceMapping: Map[String, String] = Map("/dev/sdb" -> "ephemeral0")
) extends AnyWorkersConfig {

  type InstanceSpecs = IS
  type PurchaseModel = PM
}