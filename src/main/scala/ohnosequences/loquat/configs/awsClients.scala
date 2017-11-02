package ohnosequences.loquat

import com.amazonaws.auth._
import com.amazonaws.regions._
import ohnosequences.awstools


case class AWSClients(
  regionProvider: AwsRegionProvider,
  credentials: AWSCredentialsProvider
) {

  lazy val ec2 = awstools.ec2.clientBuilder
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
    new DefaultAWSCredentialsProviderChain()
  )

  def withRegion(regionProvider: AwsRegionProvider): AWSClients = AWSClients(
    regionProvider,
    new DefaultAWSCredentialsProviderChain()
  )

  def withCredentials(credentials: AWSCredentialsProvider): AWSClients = AWSClients(
    new DefaultAwsRegionProviderChain(),
    credentials
  )
}
