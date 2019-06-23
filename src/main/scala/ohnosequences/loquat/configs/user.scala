package ohnosequences.loquat

import ohnosequences.awstools.ec2._
import com.amazonaws.auth.AWSCredentialsProvider
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

/* Simple type to separate user-related data from the config */
case class LoquatUser(
  /* email address for notifications */
  val email: String,
  /* these are credentials that are used to launch loquat */
  val localCredentials: AWSCredentialsProvider,
  /* keypair name for connecting to the loquat instances */
  val keypairName: String
) extends Config("User config")() {

  def    check[L <: AnyLoquat](l: L): Unit = l.check(this)
  def   deploy[L <: AnyLoquat](l: L): Unit = l.deploy(this)
  def undeploy[L <: AnyLoquat](l: L): Unit = l.undeploy(this)

  def validationErrors(aws: AWSClients): Seq[String] = {

    val emailErr =
      if (email.contains('@')) Seq()
      else Seq(s"User email [${email}] has invalid format")

    emailErr ++ {
      val tryKeypairAvailable = aws.ec2.keyPairExists(keypairName)
      tryKeypairAvailable match {
        case Success(true) => Seq()
        case Success(false) => Seq(s"Keypair [${keypairName}] doesn't exist")
        case Failure(exception) =>
          LoggerFactory.getLogger(getClass).warn("Error checking for keypair existence ", exception)
          Seq("Error checking for keypair existence " + exception.getMessage)
      }
    }
  }

}
