package ohnosequences.loquat

import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions._
import com.amazonaws.services.ec2.AmazonEC2
import ohnosequences.awstools


case class AWSClients(
  regionProvider: AwsRegionProvider,
  credentials: AWSCredentialsProvider
) {

  lazy val ec2: AmazonEC2 = awstools.ec2.clientBuilder
    .withRegion(regionProvider.getRegion)
    .withCredentials(credentials)
    .build()

  lazy val sns = awstools.sns.clientBuilder
    .withRegion(regionProvider.getRegion)
    .withCredentials(credentials)
    .build()

  lazy val sqs = awstools.sqs.clientBuilder
    .withRegion(regionProvider.getRegion)
    .withCredentials(credentials)
    .build()

  lazy val s3  = awstools.s3.clientBuilder
    .withRegion(regionProvider.getRegion)
    .withCredentials(credentials)
    .build()

  lazy val as  = awstools.autoscaling.clientBuilder
    .withRegion(regionProvider.getRegion)
    .withCredentials(credentials)
    .build()
}

case object AWSClients {

  def apply(): AWSClients = AWSClients(
    new DefaultAwsRegionProviderChain(),
    credentialsProvider//new DefaultAWSCredentialsProviderChain()
  )

  def withRegion(regionProvider: AwsRegionProvider): AWSClients = AWSClients(
    regionProvider,
    credentialsProvider//new DefaultAWSCredentialsProviderChain()
  )

  def withCredentials(credentials: AWSCredentialsProvider): AWSClients = AWSClients(
    new DefaultAwsRegionProviderChain(),
    credentials
  )

  def credentialsProvider: AWSCredentialsProviderChain = new AWSCredentialsProviderChain(
    new EC2ContainerCredentialsProviderWrapper(),
    new InstanceProfileCredentialsProvider(false),
    new ProfileCredentialsProvider("default")
  )

}
