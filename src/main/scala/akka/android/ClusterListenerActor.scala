package akka.android

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.io.{IO, Udp}
import akka.util.ByteString
import java.net.InetSocketAddress
import scala.concurrent.duration._

case object PongTimeout

class ClusterListenerActor(activity: Act) extends Actor {
  final val TAG = "ClusterListenerActor"

  implicit def system = context.system
  implicit def executionContext = context.system.dispatcher
  private def onUI(block: => Unit) = activity.runOnUiThread(new Runnable { def run() = block })

  activity.log(TAG, "Trying to bind to UDP address " + new InetSocketAddress(2551))
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(2551), List(Udp.SO.Broadcast(true)))

  val cluster = Cluster(context.system)
  activity.log(TAG, "Cluster self address is " + cluster.selfAddress)

  def receive = waitingForBinding

  def waitingForBinding: Receive = {

    case Udp.Bound(localAddr) =>
      activity.log(TAG, "Bound to UDP address " + localAddr)
      sender() ! Udp.Send(ByteString("ping"), new InetSocketAddress("255.255.255.255", 2551))
      context.system.scheduler.scheduleOnce(15 seconds, self, PongTimeout)
      context.become(waitingForPong(sender()))
  }

  def waitingForPong(udp: ActorRef): Receive = {

    case Udp.Received(data, udpSender) if new String(data.toArray) == "pong" =>
      activity.log(TAG, "Found first host " + udpSender.getHostName)

      val addr = Address("akka.tcp", "AndroidCluster", udpSender.getAddress.getHostAddress, 2550)
      activity.log(TAG, "Host cluster address is " + addr)

      cluster.join(addr)
      cluster.subscribe(self, classOf[ClusterDomainEvent])
      context.become(joinedCluster(udp))

    case PongTimeout =>
      activity.log(TAG, "No hosts found")
      cluster.join(cluster.selfAddress)
      cluster.subscribe(self, classOf[ClusterDomainEvent])
      context.become(joinedCluster(udp))
  }

  def joinedCluster(udp: ActorRef): Receive = {
    case Udp.Received(data, udpSender) if new String(data.toArray) == "ping" =>
      activity.log(TAG, "Received ping from " + udpSender.getHostName)
      udp ! Udp.Send(ByteString("pong"), udpSender)

    case ccs: CurrentClusterState =>
      onUI { activity.addEntry("Current cluster members: " + ccs.members.mkString(", ")) }

    case MemberUp(member) => onUI { activity.addEntry("Member up: " + member) }
    case MemberRemoved(member, _) => onUI { activity.addEntry("Member down: " + member) }
  }
}
