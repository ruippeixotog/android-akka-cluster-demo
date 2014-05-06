package akka.android

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.io.{IO, Udp}
import akka.util.ByteString
import java.net.InetSocketAddress
import scala.concurrent.duration._
import ClusterListenerActor._

case object PongTimeout

object SocketAddress {
  def apply(port: Int): InetSocketAddress = new InetSocketAddress(port)
  def apply(host: String, port: Int): InetSocketAddress = new InetSocketAddress(host, port)
  def broadcast(port: Int): InetSocketAddress = apply("255.255.255.255", port)
  def unapply(addr: InetSocketAddress) = Some(addr.getAddress, addr.getPort)
}

object UdpText {
  def apply(text: String) = ByteString(text)
  def unapply(data: ByteString) = Some(new String(data.toArray))
}

object ClusterListenerActor {
  val TAG = "ClusterListenerActor"

  val UdpPing = UdpText("ping")
  val UdpPong = UdpText("pong")
  val UdpPort = 5225
}

class ClusterListenerActor(activity: Act) extends Actor {

  implicit def system = context.system
  implicit def executionContext = context.system.dispatcher

  activity.log(TAG, "Trying to bind to UDP address " + SocketAddress(UdpPort))
  IO(Udp) ! Udp.Bind(self, SocketAddress(UdpPort))

  val cluster = Cluster(context.system)
  activity.log(TAG, "Cluster self address is " + cluster.selfAddress)

  val clusterPort = cluster.selfAddress.port.getOrElse(2550)

  def receive = waitingForBinding

  def waitingForBinding: Receive = {

    case Udp.Bound(localAddr) =>
      activity.log(TAG, "Bound to UDP address " + localAddr)

      sender() ! Udp.Send(UdpPing, SocketAddress.broadcast(UdpPort))
      context.system.scheduler.scheduleOnce(15 seconds, self, PongTimeout)
      context.become(waitingForPong(sender()))
  }

  def waitingForPong(udp: ActorRef): Receive = {

    case Udp.Received(UdpPong, SocketAddress(udpSenderAddr, _)) =>
      activity.log(TAG, "Found first host " + udpSenderAddr)

      val addr = Address("akka.tcp", "AndroidCluster", udpSenderAddr.getHostAddress, clusterPort)
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
    case Udp.Received(UdpPing, udpSender) =>
      activity.log(TAG, "Received ping from " + udpSender.getHostName)
      udp ! Udp.Send(UdpPong, udpSender)

    case ccs: CurrentClusterState =>
      activity.addEntry("Current cluster members: " + ccs.members.mkString(", "))

    case MemberUp(member) => activity.addEntry("Member up: " + member)
    case MemberRemoved(member, _) => activity.addEntry("Member down: " + member)
  }
}
